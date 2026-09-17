#!/usr/bin/env python3
"""Render QuestCraft's original world-space MainMenu into native CRT textures.

The source is the serialized Main.unity RectTransform tree plus the original
public QCXR sprites and TextMesh Pro SDF atlases.  No visual re-design is done.
This produces cached 1200x600 textures and button hit boxes for the Android
Filament renderer, while keeping the source scene as the single source of truth.
"""
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

from PIL import Image, ImageDraw


def rgba(c, default=(255, 255, 255, 255)):
    if not c:
        return default
    return tuple(max(0, min(255, round(float(c.get(k, 1.0)) * 255))) for k in ("r", "g", "b", "a"))


def v2(obj, key, dx=0.0, dy=0.0):
    v = obj.get(key) or {}
    return float(v.get("x", dx)), float(v.get("y", dy))


class TmpFont:
    def __init__(self, json_path: Path):
        self.data = json.loads(json_path.read_text(encoding="utf-8"))
        self.atlas = Image.open(json_path.with_name(self.data["atlas"]["file"])).convert("L")
        self.face = self.data["face"]
        self.characters = self.data["characters"]
        self.glyphs = self.data["glyphs"]

    def glyph(self, ch: str):
        item = self.characters.get(str(ord(ch)))
        if not item:
            return None
        return self.glyphs.get(str(item["glyph_index"]))


class MenuRenderer:
    def __init__(self, menu_json: Path, qcxr_root: Path, fonts_dir: Path):
        self.menu = json.loads(menu_json.read_text(encoding="utf-8"))
        self.qcxr_root = qcxr_root
        self.objects = self.menu["objects"]
        self.by_id = {o["id"]: o for o in self.objects}
        self.children = {}
        for o in self.objects:
            self.children.setdefault(o.get("parent_id"), []).append(o)
        for values in self.children.values():
            values.sort(key=lambda x: x.get("root_order") or 0)

        self.canvas = self.by_id[self.menu["canvas_object_id"]]
        size = self.menu.get("canvas_size") or {"x": 1200, "y": 600}
        self.width = int(round(size.get("x", 1200)))
        self.height = int(round(size.get("y", 600)))
        self.rects = {self.canvas["id"]: (-self.width / 2, -self.height / 2, self.width / 2, self.height / 2)}
        self.sprite_cache = {}
        self.meta_cache = {}

        self.fonts = {
            "sunflower": TmpFont(fonts_dir / "sunflower-bold.json"),
            "perfect": TmpFont(fonts_dir / "perfect-dos.json"),
            "liberation": TmpFont(fonts_dir / "liberation-sans.json"),
        }

    def rect(self, obj):
        oid = obj["id"]
        if oid in self.rects:
            return self.rects[oid]
        parent = self.by_id.get(obj.get("parent_id"))
        pr = self.rect(parent) if parent else (-self.width / 2, -self.height / 2, self.width / 2, self.height / 2)
        pw, ph = pr[2] - pr[0], pr[3] - pr[1]
        amin = v2(obj, "anchor_min", 0.5, 0.5)
        amax = v2(obj, "anchor_max", 0.5, 0.5)
        pivot = v2(obj, "pivot", 0.5, 0.5)
        ap = v2(obj, "anchored_position", 0.0, 0.0)
        sd = v2(obj, "size_delta", 0.0, 0.0)

        anchor_x = pr[0] + pw * (amin[0] + (amax[0] - amin[0]) * pivot[0])
        anchor_y = pr[1] + ph * (amin[1] + (amax[1] - amin[1]) * pivot[1])
        w = pw * (amax[0] - amin[0]) + sd[0]
        h = ph * (amax[1] - amin[1]) + sd[1]
        cx, cy = anchor_x + ap[0], anchor_y + ap[1]
        r = (
            cx - w * pivot[0],
            cy - h * pivot[1],
            cx + w * (1.0 - pivot[0]),
            cy + h * (1.0 - pivot[1]),
        )
        self.rects[oid] = r
        return r

    def pixel_rect(self, r):
        return (
            int(round(r[0] + self.width / 2)),
            int(round(self.height / 2 - r[3])),
            int(round(r[2] + self.width / 2)),
            int(round(self.height / 2 - r[1])),
        )

    def source_path(self, ref):
        p = (ref or {}).get("path")
        if not p or p.startswith("<"):
            return None
        return self.qcxr_root / p

    def sprite(self, ref):
        path = self.source_path(ref)
        if not path or not path.is_file():
            return None
        key = str(path)
        if key not in self.sprite_cache:
            self.sprite_cache[key] = Image.open(path).convert("RGBA")
        return self.sprite_cache[key]

    def border(self, ref, multiplier=1.0):
        path = self.source_path(ref)
        if not path:
            return (0, 0, 0, 0)
        meta = path.with_name(path.name + ".meta")
        key = str(meta)
        if key not in self.meta_cache:
            if not meta.is_file():
                self.meta_cache[key] = (0, 0, 0, 0)
            else:
                text = meta.read_text(encoding="utf-8", errors="replace")
                m = re.search(
                    r"spriteBorder:\s*\{x:\s*([\d.]+),\s*y:\s*([\d.]+),\s*z:\s*([\d.]+),\s*w:\s*([\d.]+)\}",
                    text,
                )
                self.meta_cache[key] = tuple(float(x) for x in m.groups()) if m else (0, 0, 0, 0)
        mult = max(0.0001, float(multiplier or 1.0))
        return tuple(max(0, int(round(x / mult))) for x in self.meta_cache[key])

    @staticmethod
    def tint(image, col):
        image = image.convert("RGBA")
        if col == (255, 255, 255, 255):
            return image
        r, g, b, a = image.split()
        mr, mg, mb, ma = col
        r = r.point(lambda x: x * mr // 255)
        g = g.point(lambda x: x * mg // 255)
        b = b.point(lambda x: x * mb // 255)
        a = a.point(lambda x: x * ma // 255)
        return Image.merge("RGBA", (r, g, b, a))

    @staticmethod
    def nine_slice(src, width, height, border):
        if width <= 0 or height <= 0:
            return None
        l, b, r, t = border
        if not any(border):
            return src.resize((width, height), Image.Resampling.LANCZOS)

        # Unity keeps borders but scales them down proportionally if the target is
        # smaller than the combined fixed borders.
        sx = min(1.0, width / max(1.0, l + r))
        sy = min(1.0, height / max(1.0, t + b))
        dl, dr = int(round(l * sx)), int(round(r * sx))
        dt, db = int(round(t * sy)), int(round(b * sy))

        sw, sh = src.size
        l = min(l, sw)
        r = min(r, max(0, sw - l))
        t = min(t, sh)
        b = min(b, max(0, sh - t))
        xs = [0, int(l), int(sw - r), sw]
        ys = [0, int(t), int(sh - b), sh]
        dx = [0, dl, width - dr, width]
        dy = [0, dt, height - db, height]

        out = Image.new("RGBA", (width, height), (0, 0, 0, 0))
        for yy in range(3):
            for xx in range(3):
                src_box = (xs[xx], ys[yy], xs[xx + 1], ys[yy + 1])
                dst_box = (dx[xx], dy[yy], dx[xx + 1], dy[yy + 1])
                if src_box[2] <= src_box[0] or src_box[3] <= src_box[1]:
                    continue
                if dst_box[2] <= dst_box[0] or dst_box[3] <= dst_box[1]:
                    continue
                patch = src.crop(src_box).resize(
                    (dst_box[2] - dst_box[0], dst_box[3] - dst_box[1]),
                    Image.Resampling.LANCZOS,
                )
                out.alpha_composite(patch, (dst_box[0], dst_box[1]))
        return out

    def font_for(self, component):
        p = ((component.get("font") or {}).get("path") or "")
        if "Perfect DOS" in p:
            return self.fonts["perfect"]
        if "LiberationSans" in p:
            return self.fonts["liberation"]
        return self.fonts["sunflower"]

    def draw_tmp_text(self, target, obj, component, text_override=None):
        text = component.get("text") or ""
        if text_override is not None:
            text = text_override
        # Old extractor versions could accidentally capture this neighboring
        # serialized flag; never invent visible text from it.
        if text.startswith("m_isRightToLeft:"):
            return
        if text.startswith("'") and not text.endswith("'"):
            text = text[1:]

        font = self.font_for(component)
        point_size = float(font.face.get("point_size") or 1.0)
        font_size = float(component.get("font_size") or component.get("font_size_base") or 24.0)
        scale = font_size / point_size
        line_height = float(font.face.get("line_height") or point_size) * scale
        ascent = float(font.face.get("ascent") or point_size) * scale

        pr = self.pixel_rect(self.rect(obj))
        rw, rh = max(0, pr[2] - pr[0]), max(0, pr[3] - pr[1])
        if rw <= 0 or rh <= 0:
            return

        lines = text.split("\n")
        total_height = line_height * len(lines)
        valign = int(component.get("vertical_alignment") or 512)
        if valign & 256:
            top = pr[1]
        elif valign & 1024:
            top = pr[3] - total_height
        else:
            top = pr[1] + (rh - total_height) / 2.0

        col = rgba(component.get("font_color") or component.get("color"))
        for line_index, line in enumerate(lines):
            entries = []
            text_width = 0.0
            for ch in line:
                glyph = font.glyph(ch)
                advance = (
                    float(glyph["metrics"]["advance"]) * scale
                    if glyph else point_size * 0.4 * scale
                )
                entries.append((glyph, advance))
                text_width += advance

            halign = int(component.get("horizontal_alignment") or 1)
            if halign & 2:
                pen = pr[0] + (rw - text_width) / 2.0
            elif halign & 4:
                pen = pr[2] - text_width
            else:
                pen = pr[0]

            baseline = top + line_index * line_height + ascent
            for glyph, advance in entries:
                if glyph:
                    gr = glyph["rect"]
                    gw, gh = int(gr["width"]), int(gr["height"])
                    if gw > 0 and gh > 0:
                        mask = font.atlas.crop(
                            (int(gr["x"]), int(gr["y"]), int(gr["x"]) + gw, int(gr["y"]) + gh)
                        )
                        dw = max(1, int(round(gw * scale)))
                        dh = max(1, int(round(gh * scale)))
                        mask = mask.resize((dw, dh), Image.Resampling.LANCZOS)
                        rr, gg, bb, aa = col
                        if aa != 255:
                            mask = mask.point(lambda v: v * aa // 255)
                        tile = Image.new("RGBA", (dw, dh), (rr, gg, bb, 0))
                        tile.putalpha(mask)
                        metrics = glyph["metrics"]
                        gx = int(round(pen + float(metrics["bearing_x"]) * scale))
                        gy = int(round(baseline - float(metrics["bearing_y"]) * scale))
                        target.alpha_composite(tile, (gx, gy))
                pen += advance

    def text_override(self, obj, component, state):
        name = obj.get("name")
        # Runtime values that are not source styling. Keeping them explicit makes
        # the cached screen useful without replacing any visual asset.
        if state.get("username") and name == "Username":
            return state["username"]
        parent = self.by_id.get(obj.get("parent_id"))
        grand = self.by_id.get(parent.get("parent_id")) if parent else None
        if (
            state.get("minecraft_version")
            and name == "Label"
            and parent and parent.get("name") == "InstanceList"
            and grand and grand.get("name") == "Instances"
        ):
            return state["minecraft_version"]
        return None

    def draw_component(self, target, obj, component, state):
        kind = component.get("kind")
        pr = self.pixel_rect(self.rect(obj))
        w, h = max(0, pr[2] - pr[0]), max(0, pr[3] - pr[1])
        if w <= 0 or h <= 0 or component.get("enabled") is False:
            return

        if kind == "image":
            ref = component.get("sprite")
            src = self.sprite(ref)
            col = rgba(component.get("color"))
            if src is None:
                ImageDraw.Draw(target, "RGBA").rectangle(pr, fill=col)
                return
            if int(component.get("image_type") or 0) == 1:
                out = self.nine_slice(
                    src, w, h,
                    self.border(ref, component.get("pixels_per_unit_multiplier") or 1.0),
                )
            elif component.get("preserve_aspect"):
                out = Image.new("RGBA", (w, h), (0, 0, 0, 0))
                tmp = src.copy()
                tmp.thumbnail((w, h), Image.Resampling.LANCZOS)
                out.alpha_composite(tmp, ((w - tmp.width) // 2, (h - tmp.height) // 2))
            else:
                out = src.resize((w, h), Image.Resampling.LANCZOS)
            target.alpha_composite(self.tint(out, col), (pr[0], pr[1]))

        elif kind == "raw_image":
            src = self.sprite(component.get("texture"))
            if src:
                out = src.resize((w, h), Image.Resampling.LANCZOS)
                target.alpha_composite(self.tint(out, rgba(component.get("color"))), (pr[0], pr[1]))

        elif kind == "tmp_text":
            self.draw_tmp_text(target, obj, component, self.text_override(obj, component, state))

    def render(self, mode: str, username=None, minecraft_version=None):
        target = Image.new("RGBA", (self.width, self.height), (0, 0, 0, 255))
        hitboxes = []
        state = {"username": username, "minecraft_version": minecraft_version}

        overrides = {}
        if mode == "start":
            overrides.update({"StartPanel": True, "MainPanel": False})
        elif mode == "main":
            overrides.update({"StartPanel": False, "MainPanel": True})

        # Runtime-only dropdown templates/popout panels are serialized active in
        # Unity because components toggle/instantiate them on Start. They are not
        # visible on the normal launcher screen.
        force_hidden_names = {"Template", "Changelog popout"}

        def visit(obj, ancestor_visible=True, hidden_by_runtime=False):
            active = bool(obj.get("active", True))
            if obj.get("name") in overrides:
                active = overrides[obj["name"]]
            hidden = hidden_by_runtime or obj.get("name") in force_hidden_names
            visible = ancestor_visible and active and not hidden
            if not visible:
                return

            if obj["id"] != self.canvas["id"]:
                self.rect(obj)
                for component in obj.get("components", []):
                    self.draw_component(target, obj, component, state)
                    if component.get("kind") == "button" and component.get("interactable", True):
                        methods = component.get("on_click_methods") or []
                        if methods:
                            hitboxes.append({
                                "object_id": obj["id"],
                                "name": obj.get("name"),
                                "rect": self.pixel_rect(self.rect(obj)),
                                "methods": methods,
                            })

            for child in self.children.get(obj["id"], []):
                visit(child, visible, hidden)

        visit(self.canvas)
        return target, hitboxes


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("menu_json", type=Path)
    ap.add_argument("qcxr_root", type=Path)
    ap.add_argument("fonts_dir", type=Path)
    ap.add_argument("output_dir", type=Path)
    ap.add_argument("--username", default="Add Account")
    ap.add_argument("--minecraft-version", default="1.20.4")
    args = ap.parse_args()

    r = MenuRenderer(args.menu_json, args.qcxr_root, args.fonts_dir)
    args.output_dir.mkdir(parents=True, exist_ok=True)

    combined = {"format": "nexa-questcraft-mainmenu-hitboxes-v1", "screens": {}}
    for mode, filename in (("source", "source.png"), ("start", "start.png"), ("main", "main.png")):
        image, hitboxes = r.render(mode, args.username, args.minecraft_version)
        image.save(args.output_dir / filename, optimize=True)
        combined["screens"][mode] = {"texture": filename, "buttons": hitboxes}
        print(mode, "->", filename, "buttons", len(hitboxes))

    (args.output_dir / "hitboxes.json").write_text(
        json.dumps(combined, indent=2), encoding="utf-8"
    )
    print("Rendered original QuestCraft MainMenu:", args.output_dir)


if __name__ == "__main__":
    main()
