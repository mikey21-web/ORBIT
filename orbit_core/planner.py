from __future__ import annotations

import re

from .models import Task, TaskKind, new_id
from .policy import classify_risk

SPLIT = re.compile(r"\s*(?:,|\band then\b|\bthen\b|;|\.)\s*", re.I)


class Planner:
    def __init__(self, tool_names: list[str] | None = None) -> None:
        self.tool_names = tool_names or []

    def build(self, objective: str, kind: TaskKind = TaskKind.TASK) -> list[Task]:
        parts = [p.strip() for p in SPLIT.split(objective) if p.strip()]
        if len(parts) == 1:
            parts = [objective.strip()]
        tasks: list[Task] = []
        for i, part in enumerate(parts):
            matched = next((name for name in self.tool_names if name and name.lower() in part.lower()), "")
            task = Task(
                id=new_id("task"), objective=part, kind=TaskKind.TASK,
                parent_id="" if kind == TaskKind.TASK else "goal",
                depends_on=[tasks[-1].id] if tasks and kind == TaskKind.GOAL else [],
                risk=classify_risk(part), tool=matched,
            )
            tasks.append(task)
        return tasks
