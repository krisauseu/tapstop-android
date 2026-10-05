#!/usr/bin/env python3
"""Fetch four public BSD-3-Clause files into an explicit local build directory."""
import argparse
import hashlib
import json
from pathlib import Path
from urllib.request import urlopen

from source_pins import FILES, REVISION


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    for item in FILES:
        url = f"https://raw.githubusercontent.com/qualcomm/ai-hub-models/{REVISION}/{item['path']}"
        with urlopen(url, timeout=60) as response:
            data = response.read()
        if hashlib.sha256(data).hexdigest() != item["sha256"]:
            raise ValueError(f"Upstream file does not match pinned source: {item['path']}")
        output = args.output / item["path"]
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_bytes(data)
        print(f"Verified {item['path']}")
    (args.output / "source-manifest.json").write_text(
        json.dumps({"revision": REVISION, "files": FILES}, indent=2) + "\n")


if __name__ == "__main__":
    main()
