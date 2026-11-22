from __future__ import annotations

from typing import Any, Callable

from .models import RiskLevel, Task
from .tools import ToolRegistry

TOOL_RISKS = {
    "tap": RiskLevel.LOW, "long_press": RiskLevel.LOW, "swipe": RiskLevel.LOW,
    "tap_text": RiskLevel.LOW, "scroll": RiskLevel.LOW, "back": RiskLevel.LOW,
    "home": RiskLevel.LOW, "wait": RiskLevel.LOW, "web_open": RiskLevel.LOW,
    "web_text": RiskLevel.LOW, "web_network": RiskLevel.LOW,
    "finish": RiskLevel.LOW, "ask_user": RiskLevel.LOW, "hand_over": RiskLevel.LOW,
    "type": RiskLevel.MODERATE, "launch": RiskLevel.MODERATE,
    "web_click": RiskLevel.MODERATE, "web_type": RiskLevel.MODERATE,
    "web_scroll": RiskLevel.MODERATE, "macro": RiskLevel.MODERATE,
    "shell": RiskLevel.HIGH,
}


class PhoneExecutor:
    def __init__(self, agent: Any) -> None:
        self.agent = agent

    def run(self, task: str) -> dict[str, Any]:
        result = self.agent.run(task)
        steps = []
        for step in getattr(result, "steps", []) or []:
            steps.append({
                "round": getattr(step, "round", 0),
                "kind": getattr(step, "kind", ""),
                "action": getattr(step, "action", ""),
                "detail": getattr(step, "detail", ""),
            })
        ok = bool(getattr(result, "ok", False))
        answer = getattr(result, "answer", "") or getattr(result, "reason", "")
        return {
            "observed": answer,
            "verified": ok,
            "ok": ok,
            "method": "ui_state",
            "steps": steps,
            "rounds": getattr(result, "rounds", 0),
            "used_op": getattr(result, "used_op", ""),
        }


def as_callback(executor: PhoneExecutor) -> Callable[[Task], dict[str, Any]]:
    def run(task: Task) -> dict[str, Any]:
        return executor.run(task.objective)
    return run


def register_phone_tools(registry: ToolRegistry) -> ToolRegistry:
    for name, risk in TOOL_RISKS.items():
        registry.register(
            name=name,
            handler=lambda name=name, **kwargs: {"observed": f"{name}({kwargs})", "verified": True},
            risk=risk,
            description=f"Phone action: {name}",
            input_schema={"type": "object", "properties": {}},
        )
    return registry
