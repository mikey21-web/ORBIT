from __future__ import annotations

import time
from dataclasses import dataclass, field

from .models import Task, TaskKind, new_id
from .orchestrator import Orchestrator


@dataclass
class Goal:
    id: str
    objective: str
    deadline: float = 0.0
    progress: list[str] = field(default_factory=list)
    status: str = "active"


@dataclass
class Suggestion:
    id: str
    action: str
    reason: str
    confidence: float


class AutonomyEngine:
    def __init__(self, orchestrator: Orchestrator | None = None) -> None:
        self.orchestrator = orchestrator or Orchestrator()
        self.goals: dict[str, Goal] = {}

    def add_goal(self, objective: str, deadline: float = 0.0) -> Goal:
        goal = Goal(id=new_id("goal"), objective=objective, deadline=deadline)
        self.goals[goal.id] = goal
        return goal

    def suggest(self) -> list[Suggestion]:
        now = time.time()
        result = []
        for goal in self.goals.values():
            if goal.status != "active":
                continue
            if goal.deadline and goal.deadline - now < 86400:
                result.append(Suggestion(
                    id=new_id("sug"),
                    action=f"prepare work for: {goal.objective}",
                    reason="deadline within 24 hours",
                    confidence=0.9,
                ))
            if not goal.progress:
                result.append(Suggestion(
                    id=new_id("sug"),
                    action=f"take first step on: {goal.objective}",
                    reason="goal has no recorded progress",
                    confidence=0.7,
                ))
        return result

    def delegate(self, objective: str, helpers: list[Orchestrator]) -> list[str]:
        answers = []
        for index, helper in enumerate(helpers):
            wf = helper.handle(f"review {objective} from perspective {index + 1}")
            answers.append(wf.answer)
        return answers

    def advance(self, goal_id: str, step: str) -> Goal:
        goal = self.goals[goal_id]
        goal.progress.append(step)
        if goal.deadline and time.time() > goal.deadline:
            goal.status = "overdue"
        return goal
