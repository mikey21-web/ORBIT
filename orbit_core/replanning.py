from __future__ import annotations

import re
from dataclasses import dataclass

from .models import Task, new_id

HEDGES = ("maybe", "perhaps", "probably", "might", "possibly", "unclear", "not sure", "i think")
VAGUE = ("something", "some", "maybe", "stuff", "things", "somewhere")


@dataclass
class Uncertainty:
    score: float
    reasons: list[str]


class UncertaintyEvaluator:
    def score(self, text: str) -> Uncertainty:
        value = (text or "").lower()
        reasons = []
        hits = [word for word in HEDGES if word in value]
        if hits:
            reasons.append(f"hedging: {', '.join(hits)}")
        if "?" in value:
            reasons.append("question mark")
        if len(value) < 12:
            reasons.append("very short input")
        score = min(1.0, len(hits) * 0.25 + (0.2 if "?" in value else 0) + (0.2 if len(value) < 12 else 0))
        return Uncertainty(score, reasons)


class Replanner:
    def __init__(self, evaluator: UncertaintyEvaluator | None = None) -> None:
        self.evaluator = evaluator or UncertaintyEvaluator()

    def alternative(self, task: Task, reason: str = "verification failed") -> Task:
        uncertainty = self.evaluator.score(task.objective)
        strategy = "ask the user for clarification" if uncertainty.score >= 0.5 else "try a different UI path"
        return Task(
            id=new_id("task"),
            objective=f"Alternative for {task.objective}: {strategy}",
            parent_id=task.parent_id,
            depends_on=[task.id],
            capability=task.capability,
            tool=task.tool,
            tool_args=dict(task.tool_args),
            inputs={"retry_reason": reason, "uncertainty": uncertainty.score},
            required_scopes=set(task.required_scopes),
            risk=task.risk,
            retry_limit=0,
        )
