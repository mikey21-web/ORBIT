from __future__ import annotations

import re

from .injection import scan
from .secrets import SecretStore

DENY = re.compile(r"(ignore previous|reveal password|send secret|system prompt)", re.I)


class Guardrails:
    def __init__(self, secrets: SecretStore | None = None, max_input: int = 8000) -> None:
        self.secrets = secrets or SecretStore()
        self.max_input = max_input

    def check_input(self, text: str) -> tuple[bool, str]:
        if not text or not text.strip():
            return False, "empty input"
        if len(text) > self.max_input:
            return False, "input too long"
        findings = scan(text)
        if findings:
            return False, f"injection pattern: {findings[0]}"
        if DENY.search(text):
            return False, "blocked request"
        return True, "ok"

    def check_output(self, text: str) -> tuple[bool, str]:
        clean = self.secrets.redact(text or "")
        if clean != (text or ""):
            return False, "output contained a secret"
        return True, "ok"
