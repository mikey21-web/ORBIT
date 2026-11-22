from __future__ import annotations

from .models import Task

STATES = (
    "Received", "Understood", "Planned", "PolicyCheck", "AwaitingApproval",
    "Ready", "Cancelled", "Executing", "Verifying", "Completed", "Recovering",
    "Escalated", "Failed",
)

TRANSITIONS: dict[str, set[str]] = {
    "Received": {"Understood", "Cancelled"},
    "Understood": {"Planned", "Cancelled"},
    "Planned": {"PolicyCheck", "Cancelled"},
    "PolicyCheck": {"AwaitingApproval", "Ready", "Cancelled", "Failed"},
    "AwaitingApproval": {"Ready", "Cancelled", "Escalated"},
    "Ready": {"Executing", "Cancelled"},
    "Executing": {"Verifying", "Recovering", "Cancelled", "Failed", "Escalated"},
    "Verifying": {"Completed", "Recovering", "Failed"},
    "Recovering": {"Ready", "Escalated", "Failed", "Cancelled"},
    "Escalated": {"AwaitingApproval", "Ready", "Cancelled", "Failed"},
    "Completed": set(),
    "Failed": set(),
    "Cancelled": set(),
}

TERMINAL = {"Completed", "Failed", "Cancelled"}


class InvalidTransition(Exception):
    pass


def transition(task: Task, next_state: str) -> None:
    if next_state not in STATES:
        raise InvalidTransition(f"unknown state {next_state}")
    allowed = TRANSITIONS.get(task.state, set())
    if next_state not in allowed:
        raise InvalidTransition(f"{task.state} -> {next_state}")
    task.state = next_state
