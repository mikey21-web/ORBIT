"""Redact device-specific data from a generated app map.

The crawler records whatever text is on screen, which on a personal phone
includes the account name, a masked phone number and the device name. The iOS
side has ``sanitize_map.py`` for this; the Android path had nothing, so maps
could not be shared or committed.

    python3 -m orbit.redact --map orbit/maps/device.yaml --auto
    python3 -m orbit.redact --map m.yaml --term name --term "Device Model"

Redaction is conservative: only the matched substring is replaced, so
"phone number:181****8488" becomes "phone number:<phone>" and the row still reads like a
row. Because the label no longer matches what the device shows, the element
is flagged ``redacted: true`` and the replay layer stops using it as a text
anchor (it falls back to the recorded coordinates).
"""

from __future__ import annotations

import logging

import argparse
import re
import sys
from pathlib import Path
from typing import Iterable

import yaml

PHONE_RE = re.compile(r"(?:\+?\d{1,3}[\s-]?)?\d[\d\s\-]{6,}\d")
MASKED_PHONE_RE = re.compile(r"\d{3}\*{4}\d{4}")
EMAIL_RE = re.compile(r"[\w.+-]+@[\w-]+\.[\w.]{2,}")

PHONE_TOKEN = "<phone>"
EMAIL_TOKEN = "<email>"
PERSONAL_TOKEN = "<personal>"


BRAND_RE = re.compile(r"\b(REDMI|Xiaomi|iPhone|Galaxy|Pixel)\b", re.I)


def _owner_hint(value: str) -> str:
    match = BRAND_RE.search(value)
    if match and match.start() >= 2:
        return value[:match.start()].strip(" \t-_:.,")
    return ""


def auto_terms(device=None) -> list[str]:
    terms: list[str] = []
    if device is None:
        return terms
    try:
        name = device.shell("settings get global device_name").strip()
        if name and name != "null":
            terms.append(name)
            hint = _owner_hint(name)
            if len(hint) >= 3:
                terms.append(hint)
    except Exception:
        logging.getLogger(__name__).debug('swallowed exception', exc_info=True)
    try:
        model = device.info().model
        if model:
            terms.append(model)
    except Exception:
        logging.getLogger(__name__).debug('swallowed exception', exc_info=True)
    return [t for t in terms if t]


def name_candidates(texts: Iterable[str], terms: Iterable[str]) -> list[str]:
    usable = [t for t in terms if t]
    found: list[str] = []
    for text in texts:
        for term in usable:
            index = text.find(term)
            if index <= 0:
                continue
            prefix = text[:index].strip(" \t-_:.,")
            for candidate in (prefix, _owner_hint(prefix)):
                if 2 <= len(candidate) <= 20 and candidate not in found:
                    found.append(candidate)
    return found


def _redact_text(text: str, terms: Iterable[str]) -> tuple[str, bool]:
    original = text
    # A label that mentions the device name at all ("nameofDeviceModel") is
    # personal in its entirety: replacing only the model would leave the name.
    if any(term and term in text for term in terms):
        return PERSONAL_TOKEN, True
    for term in terms:
        if term and term in text:
            text = text.replace(term, PERSONAL_TOKEN)
    text = MASKED_PHONE_RE.sub(PHONE_TOKEN, text)
    text = PHONE_RE.sub(PHONE_TOKEN, text)
    text = EMAIL_RE.sub(EMAIL_TOKEN, text)
    return text, text != original


def redact_map(
    path: str | Path, terms: Iterable[str] = (), out: str | Path | None = None
) -> dict:
    """Redact a map, in place or into ``out``.

    Redaction replaces the very labels the replay layer uses for screen
    identification and text-anchored taps, so a redacted map is for sharing,
    not for running: measured landing drops from 100% to ~50%. Build the
    working map unredacted and export a ``*.public.yaml`` copy.
    """
    path = Path(path)
    data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    terms = [t for t in terms if t and len(t) >= 3]
    report = {"elements": 0, "macros": 0, "terms": len(terms)}

    for screen in data.get("screens", []):
        for element in screen.get("elements", []):
            text = element.get("text", "")
            if not text:
                continue
            new_text, changed = _redact_text(text, terms)
            if changed:
                element["text"] = new_text
                element["redacted"] = True
                report["elements"] += 1

    # a macro step must not keep a text anchor that no longer exists on screen
    surviving = {
        element.get("text")
        for screen in data.get("screens", [])
        for element in screen.get("elements", [])
    }
    for macro in data.get("screen_macros", {}).values():
        for step in macro:
            if step.get("action") == "tap" and "text" in step:
                if step["text"] not in surviving:
                    step.pop("text", None)
                    report["macros"] += 1

    target = Path(out) if out else path
    target.write_text(
        yaml.safe_dump(data, allow_unicode=True, sort_keys=False), encoding="utf-8"
    )
    report["output"] = str(target)
    return report


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--map", action="append", required=True)
    parser.add_argument("--term", action="append", default=[], help="literal string to redact")
    parser.add_argument("--out", default=None, help="write the redacted copy here instead of in place")
    parser.add_argument("--serial", default=None, help="pull device name/model automatically")
    parser.add_argument("--adb", default=None)
    args = parser.parse_args(argv)

    terms = list(args.term)
    if args.serial:
        from .devices.adb import AdbDevice

        terms += auto_terms(AdbDevice(serial=args.serial, adb=args.adb))

    for path in args.map:
        report = redact_map(path, terms, out=args.out)
        print(f"{path} -> {report['output']}: {report['elements']} elements, "
              f"{report['macros']} macro anchors, {report['terms']} terms")
    return 0


if __name__ == "__main__":
    sys.exit(main())
