#!/usr/bin/env python3
"""Extract a renderer-neutral manifest from Unity text scenes/prefabs.

Records GameObjects, hierarchy, local transforms, meshes/materials/lights and
PrefabInstance source + overrides. No third-party/Minecraft asset bytes are copied.
"""
from __future__ import annotations

import argparse
import json
import re
from collections import Counter
from pathlib import Path

BLOCK_RE = re.compile(r"(?m)^--- !u!(\d+) &(\-?\d+)(?: stripped)?\s*$")
FILEID_RE = re.compile(r"fileID:\s*(-?\d+)")
# Keep property/value matching on a single physical YAML line. Using \s* here
# could cross a newline when `value:` is empty and accidentally consume the
# objectReference of the following modification.
OVERRIDE_RE = re.compile(
    r"(?ms)^\s*- target:[ \t]*\{([^}]*)\}[ \t]*\r?\n"
    r"[ \t]*propertyPath:[ \t]*([^\r\n]*?)[ \t]*\r?\n"
    r"[ \t]*value:[ \t]*([^\r\n]*)\r?\n"
    r"[ \t]*objectReference:[ \t]*\{([^}]*)\}"
)


def scalar(text: str, key: str):
    m = re.search(rf"(?m)^\s*{re.escape(key)}:\s*(.*?)\s*$", text)
    return m.group(1).strip() if m else None


def ref_fileid(text: str, key: str):
    m = re.search(rf"(?m)^\s*{re.escape(key)}:\s*\{{([^}}]+)\}}", text)
    if not m:
        return None
    f = FILEID_RE.search(m.group(1))
    return int(f.group(1)) if f else None


def parse_ref_body(body: str, guid_index: dict[str, str]):
    gm = re.search(r"guid:\s*([0-9a-fA-F]{32})", body)
    fm = FILEID_RE.search(body)
    guid = gm.group(1).lower() if gm else None
    if guid == "0000000000000000e000000000000000":
        path = "<unity-built-in>"
    elif guid == "0000000000000000f000000000000000":
        path = "<unity-built-in-resource>"
    elif guid:
        path = guid_index.get(guid, "<unresolved>")
    else:
        path = None
    return {
        "guid": guid,
        "file_id": int(fm.group(1)) if fm else None,
        "path": path,
    }


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
    refs, seen = [], set()
    for m in re.finditer(r"\{([^{}]*?guid:\s*[0-9a-fA-F]{32}[^{}]*?)\}", text):
        item = parse_ref_body(m.group(1), guid_index)
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


def parse_prefab_instance(body: str, guid_index: dict[str, str]):
    srcm = re.search(r"(?m)^\s*m_SourcePrefab:\s*\{([^}]+)\}", body)
    source = parse_ref_body(srcm.group(1), guid_index) if srcm else None
    parent = ref_fileid(body, "m_TransformParent")
    mods = []
    for m in OVERRIDE_RE.finditer(body):
        target = parse_ref_body(m.group(1), guid_index)
        objref = parse_ref_body(m.group(4), guid_index)
        mods.append({
            "target": target,
            "property": m.group(2).strip(),
            "value": m.group(3).strip(),
            "object_reference": objref,
        })
    removed = []
    rm = re.search(r"(?ms)^\s*m_RemovedComponents:\s*(.*?)(?=^\s*m_SourcePrefab:|\Z)", body)
    if rm:
        removed = external_refs(rm.group(1), guid_index)
    return source, parent, mods, removed


def parse_scene(scene: Path, guid_index: dict[str, str]):
    text = scene.read_text(encoding="utf-8", errors="replace")
    blocks, gameobjects, transforms = {}, {}, {}
    components_by_go: dict[int, list[int]] = {}
    all_refs, prefab_instances = [], []

    for type_id, file_id, body in split_blocks(text):
        refs = external_refs(body, guid_index)
        all_refs.extend(refs)
        block = {
            "type_id": type_id,
            "file_id": file_id,
            "game_object": ref_fileid(body, "m_GameObject"),
            "refs": refs,
        }
        if type_id == 1:
            block["name"] = scalar(body, "m_Name") or f"GameObject_{file_id}"
            block["active"] = scalar(body, "m_IsActive") != "0"
            cm = re.search(r"(?ms)^\s*m_Component:\s*\n(.*?)(?=^\s*\w[^\n]*:|\Z)", body)
            comps = [int(x) for x in FILEID_RE.findall(cm.group(1))] if cm else []
            block["components"] = comps
            gameobjects[file_id] = block
            components_by_go[file_id] = comps
        elif type_id in (4, 224):
            block["parent_transform"] = ref_fileid(body, "m_Father")
            block["local_position"] = vec(body, "m_LocalPosition", ("x", "y", "z")) or vec(body, "m_AnchoredPosition", ("x", "y"))
            block["local_rotation"] = vec(body, "m_LocalRotation", ("x", "y", "z", "w"))
            block["local_scale"] = vec(body, "m_LocalScale", ("x", "y", "z"))
            transforms[file_id] = block
        elif type_id == 33:
            mm = re.search(r"(?m)^\s*m_Mesh:.*$", body)
            block["mesh"] = external_refs(mm.group(0), guid_index) if mm else []
        elif type_id == 23:
            block["materials"] = [r for r in refs if (r["path"] or "").endswith(".mat") or r["path"] in ("<unresolved>", "<unity-built-in-resource>")]
        elif type_id == 1001:
            source, parent, mods, removed = parse_prefab_instance(body, guid_index)
            block["source_prefab"] = source
            block["transform_parent"] = parent
            block["modifications"] = mods
            block["removed_components"] = removed
            prefab_instances.append(block)
        elif type_id == 108:
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
        objects.append({
            "id": go_id,
            "name": go["name"],
            "active": go["active"],
            "transform_id": tid,
            "parent_id": parent_go,
            "local_position": t.get("local_position"),
            "local_rotation": t.get("local_rotation"),
            "local_scale": t.get("local_scale"),
            "components": [blocks[c] for c in components_by_go.get(go_id, []) if c in blocks],
        })

    unique_refs = {(r["guid"], r["file_id"], r["path"]): r for r in all_refs}
    refs = list(unique_refs.values())
    resolved_paths = [r["path"] for r in refs if r["path"] and not r["path"].startswith("<")]
    ext_counts = Counter(Path(p).suffix.lower() or "<dir>" for p in resolved_paths)
    winter = sorted({p for p in resolved_paths if p.startswith("Assets/WinterLodge/")})
    winter_instances = [p for p in prefab_instances if p.get("source_prefab") and (p["source_prefab"].get("path") or "").startswith("Assets/WinterLodge/")]

    return {
        "scene": scene.as_posix(),
        "object_count": len(objects),
        "block_count": len(blocks),
        "prefab_instance_count": len(prefab_instances),
        "objects": objects,
        "prefab_instances": prefab_instances,
        "winter_lodge_prefab_instances": winter_instances,
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
        f"Prefab instances: {manifest['prefab_instance_count']}",
        f"WinterLodge prefab instances: {len(manifest['winter_lodge_prefab_instances'])}",
        f"Resolved external refs: {manifest['resolved_reference_count']}",
        f"Unresolved refs: {manifest['unresolved_reference_count']}",
        "Reference extensions: " + json.dumps(manifest['extension_counts'], sort_keys=True),
        f"WinterLodge assets used: {len(manifest['winter_lodge_assets'])}",
        "",
        "WinterLodge instantiated sources:",
        *[f"{p['file_id']}: {p['source_prefab']['path']} ({len(p['modifications'])} overrides)" for p in manifest['winter_lodge_prefab_instances']],
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
