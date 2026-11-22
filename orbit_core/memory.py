from __future__ import annotations

import time

from .models import Memory, MemoryProposal, MemoryResult, new_id


class MemoryStore:
    def __init__(self) -> None:
        self._items: dict[str, Memory] = {}

    def add(self, proposal: MemoryProposal) -> Memory:
        item = Memory(
            id=new_id("mem"), kind=proposal.kind, content=proposal.content,
            source=proposal.source, confidence=proposal.confidence,
            expires_at=proposal.expires_at, scope=proposal.scope,
        )
        self._items[item.id] = item
        return item

    def get(self, memory_id: str) -> Memory | None:
        return self._items.get(memory_id)

    def recall(self, query: str = "", limit: int = 5) -> list[Memory]:
        now = time.time()
        tokens = [t for t in (query or "").lower().split() if len(t) > 2]
        scored = []
        for item in self._items.values():
            if item.tombstone:
                continue
            if item.expires_at and item.expires_at < now:
                continue
            text = item.content.lower()
            score = sum(1 for t in tokens if t in text)
            if tokens and score == 0:
                continue
            scored.append((score + item.confidence, item.created_at, item))
        scored.sort(key=lambda row: (row[0], row[1]), reverse=True)
        return [row[2] for row in scored[:limit]]

    def delete(self, memory_id: str) -> bool:
        item = self._items.get(memory_id)
        if item is None:
            return False
        item.tombstone = True
        item.content = ""
        return True

    def purge(self, scope: str = "") -> int:
        removed = 0
        for memory_id, item in list(self._items.items()):
            if not scope or item.scope == scope:
                self._items.pop(memory_id, None)
                removed += 1
        return removed

    def all(self) -> list[Memory]:
        return [m for m in self._items.values() if not m.tombstone]


class ContextResolver:
    def __init__(self, memory: MemoryStore | None = None) -> None:
        self.memory = memory or MemoryStore()

    def build(self, query: str, observations: list[str] | None = None) -> MemoryResult:
        items = self.memory.recall(query)
        lines = [f"- [{m.kind}] {m.content}" for m in items]
        if observations:
            lines.append("- current screen: " + " | ".join(observations[:10]))
        return MemoryResult(
            message="\n".join(lines),
            used_ids=[m.id for m in items],
        )
