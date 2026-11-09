#!/usr/bin/env python3
"""Build the release source archive byte-for-byte from the frozen source manifest."""
import hashlib
import json
from pathlib import Path
from zipfile import ZipFile, ZipInfo, ZIP_STORED

root = Path.cwd()
release = Path(__file__).resolve().parent
files = json.loads((release / "source-manifest.json").read_text())["files"]
with ZipFile(release / "arioso-source.zip", "w") as archive:
    for name, expected in sorted(files.items()):
        path = Path(name)
        if path.is_absolute() or ".." in path.parts or not path.parts:
            raise RuntimeError("Unsafe source path")
        payload = (root / path).read_bytes()
        if hashlib.sha256(payload).hexdigest() != expected:
            raise RuntimeError("Source differs: " + name)
        info = ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
        info.compress_type = ZIP_STORED
        info.external_attr = 0o100644 << 16
        archive.writestr(info, payload)
print("Built source archive from", len(files), "verified files")
