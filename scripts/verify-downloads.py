#!/usr/bin/env python3
"""Verify the checksum lists shipped with Papyrus downloads."""
import argparse
import hashlib
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("directory", type=Path)
directory = parser.parse_args().directory.resolve()
lists = sorted(directory.glob("*-SHA256SUMS.txt"))
if not lists:
    raise SystemExit("No checksum lists found")
for listing in lists:
    for line in listing.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        expected, filename = line.split(maxsplit=1)
        path = (directory / filename).resolve()
        if path.parent != directory:
            raise SystemExit("Invalid download path")
        with path.open("rb") as source:
            actual = hashlib.file_digest(source, "sha256").hexdigest()
        if actual != expected:
            raise SystemExit(f"Checksum mismatch: {filename}")
        print(f"Verified {filename}")
