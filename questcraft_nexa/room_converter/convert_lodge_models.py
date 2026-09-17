#!/usr/bin/env python3
"""Convert the QuestCraft launch-room model sources to Android-friendly GLB.

The source wrapper is GPLv3. QCWorld is converted geometry-first with its MTL
texture maps stripped before Assimp sees it, so Minecraft texture bytes are not
copied into the APK. Logical material/texture names stay in nexa-room.json and
can be resolved from the user's own legitimate Minecraft installation.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import subprocess
import tempfile
from pathlib import Path

EXPECTED_MODEL_COUNT = 8


def safe_name(value: str) -> str:
    value = re.sub(r"[^A-Za-z0-9._-]+", "_", value).strip("_.")
    return value or "model"


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def sanitize_qcworld(source: Path, temp_root: Path) -> Path:
    """Copy QCWorld OBJ/MTL but remove texture-map directives.

    This preserves meshes, UVs and material slots while deliberately avoiding
    bundling Minecraft texture images into the generated GLB.
    """
    work = temp_root / "qcworld"
    work.mkdir(parents=True, exist_ok=True)
    dst_obj = work / source.name
    shutil.copy2(source, dst_obj)

    src_mtl = source.with_suffix(".mtl")
    if src_mtl.exists():
        kept = []
        for raw in src_mtl.read_text(encoding="utf-8", errors="replace").splitlines():
            line = raw.lstrip().lower()
            if line.startswith(("map_", "bump ", "disp ", "decal ", "refl ")):
                continue
            kept.append(raw)
        (work / src_mtl.name).write_text("\n".join(kept) + "\n", encoding="utf-8")
    return dst_obj


def convert(assimp: str, source: Path, output: Path) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(
        [assimp, "export", str(source), str(output), "-fglb2"],
        check=True,
    )
    if not output.is_file() or output.stat().st_size < 128:
        raise RuntimeError(f"Assimp produced an invalid GLB: {output}")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("room_spec", type=Path)
    ap.add_argument("qcxr_root", type=Path)
    ap.add_argument("output_dir", type=Path)
    ap.add_argument("--assimp", default="assimp")
    args = ap.parse_args()

    spec = json.loads(args.room_spec.read_text(encoding="utf-8"))
    entries = [spec["qcworld"], *spec.get("props", [])]
    if len(entries) != EXPECTED_MODEL_COUNT:
        raise SystemExit(
            f"Expected {EXPECTED_MODEL_COUNT} launch-room model instances, got {len(entries)}"
        )

    seen_sources = set()
    conversion_report = []
    args.output_dir.mkdir(parents=True, exist_ok=True)

    with tempfile.TemporaryDirectory(prefix="nexa-lodge-") as temp:
        temp_root = Path(temp)
        for index, item in enumerate(entries):
            source_rel = ((item.get("source") or {}).get("path") or "").strip()
            if not source_rel:
                raise RuntimeError(f"Missing source path for room model #{index}")
            if source_rel in seen_sources:
                raise RuntimeError(f"Duplicate launch-room model source: {source_rel}")
            seen_sources.add(source_rel)

            source = args.qcxr_root / source_rel
            if not source.is_file():
                raise FileNotFoundError(source)

            base = safe_name(Path(source_rel).stem)
            filename = f"{index:02d}_{base}.glb"
            output = args.output_dir / filename

            assimp_source = source
            texture_policy = "public-wrapper-assets"
            if source_rel == "Assets/QCWorld/QCWorld.obj":
                assimp_source = sanitize_qcworld(source, temp_root)
                texture_policy = "geometry-only; Minecraft texture maps stripped"

            convert(args.assimp, assimp_source, output)
            runtime_path = f"nexa/lodge_models/{filename}"
            item["runtime_model"] = runtime_path
            item["runtime_model_sha256"] = sha256(output)
            item["runtime_model_bytes"] = output.stat().st_size
            item["runtime_texture_policy"] = texture_policy
            conversion_report.append(
                {
                    "source": source_rel,
                    "runtime_model": runtime_path,
                    "bytes": output.stat().st_size,
                    "sha256": item["runtime_model_sha256"],
                    "texture_policy": texture_policy,
                }
            )

    spec["runtime_models"] = conversion_report
    spec["asset_policy"]["runtime_model_format"] = "glTF 2.0 binary (GLB)"
    spec["asset_policy"]["qcworld_glb_contains_minecraft_texture_bytes"] = False
    args.room_spec.write_text(json.dumps(spec, indent=2, sort_keys=False), encoding="utf-8")

    print(f"Converted {len(conversion_report)} QuestCraft lodge models to GLB")
    for item in conversion_report:
        print(f"  {item['bytes']:>9}  {item['runtime_model']}  <-  {item['source']}")


if __name__ == "__main__":
    main()
