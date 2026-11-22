from __future__ import annotations

import json
import time
from dataclasses import dataclass

from .memory import MemoryStore
from .models import new_id


@dataclass
class DataDisclosure:
    id: str
    destination: str
    kind: str
    detail: str
    created_at: str


class PrivacyDashboard:
    def __init__(self, memory: MemoryStore | None = None) -> None:
        self.memory = memory or MemoryStore()
        self.disclosures: list[DataDisclosure] = []
        self._allowlist: set[str] = set()

    def record(self, destination: str, kind: str, detail: str) -> DataDisclosure:
        item = DataDisclosure(
            id=new_id("disc"), destination=destination, kind=kind,
            detail=detail[:200], created_at=time.strftime("%Y-%m-%dT%H:%M:%S"),
        )
        self.disclosures.append(item)
        return item

    def allow(self, destination: str) -> None:
        self._allowlist.add(destination)

    def revoke(self, destination: str) -> bool:
        return destination in self._allowlist and self._allowlist.discard(destination) is not None

    def permitted(self, destination: str) -> bool:
        return destination in self._allowlist

    def report(self) -> dict:
        return {
            "memories": [
                {"id": m.id, "kind": m.kind, "source": m.source, "scope": m.scope}
                for m in self.memory.all()
            ],
            "disclosures": [d.__dict__ for d in self.disclosures],
            "allowed_destinations": sorted(self._allowlist),
        }

    def json(self) -> str:
        return json.dumps(self.report(), indent=2)
