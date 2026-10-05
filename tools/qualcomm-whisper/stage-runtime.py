#!/usr/bin/env python3
"""Stage pinned runtime objects and their notices for a local TapStop APK build.

The inputs remain read-only. Run only with SDKs obtained under their applicable
license. Staged proprietary objects must not be committed or published alone.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil


def digest(path):
    with path.open("rb") as stream:
        value = hashlib.sha256()
        for block in iter(lambda: stream.read(1 << 20), b""):
            value.update(block)
    return value.hexdigest()


def verify(path, expected):
    if not path.is_file() or path.stat().st_size != expected["bytes"] or digest(path) != expected["sha256"]:
        raise ValueError(f"Missing or changed pinned runtime file: {path.name}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--s24-poc", type=Path, required=True)
    parser.add_argument("--magicpad-poc", type=Path, required=True)
    parser.add_argument("--voiceai-sdk", type=Path, required=True, help="VoiceAI 2.7.1.0 version directory")
    parser.add_argument("--qairt-license-dir", type=Path, required=True,
                        help="Directory holding official QAIRT 2.50 LICENSE.pdf, NOTICE.txt, QNN_NOTICE.txt")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    pins = json.loads(Path(__file__).with_name("runtime-files.json").read_text())
    voiceai_objects = {"whisper-sdk.jar", "libwhisperfunction_jni.so", "libwhisperfunction.so",
                       "libwhisper_lib.so", "libfft.so", "libdnnvad.so", "libopencc.so", "libopencc_jni.so"}
    sdk_objects = args.voiceai_sdk / "whisper_sdk/libs/npu/rpc_libraries/android/whisper_all_quantized"
    for name in voiceai_objects:
        path = sdk_objects / name if name.endswith(".jar") else sdk_objects / "arm64-v8a" / name
        verify(path, pins[name])
    verify(args.voiceai_sdk / "whisper_sdk/libs/npu/rpc_libraries/assets/arm64-v8a_android/libnnvad_model.so",
           pins["libnnvad_model.so"])
    sources = []
    for name, expected in pins.items():
        roots = [args.magicpad_poc] if "V81" in name else [args.s24_poc]
        if "V75" not in name and "V81" not in name:
            roots.append(args.magicpad_poc)
        paths = []
        for root in roots:
            sample = root / "android/whispersample"
            if name.endswith(".jar"):
                path, output = sample / "libs" / name, Path("libs") / name
            elif "Skel" in name:
                path = sample / "src/main/assets" / name
                output = Path("assets/qualcomm") / name
            else:
                path = sample / "libs/arm64-v8a" / name
                output = Path("jniLibs/arm64-v8a") / name
            verify(path, expected)
            paths.append(path)
        sources.append((paths[0], output))

    sdk = args.voiceai_sdk
    notices = [
        (sdk / "Qualcomm AI Stack Proprietary License.pdf", "VoiceAI-AI-Stack-License.pdf",
         "1c5471e8087d32e2c3a30902d421c65c61093c96c3cf7b3de31dae8af9cc96c5"),
        (sdk / "whisper_sdk/LICENSE", "VoiceAI-LICENSE.txt",
         "64cb3f7075993865937228afd923dedb3f08df9492200c4811d042b1f8f53fdc"),
        (sdk / "whisper_sdk/NOTICE", "VoiceAI-NOTICE.txt",
         "b7bc294369c237ed02a8d1f2dc5d4551a97c0ef94c33c60a42964860d8603b1e"),
        (args.qairt_license_dir / "LICENSE.pdf", "QAIRT-LICENSE.pdf",
         "ec1dccfdcba5c6e64126e84199b8362bf4999107bfa567ebe831dbb4c461692b"),
        (args.qairt_license_dir / "NOTICE.txt", "QAIRT-NOTICE.txt",
         "0c5e8aad3506d0ab881cabaf0dae8de64e1784dc1ee6c873caf24a78fbf01924"),
        (args.qairt_license_dir / "QNN_NOTICE.txt", "QNN-NOTICE.txt",
         "0c5e8aad3506d0ab881cabaf0dae8de64e1784dc1ee6c873caf24a78fbf01924"),
    ]
    for path, name, expected in notices:
        if not path.is_file() or digest(path) != expected:
            raise ValueError(f"Missing or changed license/notice: {name}")
        sources.append((path, Path("assets/notices") / name))
    args.output.mkdir(parents=True, exist_ok=False)
    inventory = {}
    for source, relative in sources:
        destination = args.output / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, destination)
        sha = digest(destination)
        if sha != digest(source):
            raise ValueError(f"Copy verification failed: {relative}")
        inventory[relative.as_posix()] = {"bytes": destination.stat().st_size, "sha256": sha}
    manifest = {"voiceai_version": "2.7.1.0", "qairt_version": "2.50.0.260828",
                "standalone_redistribution": False, "files": inventory}
    (args.output / "assets/qualcomm/runtime-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(f"Staged {len(inventory)} pinned files with complete notices. No model weights included.")


if __name__ == "__main__":
    main()
