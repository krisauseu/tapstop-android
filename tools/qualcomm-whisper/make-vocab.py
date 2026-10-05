#!/usr/bin/env python3
"""Recreate the verified VoiceAI vocabulary from the pinned OpenAI token table.

The byte format follows public QAI Hub hf_whisper/utils.py (BSD-3-Clause),
commit 671590e9e5c3121e3c1ef693444f0787638d6951. No vendor source is copied.
"""
import argparse
import base64
import hashlib
from pathlib import Path

SOURCE_SHA256 = "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126"
VOCAB_SHA256 = "0ba87984671b92e03b56b84ce9b217020663f6a269b5a9800901391430b79c4b"


def make_vocab(source):
    if hashlib.sha256(source).hexdigest() != SOURCE_SHA256:
        raise ValueError("Expected the pinned OpenAI multilingual.tiktoken file")
    result = bytearray()
    for rank, line in enumerate(source.splitlines()):
        encoded, encoded_rank = line.split()
        if int(encoded_rank) != rank:
            raise ValueError("Token ranks must be contiguous and ordered")
        # The pinned OpenAI table includes a padding-only empty token. Follow
        # the published VoiceAI conversion's permissive base64 decoding.
        token = base64.b64decode(encoded)
        result.extend(token)
        if 0 not in token:
            result.append(0)
    if len(result) != 357313 or hashlib.sha256(result).hexdigest() != VOCAB_SHA256:
        raise ValueError("Generated vocabulary differs from the validated VoiceAI vocabulary")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tiktoken", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    data = make_vocab(args.tiktoken.read_bytes())
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("xb") as stream:
        stream.write(data)
    print(f"Verified vocabulary: {len(data)} bytes, SHA256 {VOCAB_SHA256}")


if __name__ == "__main__":
    main()
