#!/usr/bin/env python3
"""Build the compact room description consumed by NexaQuest Android.

Keeps source-exact transforms/prefab overrides but no Minecraft texture bytes.
Texture names remain logical references and are resolved from a legitimate game
installation by the Android side.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

ROOT_TRANSFORM_FILE_ID = -8679921383154817045
SOURCE_COMMIT = "a8d46ea0db48d31015c1794bf5159a7b9c6edb4d"


def children_of(objects):
    by_parent = {}
    for o in objects:
        by_parent.setdefault(o.get("parent_id"), []).append(o)
    return by_parent


def subtree(objects, root_name):
    roots = [o for o in objects if o.get("name") == root_name]
    if not roots:
        return []
    by_parent = children_of(objects)
    out, stack, seen = [], roots[:], set()
    while stack:
        o = stack.pop()
        if o["id"] in seen:
            continue
        seen.add(o["id"])
        out.append(compact_object(o))
        stack.extend(by_parent.get(o["id"], []))
    return out


def compact_object(o):
    return {
        "id": o.get("id"),
        "name": o.get("name"),
        "active": o.get("active", True),
        "parent_id": o.get("parent_id"),
        "position": o.get("local_position"),
        "rotation": o.get("local_rotation"),
        "scale": o.get("local_scale"),
    }


def to_number(value):
    if value is None:
        return None
    try:
        return int(value)
    except ValueError:
        try:
            return float(value)
        except ValueError:
            return value


def prefab_spec(p, root_transform_owner):
    groups = {}
    for m in p.get("modifications", []):
        tid = str(m["target"].get("file_id"))
        target = groups.setdefault(tid, {})
        prop = m.get("property")
        obj = m.get("object_reference") or {}
        if obj.get("path") or obj.get("guid"):
            target[prop] = {"ref": obj}
        else:
            target[prop] = to_number(m.get("value"))

    root = groups.get(str(ROOT_TRANSFORM_FILE_ID), {})
    pos = {axis: root.get(f"m_LocalPosition.{axis}", 0.0) for axis in "xyz"}
    rot = {axis: root.get(f"m_LocalRotation.{axis}", 0.0) for axis in "xyzw"}
    if rot == {"x": 0.0, "y": 0.0, "z": 0.0, "w": 0.0}:
        rot["w"] = 1.0
    scale = {axis: root.get(f"m_LocalScale.{axis}", 1.0) for axis in "xyz"}
    name = None
    for values in groups.values():
        if "m_Name" in values:
            name = values["m_Name"]
            break

    scene_instance_id = p["file_id"]
    scene_root_transform_id = scene_instance_id + 1
    transform_parent_id = p.get("transform_parent")
    parent_instance_id = root_transform_owner.get(transform_parent_id)

    return {
        "scene_instance_id": scene_instance_id,
        "scene_root_transform_id": scene_root_transform_id,
        "transform_parent_id": transform_parent_id,
        "parent_instance_id": parent_instance_id,
        "source": p.get("source_prefab"),
        "name": name,
        "position": pos,
        "rotation": rot,
        "scale": scale,
        "overrides_by_file_id": groups,
    }


def parse_mtl(path: Path):
    if not path.exists():
        return []
    materials, current = [], None
    for raw in path.read_text(encoding="utf-8", errors="replace").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("newmtl "):
            current = {"name": line[7:].strip()}
            materials.append(current)
        elif current is not None and line.startswith("map_Kd "):
            current["texture"] = line[7:].strip()
        elif current is not None and line.startswith("map_d "):
            current["alpha_texture"] = line[6:].strip()
        elif current is not None and line.startswith("Kd "):
            parts = line.split()
            if len(parts) >= 4:
                current["diffuse"] = [float(parts[1]), float(parts[2]), float(parts[3])]
    return materials


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("manifest", type=Path)
    ap.add_argument("qcxr_root", type=Path)
    ap.add_argument("output", type=Path)
    args = ap.parse_args()

    m = json.loads(args.manifest.read_text(encoding="utf-8"))
    raw_prefabs = []
    for p in m.get("prefab_instances", []):
        path = ((p.get("source_prefab") or {}).get("path") or "")
        if path == "Assets/QCWorld/QCWorld.obj" or path.startswith("Assets/WinterLodge/"):
            raw_prefabs.append(p)

    # Unity serializes the stripped root Transform immediately after each imported
    # PrefabInstance in this exact source scene. Mapping those IDs lets Android
    # preserve the real hierarchy (WinterLodge props -> QCWorld, Display -> CRT).
    root_transform_owner = {p["file_id"] + 1: p["file_id"] for p in raw_prefabs}
    prefabs = [prefab_spec(p, root_transform_owner) for p in raw_prefabs]

    qcworld = next((p for p in prefabs if (p["source"] or {}).get("path") == "Assets/QCWorld/QCWorld.obj"), None)
    if qcworld is None:
        raise SystemExit("QCWorld instance missing from Main.unity")

    spec = {
        "format": "nexa-questcraft-room-v2",
        "source": {
            "repository": "QuestCraftPlusPlus/QCXR-XR-Wrapper",
            "branch": "CardboardCraft",
            "commit": SOURCE_COMMIT,
            "scene": "Assets/Scenes/Main.unity",
        },
        "coordinate_system": "Unity left-handed Y-up; preserve values until renderer conversion",
        "main_menu": subtree(m["objects"], "MainMenu"),
        "xr_origin": subtree(m["objects"], "XR Origin"),
        "quester_collision": subtree(m["objects"], "Quester renderer"),
        "qcworld": qcworld,
        "props": [p for p in prefabs if p is not qcworld],
        "qcworld_materials": parse_mtl(args.qcxr_root / "Assets/QCWorld/QCWorld.mtl"),
        "asset_policy": {
            "geometry_source": "official public QuestCraft source",
            "minecraft_texture_bytes_embedded": False,
            "texture_resolution": "resolve logical texture names from the user's legitimate Minecraft installation",
        },
    }

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(spec, indent=2, sort_keys=False), encoding="utf-8")
    print("Native room spec:")
    print("  MainMenu objects:", len(spec["main_menu"]))
    print("  XR Origin objects:", len(spec["xr_origin"]))
    print("  QCWorld overrides:", len(qcworld["overrides_by_file_id"]))
    print("  props:", len(spec["props"]))
    print("  materials:", len(spec["qcworld_materials"]))
    print("  parent links:", sum(1 for p in prefabs if p["parent_instance_id"] is not None))
    print("  output:", args.output)


if __name__ == "__main__":
    main()
