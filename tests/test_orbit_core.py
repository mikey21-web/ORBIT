import pytest

from orbit_core import (
    ApprovalGateway, DenyAllPolicy, InvalidTransition, Orchestrator,
    PolicyEngine, RiskLevel, Store, Task, TaskKind, ToolRegistry,
    classify_risk, transition,
)


def make_task(state="Received", risk=RiskLevel.LOW, scopes=None, tool="echo"):
    return Task(
        id="t1", objective="do a thing", kind=TaskKind.TASK,
        risk=risk, tool=tool, required_scopes=set(scopes or []), state=state,
    )


def test_state_machine():
    t = make_task()
    for state in ("Understood", "Planned", "PolicyCheck", "Ready", "Executing", "Verifying", "Completed"):
        transition(t, state)
    assert t.state == "Completed"
    with pytest.raises(InvalidTransition):
        transition(t, "Ready")


def test_risk_classification():
    assert classify_risk("transfer money") == RiskLevel.CRITICAL
    assert classify_risk("send a message") == RiskLevel.HIGH
    assert classify_risk("create a reminder") == RiskLevel.MODERATE
    assert classify_risk("read weather") == RiskLevel.LOW


def test_policy_denies_missing_scope():
    policy = PolicyEngine(granted_scopes={"calendar.read"})
    decision = policy.decide(make_task(scopes=["calendar.write"]))
    assert not decision.allowed
    assert "calendar.write" in decision.reason


def test_policy_requires_approval_for_high_risk():
    policy = PolicyEngine(granted_scopes={"send"})
    decision = policy.decide(make_task(risk=RiskLevel.HIGH), tool="send")
    assert decision.allowed
    assert decision.requires_approval


def test_deny_all_fails_closed():
    assert not DenyAllPolicy().decide(make_task()).allowed


def test_approval_binding_and_replay():
    store = Store()
    gw = ApprovalGateway(store)
    a = gw.request("t1", "u1", "send", "send money", "bob", "hello", "100")
    ok, _ = gw.validate(a.id, "u1", "send", "send money", "bob", "hello", "100")
    assert not ok
    gw.grant(a.id)
    ok, reason = gw.validate(a.id, "u1", "send", "send money", "bob", "hello", "100")
    assert ok, reason
    ok, reason = gw.validate(a.id, "u1", "send", "send money", "eve", "hello", "100")
    assert not ok
    ok, reason = gw.validate(a.id, "u1", "send", "send money", "bob", "hello", "101")
    assert not ok
    gw.consume(a.id)
    ok, _ = gw.validate(a.id, "u1", "send", "send money", "bob", "hello", "100")
    assert not ok


def test_audit_hash_chain():
    store = Store()
    first = store.append_audit("workflow.created", "u1", "goal one")
    second = store.append_audit("task.completed", "u1", "goal one")
    assert second.prev_hash == first.hash
    assert len(store.audit()) == 2


def test_orchestrator_low_risk_completes():
    tools = ToolRegistry()
    tools.register("read", lambda: {"observed": "sunny", "verified": True}, RiskLevel.LOW)
    orchestrator = Orchestrator(tools=tools)
    wf = orchestrator.handle("read weather")
    assert wf.state == "Completed"
    assert wf.tasks[0].state == "Completed"
    assert wf.tasks[0].evidence[0].passed


def test_orchestrator_approval_then_resume():
    tools = ToolRegistry()
    tools.register("send", lambda: {"observed": "sent", "verified": True}, RiskLevel.CRITICAL)
    orchestrator = Orchestrator(tools=tools)
    wf = orchestrator.handle("send money")
    assert wf.state == "Failed"
    assert len(orchestrator.pending) == 1
    approval_id = next(iter(orchestrator.pending))
    wf = orchestrator.resume(wf.id, approval_id)
    assert wf.state == "Completed"
    assert wf.tasks[0].state == "Completed"
    assert orchestrator.store.approval(approval_id).used


def test_orchestrator_policy_block():
    orchestrator = Orchestrator(policy=DenyAllPolicy())
    wf = orchestrator.handle("read weather")
    assert wf.state == "Failed"
    assert "blocked" in wf.answer


def test_verification_requires_human_when_flagged():
    tools = ToolRegistry()
    tools.register("pay", lambda: {"observed": "unclear", "needs_human": True}, RiskLevel.LOW)
    orchestrator = Orchestrator(tools=tools)
    wf = orchestrator.handle("pay")
    assert wf.state == "Failed"
