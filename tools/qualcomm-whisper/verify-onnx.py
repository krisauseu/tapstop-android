#!/usr/bin/env python3
"""Compare two ONNX decoder steps with original official HF decoder weights.

CPU-only numerical validation of an intermediate artifact; not NPU evidence.
Only decoder tensors are loaded from the safetensors checkpoint. The encoder
is never instantiated. Nonzero deterministic cross-attention context is used.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import time

ROOT = Path.cwd() / "qualcomm-work"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model-path", type=Path, required=True)
    parser.add_argument("--onnx", type=Path, required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--threads", type=int, default=4)
    args = parser.parse_args()
    os.environ.setdefault("HF_HOME", str(ROOT / ".cache/huggingface"))
    os.environ.setdefault("TORCH_HOME", str(ROOT / ".cache/torch"))
    os.environ.setdefault("XDG_CACHE_HOME", str(ROOT / ".cache"))

    import numpy as np
    import onnxruntime as ort
    from safetensors import safe_open
    import torch
    from transformers import WhisperConfig
    from transformers.models.whisper.modeling_whisper import WhisperDecoder

    torch.manual_seed(11)
    torch.set_num_threads(args.threads)
    config = WhisperConfig.from_pretrained(args.model_path, local_files_only=True)
    config._attn_implementation = "eager"
    # Meta construction avoids allocating randomly initialized decoder weights.
    with torch.device("meta"):
        decoder = WhisperDecoder(config)
    state = {}
    for path in sorted(args.model_path.glob("*.safetensors")):
        with safe_open(path, framework="pt", device="cpu") as f:
            for name in f.keys():
                if name.startswith("model.decoder."):
                    state[name.removeprefix("model.decoder.")] = f.get_tensor(name).float()
    if not state:
        raise RuntimeError("No decoder weights in the given official safetensors checkpoint")
    decoder.load_state_dict(state, strict=True, assign=True)
    decoder.eval()
    hidden = torch.randn(1, 1500, config.d_model) * 0.1
    tokens = torch.tensor([[config.decoder_start_token_id, 50259]], dtype=torch.int64)
    with torch.no_grad():
        reference_hidden = decoder(
            input_ids=tokens, encoder_hidden_states=hidden,
            use_cache=False, return_dict=False,
        )[0]
        reference_logits = torch.nn.functional.linear(reference_hidden, decoder.embed_tokens.weight)
        cross = {}
        for i, layer in enumerate(decoder.layers):
            keys = layer.encoder_attn.k_proj(hidden).reshape(1, 1500, 20, 64).permute(2, 0, 3, 1)
            values = layer.encoder_attn.v_proj(hidden).reshape(1, 1500, 20, 64).permute(2, 0, 1, 3)
            cross[f"k_cache_cross_{i}"] = keys.numpy()
            cross[f"v_cache_cross_{i}"] = values.numpy()
    options = ort.SessionOptions()
    options.intra_op_num_threads = args.threads
    options.inter_op_num_threads = 1
    # Keep graph transformation bounded and transparent for this correctness check.
    options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_BASIC
    started = time.monotonic()
    session = ort.InferenceSession(str(args.onnx), options, providers=["CPUExecutionProvider"])
    init_seconds = time.monotonic() - started
    input_types = {item.name: item.type for item in session.get_inputs()}
    if input_types["attention_mask"] == "tensor(float)":
        dtype = np.float32
        atol, rtol = 5e-3, 1e-3
    elif input_types["attention_mask"] == "tensor(float16)":
        dtype = np.float16
        atol, rtol = 0.1, 1e-2
    else:
        raise TypeError(f"Unexpected ONNX floating interface {input_types['attention_mask']}")
    feeds = {name: value.astype(dtype) for name, value in cross.items()}
    for i in range(4):
        feeds[f"k_cache_self_{i}_in"] = np.zeros((20, 1, 64, 199), dtype=dtype)
        feeds[f"v_cache_self_{i}_in"] = np.zeros((20, 1, 199, 64), dtype=dtype)
    output_names = [item.name for item in session.get_outputs()]
    checks = []
    for step in range(2):
        mask = np.full((1, 1, 1, 200), -100.0, dtype=dtype)
        mask[..., -(step + 1):] = 0
        feeds["attention_mask"] = mask
        feeds["input_ids"] = tokens[:, step:step + 1].numpy().astype(np.int32)
        feeds["position_ids"] = np.array([step], dtype=np.int32)
        started = time.monotonic()
        outputs = dict(zip(output_names, session.run(None, feeds), strict=True))
        seconds = time.monotonic() - started
        actual = outputs["logits"].reshape(1, -1).astype(np.float32)
        reference = reference_logits[:, step].numpy()
        delta = np.abs(actual - reference)
        check = {"step": step, "max_abs": float(delta.max()), "mean_abs": float(delta.mean()),
                 "reference_argmax": int(reference.argmax()), "onnx_argmax": int(actual.argmax()),
                 "cpu_onnx_seconds": seconds, "atol": atol, "rtol": rtol}
        np.testing.assert_allclose(actual, reference, atol=atol, rtol=rtol)
        for name, value in outputs.items():
            if not np.isfinite(value).all():
                raise AssertionError(f"Nonfinite ONNX decoder output {name} step {step}")
            if name.endswith("_out"):
                feeds[name.removesuffix("_out") + "_in"] = value
        checks.append(check)
    result = {"status": "PASS", "purpose": "CPU intermediate numerical validation; not NPU evidence",
              "model": "openai/whisper-large-v3-turbo", "onnx": args.onnx.name,
              "onnx_sha256": hashlib.sha256(args.onnx.read_bytes()).hexdigest(),
              "onnxruntime": ort.__version__, "torch": torch.__version__,
              "providers": session.get_providers(), "session_init_seconds": init_seconds,
              "decoder_only_weights": True, "steps": checks}
    args.evidence.parent.mkdir(parents=True, exist_ok=True)
    args.evidence.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result, indent=2), flush=True)


if __name__ == "__main__":
    main()
