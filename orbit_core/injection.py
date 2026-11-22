from __future__ import annotations

import base64
import re
import secrets
from dataclasses import dataclass

from .models import Observation, new_id

PATTERNS = (
    re.compile(r"ignore (all |any )?(previous|prior|above) instructions", re.I),
    re.compile(r"disregard (the )?system", re.I),
    re.compile(r"reveal (your |the )?(system prompt|password|secret|api key)", re.I),
    re.compile(r"send .*(password|otp|card|secret).* to", re.I),
    re.compile(r"you are now", re.I),
    re.compile(r"developer mode", re.I),
)


@dataclass
class Wrapped:
    marker: str
    text: str
    encoded: bool


def scan(text: str) -> list[str]:
    return [match.group(0) for pattern in PATTERNS for match in [pattern.search(text or "")] if match]


def wrap_untrusted(text: str, encoding: str = "none") -> Wrapped:
    marker = secrets.token_hex(8)
    payload = text or ""
    encoded = False
    if encoding == "base64":
        payload = base64.b64encode(payload.encode()).decode()
        encoded = True
    return Wrapped(marker=marker, text=f"<untrusted:{marker}>{payload}</untrusted:{marker}>", encoded=encoded)


def unwrap(wrapped: Wrapped) -> str:
    pattern = re.compile(rf"<untrusted:{re.escape(wrapped.marker)}>(.*?)</untrusted:{re.escape(wrapped.marker)}>", re.S)
    match = pattern.search(wrapped.text)
    if not match:
        return ""
    value = match.group(1)
    if wrapped.encoded:
        try:
            value = base64.b64decode(value).decode()
        except Exception:
            return ""
    return value


class CaMeLParser:
    def __init__(self) -> None:
        self._findings: list[str] = []

    def parse(self, trusted_query: str, observations: list[Observation]) -> dict:
        self._findings = []
        facts = []
        for item in observations:
            wrapped = wrap_untrusted(item.text)
            value = unwrap(wrapped)
            if not value:
                continue
            findings = scan(value)
            if findings:
                self._findings.extend(findings)
                continue
            facts.append({
                "id": item.id,
                "source": item.source,
                "trust": item.trust,
                "text": value,
            })
        return {
            "id": new_id("plan"),
            "query": trusted_query,
            "facts": facts,
            "suspicious": self._findings,
            "instructions_from_untrusted": False,
        }

    @property
    def findings(self) -> list[str]:
        return self._findings
