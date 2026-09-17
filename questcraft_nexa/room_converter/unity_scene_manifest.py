#!/usr/bin/env python3
"""Parse enough of Unity text scenes to reconstruct the QuestCraft lodge graph.

The output is renderer-neutral JSON: names, hierarchy, local transforms and
resolved external GUID references. Built-in Unity mesh GUIDs remain marked as
built-in and can be recreated as primitives by the Android renderer.
"""
from __future__ import annotations

import argparse
import json
import re
from collections import Counter
from pathlib import Path

BLOCK_RE = re.compile(r"(?m)^--- !u!(\d+) &(\-?\d+)(?: stripped)?\s*$")
GUID_RE = re.compile(r"guid:\s*([0-9a-fA-F]{32})")
FILEID_RE = re.compile(r"fileID:\s*(-?\d+)")


def scalar(text: str, key: str):
    m = re.search(rf"(?m)^\s*{re.escape(key)}:\s*(.*?)\s*$", text)
    return m.group(1).strip() if m else None


def ref_fileid(text: str, key: str):
    m = re.search(rf"(?m)^\s*{re.escape(key)}:\s*\{{([^}}]+)\}}", text)
    if not m:
        return None
    f = FILEID_RE.search(m.group(1))
    return int(f.group(1)) if f else None


def vec(text: str, key: str, keys: tuple[str, ...]):
    m = re.search(rf"(?m)^\s*{re.escape(key)}:\s*\{{([^}}]+)\}}", text)
    if not m:
        return None
    body = m.group(1)
    out = {}
    for k in keys:
        km = re.search(rf"(?:^|,)\s*{re.escape(k)}:\s*([-+0-9.eE]+)", body)
        if km:
            try:
                out[k] = float(km.group(1))
            except ValueError:
                pass
    return out or None


def external_refs(text: str, guid_index: dict[str, str]):
    refs = []
    seen = set()
    for m in re.finditer(r"\{([^{}]*?guid:\s*([0-9a-fA-F]{32})[^{}]*?)\}", text):
        body, guid = m.group(1), m.group(2).lower()
        if guid == "0000000000000000e000000000000000":
            path = "<unity-built-in>"
        else:
            path = guid_index.get(guid, "<unresolved>")
        fm = FILEID_RE.search(body)
        item = {
            "guid": guid,
            "file_id": int(fm.group(1)) if fm else None,
            "path": path,
        }
        key = (item["guid"], item["file_id"], item["path"])
        if key not in seen:
            seen.add(key)
            refs.append(item)
    return refs


def split_blocks(text: str):
    matches = list(BLOCK_RE.finditer(text))
    for i, m in enumerate(matches):
        start = m.end()
        end = matches[i + 1].start() if i + 1 < len(matches) else len(text)
        yield int(m.group(1)), int(m.group(2)), text[start:end]


def parse_scene(scene: Path, guid_index: dict[str, str]):
    text = scene.read_text(encoding="utf-8", errors="replace")
    blocks = {}
    gameobjects = {}
    transforms = {}
    components_by_go: dict[int, list[int]] = {}
    all_refs = []

    for type_id, file_id, body in split_blocks(text):
        refs = external_refs(body, guid_index)
        all_refs.extend(refs)
        block = {
            "type_id": type_id,
            "file_id": file_id,
            "game_object": ref_fileid(body, "m_GameObject"),
            "refs": refs,
        }
        if type_id == 1:  # GameObject
            block["name"] = scalar(body, "m_Name") or f"GameObject_{file_id}"
            block["active"] = scalar(body, "m_IsActive") != "0"
            comp_match = re.search(r"(?ms)^\s*m_Component:\s*\n(.*?)(?=^\s*\w[^\n]*:|\Z)", body)
            comps = []
            if comp_match:
                comps = [int(x) for x in FILEID_RE.findall(comp_match.group(1))]
            block["components"] = comps
            gameobjects[file_id] = block
            components_by_go[file_id] = comps
        elif type_id in (4, 224):  # Transform / RectTransform
            block["parent_transform"] = ref_fileid(body, "m_Father")
            block["local_position"] = vec(body, "m_LocalPosition", ("x", "y", "z")) or vec(body, "m_AnchoredPosition", ("x", "y"))
            block["local_rotation"] = vec(body, "m_LocalRotation", ("x", "y", "z", "w"))
            block["local_scale"] = vec(body, "m_LocalScale", ("x", "y", "z"))
            transforms[file_id] = block
        elif type_id == 33:  # MeshFilter
            block["mesh"] = external_refs((re.search(r"(?m)^\s*m_Mesh:.*$", body) or [""])[0], guid_index)
        elif type_id == 23:  # MeshRenderer
            block["materials"] = [r for r in refs if r["path"].endswith(".mat") or r["path"] == "<unresolved>"]
        elif type_id == 1001:  # PrefabInstance
            src = re.search(r"(?m)^\s*m_SourcePrefab:\s*\{([^}]+)\}", body)
            block["source_prefab"] = external_refs(src.group(0), guid_index)[0] if src and external_refs(src.group(0), guid_index) else None
        elif type_id == 108:  # Light
            block["light_type"] = scalar(body, "m_Type")
            block["intensity"] = scalar(body, "m_Intensity")
            block["range"] = scalar(body, "m_Range")
            block["color"] = vec(body, "m_Color", ("r", "g", "b", "a"))
        blocks[file_id] = block

    transform_to_go = {tid: t.get("game_object") for tid, t in transforms.items()}
    go_to_transform = {go: tid for tid, go in transform_to_go.items() if go is not None}

    objects = []
    for go_id, go in gameobjects.items():
        tid = go_to_transform.get(go_id)
        t = transforms.get(tid, {}) if tid is not None else {}
        parent_tid = t.get("parent_transform")
        parent_go = transform_to_go.get(parent_tid) if parent_tid else None
        comps = [blocks[c] for c in components_by_go.get(go_id, []) if c in blocks]
        objects.append({
            "id": go_id,
            "name": go["name"],
            "active": go["active"],
            "transform_id": tid,
            "parent_id": parent_go,
            "local_position": t.get("local_position"),
            "local_rotation": t.get("local_rotation"),
            "local_scale": t.get("local_scale"),
            "components": comps,
        })

    unique_refs = {}
    for r in all_refs:
        unique_refs[(r["guid"], r["file_id"], r["path"])] = r
    refs = list(unique_refs.values())
    resolved_paths = [r["path"] for r in refs if not r["path"].startswith("<")]
    ext_counts = Counter(Path(p).suffix.lower() or "<dir>" for p in resolved_paths)
    winter = sorted({p for p in resolved_paths if p.startswith("Assets/WinterLodge/")})

    return {
        "scene": scene.as_posix(),
        "object_count": len(objects),
        "block_count": len(blocks),
        "objects": objects,
        "references": refs,
        "resolved_reference_count": len(resolved_paths),
        "unresolved_reference_count": sum(1 for r in refs if r["path"] == "<unresolved>"),
        "extension_counts": dict(sorted(ext_counts.items())),
        "winter_lodge_assets": winter,
    }


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("scene", type=Path)
    ap.add_argument("guid_index", type=Path)
    ap.add_argument("output", type=Path)
    ap.add_argument("--report", type=Path)
    args = ap.parse_args()
    idx = json.loads(args.guid_index.read_text(encoding="utf-8"))
    manifest = parse_scene(args.scene, idx)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(manifest, indent=2, sort_keys=False), encoding="utf-8")

    lines = [
        f"Scene: {args.scene}",
        f"Objects: {manifest['object_count']}",
        f"Serialized blocks: {manifest['block_count']}",
        f"Resolved external refs: {manifest['resolved_reference_count']}",
        f"Unresolved refs: {manifest['unresolved_reference_count']}",
        "Reference extensions: " + json.dumps(manifest['extension_counts'], sort_keys=True),
        f"WinterLodge assets used: {len(manifest['winter_lodge_assets'])}",
        "",
        "WinterLodge references:",
        *manifest["winter_lodge_assets"],
    ]
    report = "\n".join(lines) + "\n"
    print(report)
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(report, encoding="utf-8")


if __name__ == "__main__":
    main()
