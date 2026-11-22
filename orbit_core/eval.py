from __future__ import annotations

from dataclasses import dataclass, field

from .orchestrator import Orchestrator


@dataclass
class Case:
    id: str
    task: str
    expected_state: str = "Completed"
    answer_contains: str = ""
    expect_approval: bool = False
    expect_audit_kind: str = ""


@dataclass
class CaseResult:
    case_id: str
    passed: bool
    detail: str = ""


@dataclass
class Benchmark:
    name: str = "orbit-core"
    cases: list[Case] = field(default_factory=list)
    results: list[CaseResult] = field(default_factory=list)

    def add(self, case: Case) -> None:
        self.cases.append(case)

    def run(self, orchestrator: Orchestrator) -> list[CaseResult]:
        self.results = []
        for case in self.cases:
            wf = orchestrator.handle(case.task)
            passed = wf.state == case.expected_state
            detail = f"state={wf.state}"
            if case.answer_contains and case.answer_contains not in wf.answer:
                passed = False
                detail += " answer mismatch"
            if case.expect_approval and not orchestrator.pending:
                passed = False
                detail += " no approval"
            if case.expect_audit_kind:
                kinds = [e["kind"] for e in orchestrator.store.audit()]
                if case.expect_audit_kind not in kinds:
                    passed = False
                    detail += " audit missing"
            self.results.append(CaseResult(case.id, passed, detail))
        return self.results

    @property
    def pass_rate(self) -> float:
        if not self.results:
            return 0.0
        return sum(1 for r in self.results if r.passed) / len(self.results)

    def meets(self, threshold: float) -> bool:
        return self.pass_rate >= threshold
