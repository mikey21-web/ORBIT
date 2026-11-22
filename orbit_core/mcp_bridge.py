from __future__ import annotations

import time
from dataclasses import dataclass, field
from typing import Any, Callable

from .models import new_id


@dataclass
class CapabilityToken:
    principal: str
    scopes: set[str] = field(default_factory=set)
    expires_at: float = 0.0
    one_time: bool = False
    used: bool = False


class MCPBridge:
    def __init__(self) -> None:
        self._tools: dict[str, tuple[Callable[..., Any], set[str], dict[str, Any]]] = {}

    def register(self, name: str, handler: Callable[..., Any],
                 scopes: set[str] | None = None, schema: dict[str, Any] | None = None) -> None:
        self._tools[name] = (handler, scopes or set(), schema or {"type": "object", "properties": {}})

    def token(self, principal: str, scopes: set[str], ttl: float = 300.0, one_time: bool = False) -> CapabilityToken:
        return CapabilityToken(principal, set(scopes), time.time() + ttl, one_time)

    def list_tools(self, token: CapabilityToken) -> list[dict[str, Any]]:
        result = []
        for name, (_, scopes, schema) in sorted(self._tools.items()):
            if scopes and not scopes.issubset(token.scopes):
                continue
            result.append({"name": name, "inputSchema": schema})
        return result

    def call(self, name: str, args: dict[str, Any], token: CapabilityToken) -> Any:
        if token.expires_at and time.time() > token.expires_at:
            raise PermissionError("token expired")
        if token.used and token.one_time:
            raise PermissionError("token already used")
        entry = self._tools.get(name)
        if entry is None:
            raise KeyError(f"unknown tool: {name}")
        handler, scopes, _ = entry
        if scopes and not scopes.issubset(token.scopes):
            raise PermissionError(f"missing scopes: {sorted(scopes - token.scopes)}")
        if token.one_time:
            token.used = True
        return handler(**args)
