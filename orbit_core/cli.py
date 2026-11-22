from __future__ import annotations

import argparse

from .orchestrator import Orchestrator
from .policy import PolicyEngine
from .tools import ToolRegistry
from .models import RiskLevel


def build() -> Orchestrator:
    tools = ToolRegistry()
    tools.register("read", lambda: {"observed": "sunny, 24C", "verified": True}, RiskLevel.LOW, "read weather")
    tools.register("create", lambda: {"observed": "reminder created", "verified": True}, RiskLevel.MODERATE, "create reminder")
    tools.register("send", lambda: {"observed": "message sent", "verified": True}, RiskLevel.CRITICAL, "send money")
    return Orchestrator(tools=tools, policy=PolicyEngine(granted_scopes={"read", "create", "send"}))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="ORBIT Core + Trust demo")
    parser.add_argument("task", nargs="?", default="read weather")
    parser.add_argument("--demo", action="store_true")
    args = parser.parse_args(argv)

    agent = build()
    if args.demo:
        for text in ("read weather", "create a reminder", "send money"):
            wf = agent.handle(text)
            print(f"\n> {text}\n  state: {wf.state}\n  {wf.answer}")
            for approval_id in list(agent.pending):
                wf = agent.resume(wf.id, approval_id)
                print(f"  after approval: {wf.state} — {wf.answer}")
        print("\naudit entries:", len(agent.store.audit()))
        print("events:", len(agent.store.events()))
        return 0

    wf = agent.handle(args.task)
    print(f"state: {wf.state}\n{wf.answer}")
    for approval_id in list(agent.pending):
        print(f"pending approval: {approval_id}")
    return 0 if wf.state == "Completed" else 1


if __name__ == "__main__":
    raise SystemExit(main())
