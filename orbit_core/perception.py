from __future__ import annotations

import io
import re
import xml.etree.ElementTree as ET

from .models import Observation, new_id

ALLOWED = (
    "text", "content-desc", "resource-id", "class", "package",
    "bounds", "clickable", "editable", "focused", "checked", "enabled",
)
SENSITIVE = re.compile(r"(password|passwd|otp|verification code|cvv|pin\b)", re.I)
BOUNDS = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")


def sanitize_ui_tree(xml: str, max_nodes: int = 500, max_depth: int = 30) -> str:
    if not xml or not xml.strip():
        return "<hierarchy/>"
    try:
        root = ET.fromstring(xml)
    except ET.ParseError:
        return "<hierarchy/>"

    kept = 0

    def walk(node: ET.Element, depth: int) -> ET.Element | None:
        nonlocal kept
        if kept >= max_nodes or depth > max_depth:
            return None
        attrs = {k: v for k, v in node.attrib.items() if k in ALLOWED}
        blob = " ".join(attrs.values())
        if SENSITIVE.search(blob) or "FLAG_SECURE" in blob:
            return None
        clean = ET.Element(node.tag, attrs)
        for child in list(node):
            result = walk(child, depth + 1)
            if result is not None:
                clean.append(result)
        kept += 1
        return clean

    result = walk(root, 0)
    if result is None:
        return "<hierarchy/>"
    return ET.tostring(result, encoding="unicode")


def observations_from_ui(xml: str) -> list[Observation]:
    if not xml:
        return []
    try:
        root = ET.fromstring(xml)
    except ET.ParseError:
        return []
    items: list[Observation] = []
    for node in root.iter():
        text = (node.attrib.get("text") or node.attrib.get("content-desc") or "").strip()
        if not text:
            continue
        match = BOUNDS.search(node.attrib.get("bounds", ""))
        bounds = None
        if match:
            bounds = tuple(int(match.group(i)) for i in range(1, 5))
        items.append(Observation(
            id=new_id("obs"), source="android_screen", kind="control",
            text=text[:200], bounds=bounds,
            confidence=1.0, trust="untrusted_external_content",
        ))
    return items


def redact_screenshot(png: bytes, top_fraction: float = 0.08) -> bytes:
    try:
        from PIL import Image, ImageDraw
    except ImportError:
        return png
    try:
        image = Image.open(io.BytesIO(png)).convert("RGB")
    except Exception:
        return png
    height = max(1, int(image.height * top_fraction))
    draw = ImageDraw.Draw(image)
    draw.rectangle((0, 0, image.width, height), fill=(0, 0, 0))
    out = io.BytesIO()
    image.save(out, format="PNG", optimize=True)
    return out.getvalue()
