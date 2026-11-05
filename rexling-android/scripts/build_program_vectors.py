#!/usr/bin/env python3
"""Compile the retained catalog SVG subset to native Canvas path commands.

No rasterization: SVG masks are monochrome alpha logos and can be drawn as tinted
paths. Unsupported SVG features fail generation instead of silently losing art.
"""
import base64
import json
import re
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "artwork/program-icons/superlogical"
OUTPUT = ROOT / "app/src/main/assets/program_tiles.json"


def tag(node):
    return node.tag.rsplit("}", 1)[-1]


def number(value):
    return float(str(value).removesuffix("px"))


def shapes(node):
    kind = tag(node)
    if kind == "path":
        return node.attrib["d"]
    if kind == "rect":
        assert not node.get("rx") and not node.get("ry")
        x, y, w, h = [number(node.get(k, 0)) for k in ("x", "y", "width", "height")]
        return f"M{x},{y}h{w}v{h}h{-w}Z"
    if kind in ("polygon", "polyline"):
        points = re.findall(r"[-+]?(?:\d*\.\d+|\d+)(?:[eE][-+]?\d+)?", node.attrib["points"])
        assert len(points) % 2 == 0
        return "M" + "L".join(",".join(points[i:i+2]) for i in range(0, len(points), 2)) + ("Z" if kind == "polygon" else "")
    raise ValueError(f"Unsupported shape: {kind}")


def reference(value):
    assert value.startswith("url(#") and value.endswith(")"), value
    return value[5:-1]


def compile_icon(root):
    commands = [{"op": "translate", "x": -6, "y": -6}]

    def draw(node, inherited, definitions, ink=None, opacity=1):
        kind = tag(node)
        assert node.get("filter") is None, "SVG filters are not supported"
        assert node.get("stroke") in (None, "none"), "Stroked SVG shapes are not supported"
        for declaration in node.get("style", "").split(";"):
            assert not declaration.strip() or declaration.strip().replace(" ", "") in ("mix-blend-mode:normal", "isolation:isolate", "mask-type:alpha"), declaration
        if kind in ("defs", "title", "desc"):
            return
        style = inherited.copy()
        style.update({k: node.attrib[k] for k in ("fill", "fill-opacity", "fill-rule") if k in node.attrib})
        opacity *= float(node.get("opacity", 1))
        commands.append({"op": "save"})
        if node.get("transform"):
            commands.append({"op": "transform", "value": node.get("transform")})
        if node.get("clip-path"):
            clip = definitions[reference(node.get("clip-path"))]
            assert len(clip) == 1
            commands.append({"op": "clip", "path": shapes(clip[0])})
        if node.get("mask"):
            mask = definitions[reference(node.get("mask"))]
            image = next(x for x in mask if tag(x) == "image")
            href = image.get("href", "")
            assert href.startswith("data:image/svg+xml;base64,")
            logo = ET.fromstring(base64.b64decode(href.split(",", 1)[1]))
            assert len(node) == 1 and tag(node[0]) == "rect"
            color = node[0].attrib["fill"]
            x, y, w, h = [number(image.get(k)) for k in ("x", "y", "width", "height")]
            vx, vy, vw, vh = map(float, logo.attrib["viewBox"].split())
            scale = min(w/vw, h/vh)
            commands.extend([{"op": "translate", "x": x+(w-vw*scale)/2, "y": y+(h-vh*scale)/2},
                             {"op": "scale", "x": scale, "y": scale},
                             {"op": "translate", "x": -vx, "y": -vy}])
            draw(logo, {}, {x.get("id"): x for x in logo.iter() if x.get("id")}, color, opacity)
        elif kind in ("svg", "g"):
            for child in node:
                draw(child, style, definitions, ink, opacity)
        elif kind in ("path", "rect", "polygon", "polyline"):
            fill = style.get("fill", "black")
            if fill != "none":
                paint = {"color": ink or fill, "alpha": opacity*float(style.get("fill-opacity", 1))}
                if fill.startswith("url("):
                    gradient = definitions[reference(fill)]
                    assert tag(gradient) in ("linearGradient", "radialGradient")
                    paint = {"gradient": tag(gradient), "attributes": gradient.attrib,
                             "stops": [dict(s.attrib, **{"stop-color": ink or s.get("stop-color", "black")}) for s in gradient],
                             "alpha": paint["alpha"]}
                commands.append({"op": "path", "path": shapes(node), "evenOdd": style.get("fill-rule") == "evenodd", "paint": paint})
        else:
            raise ValueError(f"Unsupported SVG element: {kind}")
        commands.append({"op": "restore"})

    draw(root, {}, {x.get("id"): x for x in root.iter() if x.get("id")})
    return commands


def main():
    icons = {p.stem.replace("-", "_"): compile_icon(ET.parse(p).getroot()) for p in sorted(SOURCE.glob("*.svg"))}
    OUTPUT.write_text(json.dumps({"width": 61, "height": 48, "icons": icons}, separators=(",", ":")) + "\n")
    print(f"Compiled {len(icons)} vector tiles to {OUTPUT.relative_to(ROOT)} ({OUTPUT.stat().st_size:,} bytes)")


if __name__ == "__main__":
    main()
