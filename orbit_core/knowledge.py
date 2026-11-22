from __future__ import annotations

import time
from dataclasses import dataclass, field

from .models import new_id


@dataclass
class Entity:
    id: str
    kind: str
    name: str
    attrs: dict = field(default_factory=dict)


@dataclass
class Relation:
    id: str
    subject: str
    predicate: str
    obj: str
    valid_from: float = 0.0
    valid_to: float = 0.0


class KnowledgeGraph:
    def __init__(self) -> None:
        self.entities: dict[str, Entity] = {}
        self.relations: list[Relation] = []

    def add(self, kind: str, name: str, attrs: dict | None = None) -> Entity:
        entity = Entity(id=new_id("ent"), kind=kind, name=name, attrs=attrs or {})
        self.entities[entity.id] = entity
        return entity

    def relate(self, subject: str, predicate: str, obj: str,
               valid_from: float = 0.0, valid_to: float = 0.0) -> Relation:
        relation = Relation(
            id=new_id("rel"), subject=subject, predicate=predicate, obj=obj,
            valid_from=valid_from, valid_to=valid_to,
        )
        self.relations.append(relation)
        return relation

    def query(self, subject: str = "", predicate: str = "", at: float = 0.0) -> list[Relation]:
        now = at or time.time()
        return [
            r for r in self.relations
            if (not subject or r.subject == subject)
            and (not predicate or r.predicate == predicate)
            and (not r.valid_from or r.valid_from <= now)
            and (not r.valid_to or r.valid_to >= now)
        ]

    def find(self, name: str = "", kind: str = "") -> list[Entity]:
        return [
            e for e in self.entities.values()
            if (not name or name.lower() in e.name.lower())
            and (not kind or e.kind == kind)
        ]
