#!/usr/bin/env python3
"""Copy a known accepted model into a fresh local TapStop import directory.

No downloads, device writes, source changes or publication. A fresh recompile
with different hashes needs separate review and real-device acceptance first.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil


def verify(path, expected):
    if not path.is_file() or path.stat().st_size != expected["bytes"]:
        raise ValueError(f"Missing file or wrong size: {path.name}")
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(8 << 20), b""):
            digest.update(block)
    if digest.hexdigest() != expected["sha256"]:
        raise ValueError(f"Wrong SHA256: {path.name}")


def main():
    packages = json.loads(Path(__file__).with_name("model-packages.json").read_text())
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target", choices=packages, required=True)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    package = packages[args.target]
    for name, expected in package["files"].items():
        verify(args.source / name, expected)
        print(f"Source verified: {name}", flush=True)
    args.output.mkdir(parents=True, exist_ok=False)
    for name, expected in package["files"].items():
        shutil.copyfile(args.source / name, args.output / name)
        verify(args.output / name, expected)
        print(f"Copy verified: {name}", flush=True)
    # Write last: an incomplete package never has a completed import manifest.
    (args.output / "tapstop-model.json").write_text(json.dumps(package, indent=2) + "\n")
    print(f"Local import package ready: {args.target}")


if __name__ == "__main__":
    main()
