#!/usr/bin/env python3
"""Build a Unity GUID -> asset path index without importing Unity.

This intentionally records paths/metadata only. It does not copy third-party or
Minecraft asset bytes into the Nexa repository.
"""
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

GUID_RE = re.compile(r"(?m)^guid:\s*([0-9a-fA-F]{32})\s*$")


def build_guid_index(assets_root: Path) -> dict[str, str]:
    index: dict[str, str] = {}
    collisions: dict[str, list[str]] = {}
    for meta in assets_root.rglob("*.meta"):
        try:
            text = meta.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        m = GUID_RE.search(text)
        if not m:
            continue
        guid = m.group(1).lower()
        asset = meta.with_suffix("")
        try:
            rel = asset.relative_to(assets_root.parent).as_posix()
        except ValueError:
            rel = asset.as_posix()
        if guid in index and index[guid] != rel:
            collisions.setdefault(guid, [index[guid]]).append(rel)
        else:
            index[guid] = rel
    if collisions:
        raise RuntimeError("Unity GUID collisions found: " + json.dumps(collisions, indent=2))
    return index


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("assets_root", type=Path)
    ap.add_argument("output", type=Path)
    args = ap.parse_args()
    idx = build_guid_index(args.assets_root)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(idx, indent=2, sort_keys=True), encoding="utf-8")
    print(f"GUID index: {len(idx)} assets -> {args.output}")


if __name__ == "__main__":
    main()
