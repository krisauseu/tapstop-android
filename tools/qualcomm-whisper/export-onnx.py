#!/usr/bin/env python3
"""Local optimized FP32 Whisper ONNX export using pinned Qualcomm public rewrites.

This creates intermediate ONNX models, NOT final QNN HTP context binaries.
No AIMET, calibration, training, AI Hub API, or cloud compute is used.
"""

from __future__ import annotations

import argparse
import gc
import hashlib
import importlib.metadata
import json
import os
from pathlib import Path
import platform
import sys
import time

from whisper_contract import reference as read_reference

ROOT = Path.cwd() / "qualcomm-work"
HF_REVISION = "41f01f3fe87f28c78e2fbf8b568835947dd65ed9"
MODEL_ID = "openai/whisper-large-v3-turbo"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(8 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True, help="Directory made by fetch-sources.py")
    parser.add_argument("--model-path", type=Path, required=True, help="Existing pinned official HF snapshot")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--component", choices=["both", "encoder", "decoder"], default="both")
    parser.add_argument("--opset", type=int, default=18)
    parser.add_argument("--threads", type=int, default=4)
    args = parser.parse_args()
    args.dtype, args.exporter, args.optimize = "float32", "dynamo", True
    args.revision = HF_REVISION
    source = args.source.resolve()
    args.output.mkdir(parents=True, exist_ok=False)
    # All incidental Hugging Face / torch caches stay inside the local workspace.
    os.environ.setdefault("HF_HOME", str(ROOT / ".cache/huggingface"))
    os.environ.setdefault("TORCH_HOME", str(ROOT / ".cache/torch"))
    os.environ.setdefault("XDG_CACHE_HOME", str(ROOT / ".cache"))
    os.environ.setdefault("HF_HUB_DISABLE_TELEMETRY", "1")
    os.environ.setdefault("TOKENIZERS_PARALLELISM", "false")
    sys.path.insert(0, str(source / "src"))

    import numpy as np
    import onnx
    import torch
    from transformers import WhisperForConditionalGeneration

    torch.set_num_threads(args.threads)
    torch.manual_seed(0)
    source_manifest = json.loads((source / "source-manifest.json").read_text())
    from source_pins import REVISION, FILES
    if source_manifest != {"revision": REVISION, "files": FILES}:
        raise ValueError("Unexpected Qualcomm source manifest")
    for item in source_manifest["files"]:
        if sha256(source / item["path"]) != item["sha256"]:
            raise RuntimeError(f"Pinned Qualcomm source changed: {item['path']}")
    from qai_hub_models.models.templates.hf_whisper.model_adaptation import monkey_patch_model
    for module_name, relative in (
            ("qai_hub_models.models.templates.hf_whisper.model_adaptation", "models/templates/hf_whisper/model_adaptation.py"),
            ("qai_hub_models.utils.model_adapters", "utils/model_adapters.py"),
            ("qai_hub_models.utils.torch_typing_helpers", "utils/torch_typing_helpers.py")):
        loaded = Path(sys.modules[module_name].__file__).resolve()
        expected_module = source / "src/qai_hub_models" / relative
        if loaded != expected_module.resolve():
            raise RuntimeError(f"Installed package shadows the pinned source: {module_name}")
    versions = {"python": platform.python_version(), "platform": platform.platform()}
    for package in ["torch", "transformers", "onnx", "onnxscript", "onnx-ir", "numpy", "safetensors", "huggingface-hub"]:
        try:
            versions[package] = importlib.metadata.version(package)
        except importlib.metadata.PackageNotFoundError:
            versions[package] = None
    if versions["transformers"] != "4.56.2":
        raise RuntimeError("Pinned Qualcomm adaptation requires transformers==4.56.2")
    print(json.dumps(versions, indent=2), flush=True)
    model_path = args.model_path.resolve()
    weights = [p for p in sorted(model_path.glob("*.safetensors"))]
    if len(weights) != 1 or sha256(weights[0]) != "542566a422ae4f3fd23f1ba11add198fca01bbf82e66e6a2857b3f608b1eb9d1":
        raise ValueError("Expected the pinned original large-v3-turbo safetensors checkpoint")
    provenance = {
        "source": MODEL_ID, "revision": args.revision,
        "qualcomm_revision": source_manifest["revision"], "versions": versions,
        "model": MODEL_ID, "exporter": args.exporter, "optimize": args.optimize,
        "intermediate_dtype": args.dtype, "opset": args.opset,
        "weights": [{"name": p.name, "size": p.stat().st_size, "sha256": sha256(p)} for p in weights],
        "final_qnn_context": False,
    }
    (args.output / "provenance.json").write_text(json.dumps(provenance, indent=2) + "\n")
    print("Loading original official model on CPU in FP32", flush=True)
    model = WhisperForConditionalGeneration.from_pretrained(
        model_path, local_files_only=True, torch_dtype=torch.float32,
        low_cpu_mem_usage=True, attn_implementation="eager",
    ).eval()
    config = model.config
    expected = {"encoder_layers": 32, "decoder_layers": 4, "d_model": 1280,
                "decoder_attention_heads": 20, "num_mel_bins": 128, "vocab_size": 51866,
                "max_source_positions": 1500}
    for name, value in expected.items():
        if getattr(config, name) != value:
            raise ValueError(f"Wrong model configuration {name}: {getattr(config, name)} != {value}")
    # Qualcomm's public adaptation deliberately ties the output projection to
    # embed_tokens. Confirm that the official checkpoint actually has that property.
    if not torch.equal(model.proj_out.weight, model.model.decoder.embed_tokens.weight):
        raise ValueError("Original checkpoint output projection is not tied to token embeddings")

    # Cheap but meaningful semantic check: two decoder steps against the original
    # Hugging Face decoder, using deterministic nonzero encoder hidden states.
    # This checks cross-cache packing, cache update order, causal mask and positions.
    hidden = torch.randn(1, 1500, config.d_model) * 0.01
    tokens = torch.tensor([[config.decoder_start_token_id, 50259]], dtype=torch.int64)
    with torch.no_grad():
        reference_hidden = model.model.decoder(
            input_ids=tokens, encoder_hidden_states=hidden,
            use_cache=False, return_dict=False,
        )[0]
        reference_logits = model.proj_out(reference_hidden).detach()
    config.return_dict = False
    config.tie_word_embeddings = False
    config.mask_neg = -100.0
    print("Applying original Qualcomm split-head/Conv2d/cache rewrites", flush=True)
    monkey_patch_model(model.model)
    model.eval()

    class Encoder(torch.nn.Module):
        def __init__(self, encoder):
            super().__init__()
            self.encoder = encoder

        def forward(self, input_features):
            caches = self.encoder(input_features)[0]
            return tuple(tensor for pair in caches for tensor in pair)

    class Decoder(torch.nn.Module):
        def __init__(self, decoder):
            super().__init__()
            self.decoder = decoder

        def forward(self, input_ids, attention_mask, *cache_and_position):
            self_cache = tuple((cache_and_position[i], cache_and_position[i + 1]) for i in range(0, 8, 2))
            cross_cache = tuple((cache_and_position[i], cache_and_position[i + 1]) for i in range(8, 16, 2))
            logits, new_cache = self.decoder(
                input_ids=input_ids, attention_mask=attention_mask,
                past_key_values=self_cache, cross_attn_past_key_value=cross_cache,
                position_ids=cache_and_position[-1].to(torch.int64),
            )
            return (logits, *(tensor for pair in new_cache for tensor in pair))

    encoder = Encoder(model.model.encoder).eval()
    decoder = Decoder(model.model.decoder).eval()
    with torch.no_grad():
        cross = []
        h = hidden.unsqueeze(1).permute(0, 3, 1, 2)
        for layer in decoder.decoder.layers:
            cross.extend([
                torch.cat([p(h).permute(0, 2, 1, 3) for p in layer.encoder_attn.k_proj_sha], dim=0),
                torch.cat([p(h).permute(0, 2, 3, 1) for p in layer.encoder_attn.v_proj_sha], dim=0),
            ])
        cache = [t for _ in range(4) for t in (torch.zeros(20, 1, 64, 199), torch.zeros(20, 1, 199, 64))]
        checks = []
        for step in range(2):
            mask = torch.full((1, 1, 1, 200), -100.0)
            mask[..., -(step + 1):] = 0.0
            result = decoder(tokens[:, step:step + 1].to(torch.int32), mask, *cache, *cross, torch.tensor([step], dtype=torch.int32))
            actual = result[0].reshape(1, -1)
            reference = reference_logits[:, step]
            delta = (actual - reference).abs()
            check = {"step": step, "max_abs": float(delta.max()), "mean_abs": float(delta.mean()),
                     "reference_argmax": int(reference.argmax()), "adapted_argmax": int(actual.argmax())}
            checks.append(check)
            np.testing.assert_allclose(actual.numpy(), reference.numpy(), atol=5e-3, rtol=1e-3)
            cache = list(result[1:])
    print("Decoder semantic comparison: " + json.dumps(checks), flush=True)
    (args.output / "decoder-numerics.json").write_text(json.dumps(checks, indent=2) + "\n")
    del model, hidden, reference_hidden, reference_logits, cross, cache, result, actual, reference, delta, h
    gc.collect()
    dtype = getattr(torch, args.dtype)
    encoder.to(dtype=dtype)
    decoder.to(dtype=dtype)

    encoder_inputs = {"input_features": torch.zeros(1, 128, 3000, dtype=dtype)}
    decoder_inputs = {"input_ids": torch.tensor([[config.decoder_start_token_id]], dtype=torch.int32),
                      "attention_mask": torch.full((1, 1, 1, 200), -100.0, dtype=dtype)}
    decoder_inputs["attention_mask"][..., -1] = 0
    for i in range(4):
        decoder_inputs[f"k_cache_self_{i}_in"] = torch.zeros(20, 1, 64, 199, dtype=dtype)
        decoder_inputs[f"v_cache_self_{i}_in"] = torch.zeros(20, 1, 199, 64, dtype=dtype)
    for i in range(4):
        decoder_inputs[f"k_cache_cross_{i}"] = torch.zeros(20, 1, 64, 1500, dtype=dtype)
        decoder_inputs[f"v_cache_cross_{i}"] = torch.zeros(20, 1, 1500, 64, dtype=dtype)
    decoder_inputs["position_ids"] = torch.zeros(1, dtype=torch.int32)
    encoder_outputs = [f"{kind}_cache_cross_{i}" for i in range(4) for kind in ("k", "v")]
    decoder_outputs = ["logits", *[f"{kind}_cache_self_{i}_out" for i in range(4) for kind in ("k", "v")]]

    for component, module, inputs, output_names in [
        ("decoder", decoder, decoder_inputs, decoder_outputs),
        ("encoder", encoder, encoder_inputs, encoder_outputs),
    ]:
        if args.component not in ("both", component):
            continue
        path = args.output / f"hf_whisper_{component}.onnx"
        print(f"Exporting {component}: {path}", flush=True)
        started = time.monotonic()
        with torch.no_grad():
            torch.onnx.export(
                module, tuple(inputs.values()), str(path),
                input_names=list(inputs), output_names=output_names,
                opset_version=args.opset, dynamo=args.exporter == "dynamo",
                external_data=True, optimize=args.optimize,
                report=args.exporter == "dynamo", artifacts_dir=str(args.output),
            )
        graph = onnx.load(str(path), load_external_data=False)
        graph.graph.name = f"hf_whisper_{component}"
        onnx.save_model(graph, str(path))
        # Validate exact names/shapes and intermediate dtypes against observed
        # S24 VoiceAI contracts; final FP16 conversion is QAIRT's responsibility.
        reference = read_reference(component)
        observed = {}
        for group, tensors in [("inputs", graph.graph.input), ("outputs", graph.graph.output)]:
            expected_tensors = {x["info"]["name"]: x["info"] for x in reference["graphInputs" if group == "inputs" else "graphOutputs"]}
            observed[group] = {}
            for tensor in tensors:
                shape = [d.dim_value for d in tensor.type.tensor_type.shape.dim]
                element = tensor.type.tensor_type.elem_type
                ref = expected_tensors.pop(tensor.name)
                expected_type = onnx.TensorProto.INT32 if ref["dataType"] == "QNN_DATATYPE_INT_32" else (onnx.TensorProto.FLOAT16 if args.dtype == "float16" else onnx.TensorProto.FLOAT)
                if shape != ref["dimensions"] or element != expected_type:
                    raise ValueError(f"Interface mismatch {component}.{tensor.name}: {shape}/{element} vs {ref['dimensions']}/{expected_type}")
                observed[group][tensor.name] = {"shape": shape, "dtype": onnx.TensorProto.DataType.Name(element)}
            if expected_tensors:
                raise ValueError(f"Missing {component} {group}: {list(expected_tensors)}")
        onnx.checker.check_model(str(path))
        observed["graph_name"] = graph.graph.name
        observed["export_seconds"] = time.monotonic() - started
        observed["sha256"] = sha256(path)
        observed["qualcomm_source"] = source_manifest["revision"]
        observed["final_qnn_context"] = False
        (args.output / f"{component}-interface.json").write_text(json.dumps(observed, indent=2) + "\n")
        print(f"Verified {component} ONNX interface in {observed['export_seconds']:.1f}s", flush=True)
        del graph
        gc.collect()


if __name__ == "__main__":
    main()
