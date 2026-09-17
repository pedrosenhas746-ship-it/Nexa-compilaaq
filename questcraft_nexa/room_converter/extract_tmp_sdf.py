#!/usr/bin/env python3
"""Extract a TextMesh Pro SDF font asset into Android-friendly data.

Unity serializes the SDF atlas as an Alpha8 Texture2D inside the .asset and
stores glyph/character tables in the same YAML text.  This converter keeps the
original atlas pixels and exact TMP metrics; no replacement system font is used.
"""
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

try:
    from PIL import Image
except ImportError as exc:
    raise SystemExit("Pillow is required (python3 -m pip install pillow)") from exc


def scalar(text: str, key: str, default=None):
    m = re.search(rf"(?m)^\s*{re.escape(key)}:\s*(.*?)\s*$", text)
    return m.group(1) if m else default


def number(value, default=0.0):
    if value is None:
        return default
    try:
        return int(value)
    except (TypeError, ValueError):
        try:
            return float(value)
        except (TypeError, ValueError):
            return default


def section(text: str, begin: str, end: str | None) -> str:
    start = text.find(begin)
    if start < 0:
        raise ValueError(f"Missing section {begin!r}")
    start += len(begin)
    if end is None:
        return text[start:]
    stop = text.find(end, start)
    if stop < 0:
        raise ValueError(f"Missing section {end!r}")
    return text[start:stop]


def parse_glyphs(text: str):
    body = section(text, "m_GlyphTable:", "m_CharacterTable:")
    chunks = re.split(r"(?m)^\s{2}- m_Index:\s*", body)[1:]
    out = {}
    for chunk in chunks:
        first, _, rest = chunk.partition("\n")
        idx = int(first.strip())
        def grab(name, default=0.0):
            m = re.search(rf"(?m)^\s+{re.escape(name)}:\s*([-+0-9.eE]+)\s*$", rest)
            return number(m.group(1), default) if m else default

        rect_match = re.search(
            r"m_GlyphRect:\s*\n"
            r"\s*m_X:\s*([-+0-9.eE]+)\s*\n"
            r"\s*m_Y:\s*([-+0-9.eE]+)\s*\n"
            r"\s*m_Width:\s*([-+0-9.eE]+)\s*\n"
            r"\s*m_Height:\s*([-+0-9.eE]+)",
            rest,
        )
        metrics_match = re.search(
            r"m_Metrics:\s*\n"
            r"\s*m_Width:\s*([-+0-9.eE]+)\s*\n"
            r"\s*m_Height:\s*([-+0-9.eE]+)\s*\n"
            r"\s*m_HorizontalBearingX:\s*([-+0-9.eE]+)\s*\n"
            r"\s*m_HorizontalBearingY:\s*([-+0-9.eE]+)\s*\n"
            r"\s*m_HorizontalAdvance:\s*([-+0-9.eE]+)",
            rest,
        )
        if not rect_match or not metrics_match:
            continue
        mx = [number(x) for x in metrics_match.groups()]
        rx = [number(x) for x in rect_match.groups()]
        out[idx] = {
            "index": idx,
            "metrics": {
                "width": mx[0],
                "height": mx[1],
                "bearing_x": mx[2],
                "bearing_y": mx[3],
                "advance": mx[4],
            },
            "rect_unity": {
                "x": rx[0], "y": rx[1], "width": rx[2], "height": rx[3]
            },
            "scale": grab("m_Scale", 1.0),
            "atlas_index": int(grab("m_AtlasIndex", 0)),
        }
    return out


def parse_characters(text: str):
    body = section(text, "m_CharacterTable:", "m_AtlasTextures:")
    chunks = re.split(r"(?m)^\s{2}- m_ElementType:\s*", body)[1:]
    chars = []
    for chunk in chunks:
        um = re.search(r"(?m)^\s*m_Unicode:\s*(\d+)\s*$", chunk)
        gm = re.search(r"(?m)^\s*m_GlyphIndex:\s*(\d+)\s*$", chunk)
        sm = re.search(r"(?m)^\s*m_Scale:\s*([-+0-9.eE]+)\s*$", chunk)
        if not um or not gm:
            continue
        chars.append({
            "unicode": int(um.group(1)),
            "glyph_index": int(gm.group(1)),
            "scale": number(sm.group(1), 1.0) if sm else 1.0,
        })
    return chars


def parse_texture(text: str):
    # The custom QC fonts embed exactly one Alpha8 atlas.  Search the Texture2D
    # block itself instead of assuming whether it appears before/after the TMP object.
    candidates = list(re.finditer(r"(?m)^Texture2D:\s*$", text))
    if not candidates:
        raise ValueError("No embedded Texture2D atlas")
    for candidate in candidates:
        block = text[candidate.start():]
        wm = re.search(r"(?m)^\s*m_Width:\s*(\d+)\s*$", block)
        hm = re.search(r"(?m)^\s*m_Height:\s*(\d+)\s*$", block)
        fm = re.search(r"(?m)^\s*m_TextureFormat:\s*(\d+)\s*$", block)
        nm = re.search(r"(?m)^\s*image data:\s*(\d+)\s*$", block)
        dm = re.search(r"(?m)^\s*_typelessdata:\s*([0-9a-fA-F]+)\s*$", block)
        if not (wm and hm and fm and nm and dm):
            continue
        width, height = int(wm.group(1)), int(hm.group(1))
        fmt, size = int(fm.group(1)), int(nm.group(1))
        raw = bytes.fromhex(dm.group(1))
        if len(raw) < size:
            raise ValueError(f"Atlas data truncated: {len(raw)} < {size}")
        raw = raw[:size]
        if fmt != 1:
            raise ValueError(f"Unsupported TMP atlas TextureFormat {fmt}; expected Alpha8 (1)")
        if size != width * height:
            raise ValueError(f"Unexpected Alpha8 size: {size} != {width}x{height}")
        return width, height, fmt, raw
    raise ValueError("Could not parse embedded Texture2D")


def parse_face(text: str):
    body = section(text, "m_FaceInfo:", "m_GlyphTable:")
    names = {
        "family": "m_FamilyName",
        "style": "m_StyleName",
        "point_size": "m_PointSize",
        "scale": "m_Scale",
        "units_per_em": "m_UnitsPerEM",
        "line_height": "m_LineHeight",
        "ascent": "m_AscentLine",
        "cap": "m_CapLine",
        "mean": "m_MeanLine",
        "baseline": "m_Baseline",
        "descent": "m_DescentLine",
        "tab_width": "m_TabWidth",
    }
    out = {}
    for target, source in names.items():
        value = scalar(body, source)
        if target in ("family", "style"):
            out[target] = value or ""
        else:
            out[target] = number(value)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("source", type=Path)
    ap.add_argument("output_dir", type=Path)
    ap.add_argument("--name")
    args = ap.parse_args()

    text = args.source.read_text(encoding="utf-8", errors="strict")
    width, height, texture_format, raw = parse_texture(text)
    glyphs = parse_glyphs(text)
    characters = parse_characters(text)
    face = parse_face(text)

    base = args.name or re.sub(r"[^A-Za-z0-9._-]+", "_", args.source.stem).strip("_")
    args.output_dir.mkdir(parents=True, exist_ok=True)
    atlas_path = args.output_dir / f"{base}.png"
    json_path = args.output_dir / f"{base}.json"

    # Unity's raw texture rows use the opposite vertical orientation to PNG / Android Canvas.
    image = Image.frombytes("L", (width, height), raw)
    image = image.transpose(Image.Transpose.FLIP_TOP_BOTTOM)
    image.save(atlas_path, optimize=True)

    for glyph in glyphs.values():
        r = glyph["rect_unity"]
        glyph["rect"] = {
            "x": r["x"],
            "y": height - (r["y"] + r["height"]),
            "width": r["width"],
            "height": r["height"],
        }

    char_map = {str(c["unicode"]): c for c in characters}
    data = {
        "format": "nexa-tmp-sdf-v1",
        "source_asset": args.source.name,
        "source_font_guid": scalar(text, "m_SourceFontFileGUID"),
        "face": face,
        "atlas": {
            "file": atlas_path.name,
            "width": width,
            "height": height,
            "texture_format": texture_format,
            "padding": number(scalar(text, "m_AtlasPadding")),
            "render_mode": number(scalar(text, "m_AtlasRenderMode")),
        },
        "material": {
            "gradient_scale": number(scalar(text, "_GradientScale"), 1.0),
            "weight_normal": number(scalar(text, "_WeightNormal"), 0.0),
            "weight_bold": number(scalar(text, "_WeightBold"), 0.75),
            "face_dilate": number(scalar(text, "_FaceDilate"), 0.0),
            "outline_width": number(scalar(text, "_OutlineWidth"), 0.0),
            "outline_softness": number(scalar(text, "_OutlineSoftness"), 0.0),
        },
        "glyphs": {str(k): v for k, v in glyphs.items()},
        "characters": char_map,
    }
    json_path.write_text(json.dumps(data, indent=2, ensure_ascii=False), encoding="utf-8")

    # Hard checks on the printable ASCII used by the launcher.
    for cp in range(32, 127):
        if str(cp) not in char_map:
            raise SystemExit(f"Missing ASCII U+{cp:04X} in {args.source.name}")
        idx = char_map[str(cp)]["glyph_index"]
        if idx not in glyphs:
            raise SystemExit(f"Missing glyph {idx} for U+{cp:04X}")

    print(f"TMP SDF extracted: {face.get('family')} {face.get('style')}")
    print(f"  atlas: {width}x{height} Alpha8 -> {atlas_path}")
    print(f"  glyphs: {len(glyphs)}, characters: {len(characters)} -> {json_path}")


if __name__ == "__main__":
    main()
