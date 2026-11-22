from orbit_core import Orchestrator, Replanner, UncertaintyEvaluator


def test_uncertainty_detects_hedging():
    score = UncertaintyEvaluator().score("maybe find something somewhere")
    assert score.score > 0
    assert score.reasons


def test_uncertainty_low_for_clear_task():
    score = UncertaintyEvaluator().score("open bluetooth settings")
    assert score.score == 0


def test_replanner_produces_dependent_task():
    orchestrator = Orchestrator(on_execute=lambda task: {"observed": "ok", "verified": True})
    wf = orchestrator.handle("open bluetooth")
    original = wf.tasks[0]
    replacement = Replanner().alternative(original, "landed on wrong screen")
    assert replacement.depends_on == [original.id]
    assert "Alternative" in replacement.objective
    assert replacement.inputs["retry_reason"] == "landed on wrong screen"


def test_orchestrator_replan_recovers():
    calls = {"n": 0}

    def execute(task):
        calls["n"] += 1
        if calls["n"] == 1:
            return {"observed": "wrong screen", "verified": False}
        return {"observed": "recovered", "verified": True}

    orchestrator = Orchestrator(on_execute=execute)
    wf = orchestrator.handle("open bluetooth")
    failed = wf.tasks[0]
    wf = orchestrator.replan(wf.id, failed.id, "wrong screen")
    assert len(wf.tasks) == 2
    assert wf.tasks[1].state == "Completed"
    assert wf.tasks[1].depends_on == [failed.id]
    events = orchestrator.store.events()
    assert any(e["kind"] == "task.replanned" for e in events)
