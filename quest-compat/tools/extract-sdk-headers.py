#!/usr/bin/env python3
"""Extract only the ABI declarations from the pinned SDK archive."""
import hashlib
from pathlib import Path
import sys
import zipfile

archive, destination = Path(sys.argv[1]), Path(sys.argv[2])
raw = archive.read_bytes()
git_sha = hashlib.sha1(b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest()
if git_sha != 'c5d3fa6ef4f722616cb6339055c9cad9f87c9e43':
    raise SystemExit('SDK archive differs from the pinned Git blob')
with zipfile.ZipFile(archive) as source:
    headers = [n for n in source.namelist() if n.startswith('VrApi/Include/') and n.endswith('.h')]
    if not headers:
        raise SystemExit('SDK archive has no VrApi/Include headers')
    for name in headers + ['LICENSE.txt']:
        if name not in source.namelist():
            continue
        if '..' in Path(name).parts:
            raise SystemExit('Unsafe archive path')
        target = destination / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(source.read(name))
version = (destination / 'VrApi/Include/VrApi_Version.h').read_text()
if '#define VRAPI_MINOR_VERSION 50' not in version:
    raise SystemExit('SDK version does not match the 1.1.50 declarations')
print('Verified SDK archive; only ABI headers and license extracted')
