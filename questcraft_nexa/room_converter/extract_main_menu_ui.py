#!/usr/bin/env python3
"""Extract QuestCraft MainMenu Canvas data from a Unity YAML scene.

This does not render or reinterpret the UI. It serializes the original
RectTransforms, colors, sprites/textures, TMP text settings and button states
so the native Android renderer can reproduce the same 1200x600 world-space
Canvas on the CRT without running Unity.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from unity_scene_manifest import split_blocks, scalar, ref_fileid, vec, parse_ref_body, FILEID_RE


def inline_ref(body: str, key: str, guid_index: dict[str, str]):
    m = re.search(rf"(?m)^\s*{re.escape(key)}:\s*\{{([^}}]+)\}}", body)
    return parse_ref_body(m.group(1), guid_index) if m else None


def num(value):
    if value is None or value == "":
        return None
    try:
        return int(value)
    except (TypeError, ValueError):
        try:
            return float(value)
        except (TypeError, ValueError):
            return value


def parse_text_value(body: str):
    # Most launcher labels are single-line plain scalars (Play, Instances, etc.).
    # Keep quoted/plain scalars exactly; folded multi-line TMP text is left as the
    # YAML scalar so no text is invented by the converter.
    value = scalar(body, "m_text")
    if value is None:
        return None
    if len(value) >= 2 and value[0] == value[-1] and value[0] in ("'", '"'):
        return value[1:-1]
    return value


def parse_component(type_id: int, file_id: int, body: str, guid_index: dict[str, str]):
    out = {"type_id": type_id, "file_id": file_id}
    if type_id == 223:  # Canvas
        out.update({
            "kind": "canvas",
            "render_mode": num(scalar(body, "m_RenderMode")),
            "plane_distance": num(scalar(body, "m_PlaneDistance")),
            "sorting_order": num(scalar(body, "m_SortingOrder")),
        })
        return out
    if type_id != 114:
        return out

    script = inline_ref(body, "m_Script", guid_index)
    sprite = inline_ref(body, "m_Sprite", guid_index)
    texture = inline_ref(body, "m_Texture", guid_index)
    font = inline_ref(body, "m_fontAsset", guid_index)
    color = vec(body, "m_Color", ("r", "g", "b", "a"))
    font_color = vec(body, "m_fontColor", ("r", "g", "b", "a"))
    reference_resolution = vec(body, "m_ReferenceResolution", ("x", "y"))
    text = parse_text_value(body)

    if reference_resolution is not None:
        kind = "canvas_scaler"
    elif text is not None or font is not None:
        kind = "tmp_text"
    elif texture is not None:
        kind = "raw_image"
    elif sprite is not None or "m_FillCenter:" in body:
        kind = "image"
    elif "m_OnClick:" in body:
        kind = "button"
    else:
        kind = "behaviour"

    out.update({
        "kind": kind,
        "script": script,
        "enabled": scalar(body, "m_Enabled") != "0",
    })
    if reference_resolution is not None:
        out.update({
            "ui_scale_mode": num(scalar(body, "m_UiScaleMode")),
            "reference_resolution": reference_resolution,
            "scale_factor": num(scalar(body, "m_ScaleFactor")),
            "reference_pixels_per_unit": num(scalar(body, "m_ReferencePixelsPerUnit")),
        })
    if color is not None:
        out["color"] = color
    if sprite is not None:
        out["sprite"] = sprite
        out["image_type"] = num(scalar(body, "m_Type"))
        out["pixels_per_unit_multiplier"] = num(scalar(body, "m_PixelsPerUnitMultiplier"))
        out["preserve_aspect"] = scalar(body, "m_PreserveAspect") == "1"
    if texture is not None:
        out["texture"] = texture
        out["uv_rect"] = vec(body, "m_UVRect", ("x", "y", "width", "height"))
    if text is not None:
        out.update({
            "text": text,
            "font": font,
            "font_size": num(scalar(body, "m_fontSize")),
            "font_size_base": num(scalar(body, "m_fontSizeBase")),
            "font_weight": num(scalar(body, "m_fontWeight")),
            "font_color": font_color,
            "horizontal_alignment": num(scalar(body, "m_HorizontalAlignment")),
            "vertical_alignment": num(scalar(body, "m_VerticalAlignment")),
            "auto_sizing": scalar(body, "m_enableAutoSizing") == "1",
            "font_size_min": num(scalar(body, "m_fontSizeMin")),
            "font_size_max": num(scalar(body, "m_fontSizeMax")),
        })
    if "m_OnClick:" in body:
        out["interactable"] = scalar(body, "m_Interactable") != "0"
        out["normal_color"] = vec(body, "m_NormalColor", ("r", "g", "b", "a"))
        out["highlighted_color"] = vec(body, "m_HighlightedColor", ("r", "g", "b", "a"))
        out["pressed_color"] = vec(body, "m_PressedColor", ("r", "g", "b", "a"))
        out["disabled_color"] = vec(body, "m_DisabledColor", ("r", "g", "b", "a"))
        methods = re.findall(r"(?m)^\s*m_MethodName:\s*(.*?)\s*$", body)
        if methods:
            out["on_click_methods"] = methods
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("scene", type=Path)
    ap.add_argument("guid_index", type=Path)
    ap.add_argument("output", type=Path)
    args = ap.parse_args()

    guid_index = json.loads(args.guid_index.read_text(encoding="utf-8"))
    text = args.scene.read_text(encoding="utf-8", errors="replace")

    blocks = {}
    gameobjects = {}
    transforms = {}
    components_by_go = {}

    for type_id, file_id, body in split_blocks(text):
        go = ref_fileid(body, "m_GameObject")
        blocks[file_id] = (type_id, body)
        if type_id == 1:
            cm = re.search(r"(?ms)^\s*m_Component:\s*\n(.*?)(?=^\s*\w[^\n]*:|\Z)", body)
            comps = [int(x) for x in FILEID_RE.findall(cm.group(1))] if cm else []
            gameobjects[file_id] = {
                "id": file_id,
                "name": scalar(body, "m_Name") or f"GameObject_{file_id}",
                "active": scalar(body, "m_IsActive") != "0",
            }
            components_by_go[file_id] = comps
        elif type_id in (4, 224):
            transforms[file_id] = {
                "type_id": type_id,
                "game_object": go,
                "parent_transform": ref_fileid(body, "m_Father"),
                "local_position": vec(body, "m_LocalPosition", ("x", "y", "z")),
                "local_rotation": vec(body, "m_LocalRotation", ("x", "y", "z", "w")),
                "local_scale": vec(body, "m_LocalScale", ("x", "y", "z")),
                "anchor_min": vec(body, "m_AnchorMin", ("x", "y")),
                "anchor_max": vec(body, "m_AnchorMax", ("x", "y")),
                "anchored_position": vec(body, "m_AnchoredPosition", ("x", "y")),
                "size_delta": vec(body, "m_SizeDelta", ("x", "y")),
                "pivot": vec(body, "m_Pivot", ("x", "y")),
                "root_order": num(scalar(body, "m_RootOrder")),
            }

    transform_to_go = {tid: t["game_object"] for tid, t in transforms.items() if t.get("game_object") is not None}
    go_to_transform = {go: tid for tid, go in transform_to_go.items()}
    parent_by_go = {}
    for go_id, tid in go_to_transform.items():
        parent_tid = transforms[tid].get("parent_transform")
        parent_by_go[go_id] = transform_to_go.get(parent_tid)

    roots = [go_id for go_id, go in gameobjects.items() if go["name"] == "MainMenu"]
    if len(roots) != 1:
        raise SystemExit(f"Expected exactly one MainMenu root, found {len(roots)}")
    root = roots[0]

    children = {}
    for go_id, parent in parent_by_go.items():
        children.setdefault(parent, []).append(go_id)

    selected = []
    stack = [root]
    seen = set()
    while stack:
        go_id = stack.pop()
        if go_id in seen:
            continue
        seen.add(go_id)
        selected.append(go_id)
        kids = children.get(go_id, [])
        kids.sort(key=lambda x: transforms.get(go_to_transform.get(x), {}).get("root_order") or 0, reverse=True)
        stack.extend(kids)

    objects = []
    asset_refs = {}
    for go_id in selected:
        go = gameobjects[go_id]
        tid = go_to_transform.get(go_id)
        t = transforms.get(tid, {})
        components = []
        for cid in components_by_go.get(go_id, []):
            if cid not in blocks:
                continue
            type_id, body = blocks[cid]
            comp = parse_component(type_id, cid, body, guid_index)
            components.append(comp)
            for key in ("sprite", "texture", "font"):
                ref = comp.get(key)
                if ref and ref.get("path") and not ref["path"].startswith("<"):
                    asset_refs[ref["path"]] = ref

        objects.append({
            "id": go_id,
            "name": go["name"],
            "active": go["active"],
            "parent_id": parent_by_go.get(go_id),
            "transform_id": tid,
            "transform_type": t.get("type_id"),
            "local_position": t.get("local_position"),
            "local_rotation": t.get("local_rotation"),
            "local_scale": t.get("local_scale"),
            "anchor_min": t.get("anchor_min"),
            "anchor_max": t.get("anchor_max"),
            "anchored_position": t.get("anchored_position"),
            "size_delta": t.get("size_delta"),
            "pivot": t.get("pivot"),
            "root_order": t.get("root_order"),
            "components": components,
        })

    canvas = next((o for o in objects if o["name"] == "MainCanvas"), None)
    if canvas is None:
        raise SystemExit("MainCanvas missing from MainMenu subtree")

    out = {
        "format": "nexa-questcraft-mainmenu-ui-v1",
        "source_scene": "Assets/Scenes/Main.unity",
        "root_id": root,
        "object_count": len(objects),
        "canvas_object_id": canvas["id"],
        "canvas_size": canvas.get("size_delta"),
        "objects": objects,
        "asset_refs": list(asset_refs.values()),
    }

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(out, indent=2, sort_keys=False), encoding="utf-8")
    names = {o["name"] for o in objects}
    required = {"MainCanvas", "MainPanel", "Bottom Bar", "Play Button", "InstanceManagerButton", "ModSearchButton"}
    missing = sorted(required - names)
    if missing:
        raise SystemExit("Missing expected original UI objects: " + ", ".join(missing))
    print("QuestCraft MainMenu UI objects:", len(objects))
    print("Canvas size:", out["canvas_size"])
    print("Referenced UI assets:", len(asset_refs))


if __name__ == "__main__":
    main()
