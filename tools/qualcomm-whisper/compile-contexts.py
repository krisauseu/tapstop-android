#!/usr/bin/env python3
"""QAIRT 2.50 FP16 ONNX -> DLC -> target-specific HTP contexts.

Run in an isolated Linux x86_64 Python 3.12 environment. No model
quantization, calibration, device mutation, or CPU runtime execution is performed.
The known VoiceAI contexts are used only as tensor-interface metadata references.
"""
import argparse
import hashlib
import importlib.metadata
import json
import os
from pathlib import Path
import platform
import shlex
import subprocess
import sys

from whisper_contract import reference as read_reference, validate_context
SDK_VERSION = '2.50.0.260828'


def sha256(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def tensor_map(tensors):
    result = {}
    for tensor in tensors:
        info = tensor['info']
        if info['name'] in result:
            raise ValueError(f'Duplicate tensor: {info["name"]}')
        result[info['name']] = {'shape': info['dimensions'], 'dtype': info['dataType']}
    return result


def inspect_onnx(path, component, output_dir):
    import onnx
    model = onnx.load(str(path), load_external_data=False)
    reference = read_reference(component)
    if any(node.op_type in ('QuantizeLinear', 'DequantizeLinear', 'FakeQuantize') for node in model.graph.node):
        raise ValueError(f'{path}: quantized/QDQ graph is not the original floating-point source')
    config = {}
    description = {'file': path.name, 'sha256': sha256(path), 'opsets': [
        {'domain': item.domain, 'version': item.version} for item in model.opset_import], 'inputs': [], 'outputs': []}
    preserve_integer = []
    initializers = {tensor.name for tensor in model.graph.initializer}
    for direction, proto_tensors, config_key, ref_key in (
            ('inputs', [tensor for tensor in model.graph.input if tensor.name not in initializers],
             'Input Tensor Configuration', 'graphInputs'),
            ('outputs', model.graph.output, 'Output Tensor Configuration', 'graphOutputs')):
        expected = tensor_map(reference[ref_key])
        if {tensor.name for tensor in proto_tensors} != set(expected):
            raise ValueError(f'{component}: ONNX {direction} names do not match VoiceAI metadata')
        config[config_key] = []
        for tensor in proto_tensors:
            tensor_type = tensor.type.tensor_type
            shape = [dimension.dim_value for dimension in tensor_type.shape.dim]
            if shape != expected[tensor.name]['shape']:
                raise ValueError(f'{component}/{tensor.name}: ONNX shape {shape}, expected {expected[tensor.name]["shape"]}')
            dtype = tensor_type.elem_type
            description[direction].append({'name': tensor.name, 'shape': shape, 'onnx_dtype': dtype})
            if expected[tensor.name]['dtype'] == 'QNN_DATATYPE_INT_32':
                if dtype != onnx.TensorProto.INT32:
                    raise ValueError(f'{tensor.name}: export as int32; implicit int64 narrowing is not accepted')
                preserve_integer.append(tensor.name)
                continue
            if dtype not in (onnx.TensorProto.FLOAT, onnx.TensorProto.FLOAT16):
                raise ValueError(f'{tensor.name}: expected float32 or float16 ONNX tensor, got {dtype}')
            config[config_key].append({'Name': tensor.name,
                'Src Model Parameters': {'DataType': 'float16' if dtype == onnx.TensorProto.FLOAT16 else 'float32'},
                'Desired Model Parameters': {'DataType': 'float16'}})
    # JSON is valid YAML. Passing this through --config forces Float16 external IO;
    # --float_bitwidth 16 controls internal floating-point tensors separately.
    config_path = output_dir / f'{component}-io.yaml'
    config_path.write_text(json.dumps(config, indent=2) + '\n')
    (output_dir / f'{component}-onnx-interface.json').write_text(json.dumps(description, indent=2) + '\n')
    return config_path, preserve_integer, description


def run(command, output_dir, name, env):
    print('+ ' + shlex.join(map(str, command)), flush=True)
    with (output_dir / 'commands.jsonl').open('a') as stream:
        stream.write(json.dumps({'name': name, 'command': list(map(str, command))}) + '\n')
    with (output_dir / f'{name}.log').open('w') as stream:
        process = subprocess.run(list(map(str, command)), stdout=stream, stderr=subprocess.STDOUT, env=env)
    if process.returncode:
        raise RuntimeError(f'{name} exited {process.returncode}; see {output_dir / (name + ".log")}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--encoder', type=Path)
    parser.add_argument('--decoder', type=Path)
    parser.add_argument('--output-dir', type=Path)
    parser.add_argument('--soc-model', type=int, required=True)
    parser.add_argument('--dsp-arch', type=int, required=True)
    parser.add_argument('--sdk-root', type=Path)
    parser.add_argument('--inspect-json', type=Path, help='Validate existing utility JSON only; no compile')
    parser.add_argument('--component', choices=('encoder', 'decoder'), help='Used with --inspect-json')
    args = parser.parse_args()
    if args.soc_model <= 0 or args.dsp_arch <= 0:
        parser.error('Target parameters must be positive')
    if args.inspect_json:
        if not args.component:
            parser.error('--inspect-json requires --component')
        print(json.dumps(validate_context(json.loads(args.inspect_json.read_text()), args.component, args.soc_model, args.dsp_arch), indent=2))
        return
    if not args.encoder and not args.decoder:
        parser.error('At least one original-network ONNX --encoder/--decoder is required')
    if args.sdk_root is None or args.output_dir is None:
        parser.error('Compilation requires --sdk-root and --output-dir')
    if platform.system() != 'Linux' or platform.machine() != 'x86_64':
        raise RuntimeError('Use an isolated Linux x86_64 environment, not macOS Python')
    if sys.version_info[:2] not in ((3, 10), (3, 12)):
        raise RuntimeError('QAIRT 2.50 native bindings require Python 3.10 or 3.12')
    sdk = args.sdk_root.resolve()
    if sdk.name != SDK_VERSION:
        raise ValueError(f'Expected validated QAIRT {SDK_VERSION}; got {sdk}')
    bin_dir, lib_dir = sdk / 'bin/x86_64-linux-clang', sdk / 'lib/x86_64-linux-clang'
    for path in (bin_dir / 'qairt-converter', bin_dir / 'qnn-context-binary-generator',
                 bin_dir / 'qnn-context-binary-utility', lib_dir / 'libQnnHtp.so',
                 lib_dir / 'libQnnHtpNetRunExtensions.so', lib_dir / 'libQnnSystem.so', lib_dir / 'libQnnModelDlc.so'):
        if not path.is_file():
            raise FileNotFoundError(f'Missing SDK component: {path}')
    output_dir = args.output_dir.resolve()
    output_dir.mkdir(parents=True, exist_ok=False)
    env = os.environ.copy()
    env.update(QAIRT_SDK_ROOT=str(sdk), QNN_SDK_ROOT=str(sdk), SNPE_ROOT=str(sdk),
               PYTHONPATH=str(sdk / 'lib/python') + os.pathsep + env.get('PYTHONPATH', ''),
               LD_LIBRARY_PATH=str(lib_dir) + os.pathsep + env.get('LD_LIBRARY_PATH', ''))
    versions = {'python': sys.version, 'platform': platform.platform(), 'qairt': SDK_VERSION}
    for package in ('numpy', 'torch', 'transformers', 'onnx', 'onnxruntime', 'onnxsim', 'PyYAML'):
        try:
            versions[package] = importlib.metadata.version(package)
        except importlib.metadata.PackageNotFoundError:
            versions[package] = None
    (output_dir / 'versions.json').write_text(json.dumps(versions, indent=2) + '\n')
    components = [(name, path.resolve()) for name, path in (('encoder', args.encoder), ('decoder', args.decoder)) if path]
    perf_path = output_dir / 'PerfSetting.conf'
    perf_path.write_text(json.dumps({'graphs': [{'vtcm_mb': 8, 'O': 3.0,
        'graph_names': [f'hf_whisper_{name}' for name, _ in components]}],
        'devices': [{'soc_model': args.soc_model, 'dsp_arch': f'v{args.dsp_arch}', 'pd_session': 'unsigned',
                     'cores': [{'perf_profile': 'burst', 'rpc_control_latency': 100}]}]}, indent=2) + '\n')
    backend_config = output_dir / 'HtpConfigFile.json'
    backend_config.write_text(json.dumps({'backend_extensions': {
        'shared_library_path': str(lib_dir / 'libQnnHtpNetRunExtensions.so'),
        'config_file_path': str(perf_path)}}, indent=2) + '\n')
    report = {'status': 'PARTIAL', 'qairt': SDK_VERSION,
              'socModel': args.soc_model, 'dspArch': args.dsp_arch,
              'hardwareValidated': False, 'components': {}}
    for component, onnx_path in components:
        config, integers, source = inspect_onnx(onnx_path, component, output_dir)
        dlc = output_dir / f'hf_whisper_{component}.dlc'
        command = [sys.executable, bin_dir / 'qairt-converter', '--input_network', onnx_path,
                   '--output_path', dlc, '--float_bitwidth', '16', '--float_bias_bitwidth', '16',
                   '--config', config, '--onnx_skip_simplification']
        if integers:
            command += ['--preserve_io_datatype'] + integers
        if component == 'encoder':
            command += ['--desired_input_color_encoding', 'input_features', 'other']
        else:
            # Match the legacy Whisper decoder's treatment of head/cache axes.
            # QAIRT 2.50 retains this deprecated spelling for compatibility.
            for tensor in source['inputs']:
                command += ['--source_model_input_layout', tensor['name'], 'NONTRIVIAL']
        run(command, output_dir, f'{component}-convert', env)
        run([bin_dir / 'qnn-context-binary-generator', '--model', lib_dir / 'libQnnModelDlc.so',
             '--dlc_path', dlc, '--backend', lib_dir / 'libQnnHtp.so', '--config_file', backend_config,
             '--binary_file', component, '--output_dir', output_dir, '--retain_tensor_name',
             '--log_level', 'info'], output_dir, f'{component}-compile', env)
        context = output_dir / f'{component}.bin'
        if not context.is_file() or not context.stat().st_size:
            raise RuntimeError(f'Compiler did not produce {context}')
        inspection = output_dir / f'{component}-context.json'
        run([bin_dir / 'qnn-context-binary-utility', '--context_binary', context, '--json_file', inspection],
            output_dir, f'{component}-inspect', env)
        validated = validate_context(json.loads(inspection.read_text()), component, args.soc_model, args.dsp_arch)
        validated.update(context=context.name, bytes=context.stat().st_size, sha256=sha256(context), source=source)
        report['components'][component] = validated
        if len(report['components']) == 2:
            report['status'] = 'BOTH_CONTEXTS_INSPECTED_HARDWARE_VALIDATION_REQUIRED'
        (output_dir / 'context-inspection.json').write_text(json.dumps(report, indent=2) + '\n')
        print(f'VERIFIED {component}: socModel={args.soc_model} dspArch={args.dsp_arch} VoiceAI FP16 IO exact', flush=True)
    print(f'Build/inspection artifacts: {output_dir}', flush=True)


if __name__ == '__main__':
    main()
