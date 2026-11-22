from __future__ import annotations

import time
from dataclasses import dataclass, field

from .models import new_id


@dataclass
class Handover:
    id: str
    task_id: str
    question: str
    kind: str
    context: dict = field(default_factory=dict)
    created_at: str = field(default_factory=lambda: time.strftime("%Y-%m-%dT%H:%M:%S"))
    answer: str = ""
    answered: bool = False


class HandoverManager:
    def __init__(self) -> None:
        self._items: dict[str, Handover] = {}

    def request(self, task_id: str, question: str, kind: str = "ask", context: dict | None = None) -> Handover:
        item = Handover(
            id=new_id("hand"), task_id=task_id, question=question,
            kind=kind, context=context or {},
        )
        self._items[item.id] = item
        return item

    def answer(self, handover_id: str, text: str) -> Handover:
        item = self._items.get(handover_id)
        if item is None:
            raise KeyError(handover_id)
        item.answer = text
        item.answered = True
        return item

    def get(self, handover_id: str) -> Handover | None:
        return self._items.get(handover_id)

    def pending(self) -> list[Handover]:
        return [h for h in self._items.values() if not h.answered]
