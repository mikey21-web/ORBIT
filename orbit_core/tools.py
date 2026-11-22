from __future__ import annotations

from typing import Any, Callable

from .models import RiskLevel, Tool, new_id


class ToolRegistry:
    def __init__(self) -> None:
        self._tools: dict[str, Tool] = {}
        self._handlers: dict[str, Callable[..., Any]] = {}

    def register(self, name: str, handler: Callable[..., Any], risk: RiskLevel = RiskLevel.LOW,
                 description: str = "", input_schema: dict[str, Any] | None = None) -> Tool:
        tool = Tool(
            id=new_id("tool"), name=name, description=description, risk=risk,
            input_schema=input_schema or {},
        )
        self._tools[name] = tool
        self._handlers[name] = handler
        return tool

    def get(self, name: str) -> Tool | None:
        return self._tools.get(name)

    def names(self) -> list[str]:
        return sorted(self._tools)

    def schemas(self, allowlist: set[str] | None = None) -> list[dict[str, Any]]:
        result = []
        for name, tool in sorted(self._tools.items()):
            if allowlist is not None and name not in allowlist:
                continue
            result.append({
                "name": name,
                "description": tool.description,
                "risk": tool.risk.value,
                "parameters": tool.input_schema,
            })
        return result

    def invoke(self, name: str, args: dict[str, Any]) -> Any:
        if name not in self._handlers:
            raise KeyError(f"unknown tool: {name}")
        return self._handlers[name](**args)
