from __future__ import annotations

from dataclasses import dataclass
from typing import Protocol


class Model(Protocol):
    def text(self, system: str, user: str) -> str: ...


@dataclass
class Route:
    model: str
    reason: str


class ModelGateway:
    def __init__(self, models: dict[str, Model] | None = None, default: str = "reasoning") -> None:
        self.models = models or {}
        self.default = default

    def route(self, task: str, needs_vision: bool = False, complexity: int = 1) -> Route:
        if needs_vision:
            return Route("vision", "visual input required")
        if complexity >= 3:
            return Route("reasoning", "multi-step planning")
        if complexity == 2:
            return Route("general", "bounded decision")
        return Route("small", "simple classification or extraction")

    def complete(self, system: str, user: str, complexity: int = 1, needs_vision: bool = False) -> str:
        route = self.route(user, needs_vision, complexity)
        model = self.models.get(route.model) or self.models.get(self.default)
        if model is None:
            raise RuntimeError(f"no model available for route {route.model}")
        return model.text(system, user)


class EchoModel:
    def text(self, system: str, user: str) -> str:
        return ""
