from __future__ import annotations

import uuid
from typing import Any, Callable

from .models import new_id
from .store import Store


class EventBus:
    def __init__(self, store: Store) -> None:
        self.store = store
        self._handlers: dict[str, list[Callable[[dict[str, Any]], None]]] = {}

    def subscribe(self, kind: str, handler: Callable[[dict[str, Any]], None]) -> None:
        self._handlers.setdefault(kind, []).append(handler)

    def publish(self, kind: str, task_id: str = "", trace_id: str = "", **payload: Any) -> dict[str, Any]:
        event_id = new_id("evt")
        self.store.publish(event_id, kind, task_id, trace_id, payload)
        event = {"id": event_id, "kind": kind, "task_id": task_id, "trace_id": trace_id, **payload}
        for handler in self._handlers.get(kind, []):
            handler(event)
        for handler in self._handlers.get("*", []):
            handler(event)
        return event

    @staticmethod
    def idempotency_key() -> str:
        return uuid.uuid4().hex
