from __future__ import annotations

from typing import Any

from .models import Evidence, Task, VerificationMethod, new_id


class Verifier:
    def verify(self, task: Task, result: Any) -> Evidence:
        if isinstance(result, dict):
            method_name = result.get("method", "ui_state")
            try:
                method = VerificationMethod(method_name)
            except ValueError:
                method = VerificationMethod.UI_STATE
            observed = str(result.get("observed", result))
            passed = bool(result.get("verified", result.get("ok", True)))
            if result.get("needs_human"):
                method = VerificationMethod.HUMAN
                passed = False
        else:
            method = VerificationMethod.EXTERNAL_ACK
            observed = str(result)
            passed = result is not None
        evidence = Evidence(
            id=new_id("ev"), task_id=task.id, method=method,
            claimed=task.objective, observed=observed, passed=passed,
        )
        task.evidence.append(evidence)
        return evidence
