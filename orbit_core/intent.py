from __future__ import annotations

import re
from dataclasses import dataclass

from .gateway import ModelGateway
from .models import TaskKind


QUESTION = re.compile(r"^(what|why|how|who|when|where|is|are|can|does|do|explain|tell me about)\b", re.I)
GOAL = re.compile(r"\b(prepare|organise|organize|handle|manage|plan|take care of|get everything ready)\b", re.I)


@dataclass
class Intent:
    kind: TaskKind
    confidence: float
    objective: str


class IntentInterpreter:
    def __init__(self, gateway: ModelGateway | None = None) -> None:
        self.gateway = gateway

    def interpret(self, text: str) -> Intent:
        clean = (text or "").strip()
        if not clean:
            return Intent(TaskKind.QUESTION, 0.0, "")
        if QUESTION.match(clean) and not GOAL.search(clean):
            return Intent(TaskKind.QUESTION, 0.9, clean)
        if GOAL.search(clean):
            return Intent(TaskKind.GOAL, 0.85, clean)
        return Intent(TaskKind.TASK, 0.8, clean)
