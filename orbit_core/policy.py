from __future__ import annotations

import re
from dataclasses import dataclass

from .models import RiskLevel, Task


CRITICAL = re.compile(
    r"(transfer|pay|payment|purchase|buy|order|delete|remove|wipe|send money|upi|bank|refund|subscribe)",
    re.I,
)
HIGH = re.compile(
    r"(send|message|email|post|tweet|call|invite|forward|change password|permission|settings|install|uninstall)",
    re.I,
)
MODERATE = re.compile(
    r"(create|add|write|update|edit|schedule|book|remind|move|rename|organise|organize)",
    re.I,
)


def classify_risk(action: str, target: str = "", content: str = "") -> RiskLevel:
    text = f"{action} {target} {content}"
    if CRITICAL.search(text):
        return RiskLevel.CRITICAL
    if HIGH.search(text):
        return RiskLevel.HIGH
    if MODERATE.search(text):
        return RiskLevel.MODERATE
    return RiskLevel.LOW


@dataclass
class Decision:
    allowed: bool
    reason: str
    risk: RiskLevel
    requires_approval: bool = False


class PolicyEngine:
    def __init__(self, granted_scopes: set[str] | None = None, allowed_tools: set[str] | None = None) -> None:
        self.granted_scopes = granted_scopes or set()
        self.allowed_tools = allowed_tools or set()

    def decide(self, task: Task, tool: str | None = None) -> Decision:
        risk = task.risk
        tool = tool or task.tool
        if task.required_scopes and not task.required_scopes.issubset(self.granted_scopes):
            missing = sorted(task.required_scopes - self.granted_scopes)
            return Decision(False, f"missing scopes: {', '.join(missing)}", risk)
        if self.allowed_tools and tool and tool not in self.allowed_tools:
            return Decision(False, f"tool not allowed: {tool}", risk)
        if risk in (RiskLevel.HIGH, RiskLevel.CRITICAL):
            return Decision(True, "requires explicit approval", risk, True)
        if risk == RiskLevel.MODERATE:
            return Decision(True, "within granted scope", risk)
        return Decision(True, "low risk", risk)


class DenyAllPolicy(PolicyEngine):
    def decide(self, task: Task, tool: str | None = None) -> Decision:
        return Decision(False, "fail-closed: no policy configured", task.risk)
