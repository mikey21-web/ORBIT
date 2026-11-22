from orbit_core import (
    Orchestrator, Planner, PolicyEngine, RiskLevel, ToolRegistry,
)


def needs_user(question="I need your input to continue.", steps=None):
    return {"needs_user": True, "question": question, "kind": "ask",
            "steps": steps or [], "screen": "captcha screen"}


def done(answer):
    return {"observed": answer, "verified": True, "ok": True, "method": "ui_state"}


def test_handover_pauses_with_context():
    orchestrator = Orchestrator(on_execute=lambda task: needs_user())
    wf = orchestrator.handle("solve captcha")
    assert wf.state == "Failed"
    assert wf.tasks[0].state == "Escalated"
    pending = orchestrator.handovers.pending()
    assert len(pending) == 1
    assert pending[0].question == "I need your input to continue."
    assert pending[0].context["screen"] == "captcha screen"


def test_handover_resume_preserves_answer_and_completes():
    calls = {"n": 0}

    def execute(task):
        calls["n"] += 1
        if calls["n"] == 1:
            return needs_user("Enter the code")
        return done("completed after user answer")

    orchestrator = Orchestrator(on_execute=execute)
    wf = orchestrator.handle("solve captcha")
    handover = orchestrator.handovers.pending()[0]
    assert handover.id in [h.id for h in orchestrator.handed_off.values()]

    wf = orchestrator.resume_handover(wf.id, handover.id, "the code is 42")
    assert wf.state == "Completed"
    assert wf.tasks[0].state == "Completed"
    assert wf.tasks[0].inputs["user_answer"] == "the code is 42"
    assert orchestrator.handovers.get(handover.id).answered


def test_handover_audit_records_answer():
    orchestrator = Orchestrator(on_execute=lambda task: needs_user("Enter OTP"))
    wf = orchestrator.handle("verify login")
    handover_id = orchestrator.handovers.pending()[0].id
    orchestrator.resume_handover(wf.id, handover_id, "123456")
    kinds = [entry["kind"] for entry in orchestrator.store.audit()]
    assert "handover.requested" in kinds
    assert "handover.answered" in kinds


def test_emergency_stop_cancels_task_and_revokes_approval():
    tools = ToolRegistry()
    tools.register('send', lambda: {'observed': 'sent', 'verified': True}, RiskLevel.CRITICAL)
    orchestrator = Orchestrator(
        tools=tools,
        planner=Planner(['send']),
        policy=PolicyEngine(granted_scopes={'send'}),
    )
    wf = orchestrator.handle('send money')
    approval_id = next(iter(orchestrator.pending))
    wf = orchestrator.cancel(wf.id)
    assert wf.state == 'Cancelled'
    assert wf.tasks[0].state == 'Cancelled'
    approval = orchestrator.store.approval(approval_id)
    assert not approval.granted
    assert approval.expires_at == 0
    kinds = [entry['kind'] for entry in orchestrator.store.audit()]
    assert 'workflow.cancelled' in kinds
