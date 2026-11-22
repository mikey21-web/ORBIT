from __future__ import annotations

import time
from typing import Any, Callable

from .approval import ApprovalGateway
from .events import EventBus
from .gateway import ModelGateway
from .handover import Handover, HandoverManager
from .intent import IntentInterpreter
from .models import Approval, Principal, Task, Workflow, new_id
from .planner import Planner
from .policy import PolicyEngine
from .replanning import Replanner
from .state import TERMINAL, InvalidTransition, transition
from .store import Store
from .tools import ToolRegistry
from .verification import Verifier


class Orchestrator:
    def __init__(
        self,
        store: Store | None = None,
        tools: ToolRegistry | None = None,
        policy: PolicyEngine | None = None,
        approvals: ApprovalGateway | None = None,
        gateway: ModelGateway | None = None,
        planner: Planner | None = None,
        verifier: Verifier | None = None,
        principal: Principal | None = None,
        on_execute: Callable[[Task], Any] | None = None,
        auto_approve: bool = False,
    ) -> None:
        self.store = store or Store()
        self.events = EventBus(self.store)
        self.tools = tools or ToolRegistry()
        self.policy = policy or PolicyEngine()
        self.approvals = approvals or ApprovalGateway(self.store)
        self.gateway = gateway or ModelGateway()
        self.intent = IntentInterpreter(self.gateway)
        self.planner = planner or Planner(self.tools.names())
        self.verifier = verifier or Verifier()
        self.principal = principal or Principal(new_id("user"), "user", "local")
        self.on_execute = on_execute
        self.auto_approve = auto_approve
        self.workflows: dict[str, Workflow] = {}
        self.pending: dict[str, Approval] = {}
        self.handovers = HandoverManager()
        self.handed_off: dict[str, Handover] = {}
        self.replanner = Replanner()

    def handle(self, text: str, trace_id: str = "") -> Workflow:
        trace_id = trace_id or new_id("trace")
        intent = self.intent.interpret(text)
        tasks = self.planner.build(intent.objective, intent.kind)
        workflow = Workflow(id=new_id("wf"), goal=intent.objective, tasks=tasks, state="Received")
        self.workflows[workflow.id] = workflow
        self.store.append_audit("workflow.created", self.principal.id, intent.objective, trace_id)
        self.events.publish("workflow.created", workflow.id, trace_id, objective=intent.objective)

        answers: list[str] = []
        for task in tasks:
            self.store.save_task(task, workflow.id)
            for state in ("Understood", "Planned", "PolicyCheck"):
                self._advance(task, state, workflow, trace_id)

            decision = self.policy.decide(task)
            if not decision.allowed:
                self._fail(task, decision.reason, trace_id)
                answers.append(f"{task.objective}: blocked ({decision.reason})")
                continue

            approval = self.approvals.require(
                decision, task.id, self.principal.id, task.tool or "unknown",
                task.objective, task.objective, str(task.tool_args.get("amount", "")),
            )
            if approval is not None:
                self._advance(task, "AwaitingApproval", workflow, trace_id)
                self.pending[approval.id] = approval
                self.events.publish("approval.requested", task.id, trace_id, approval_id=approval.id)
                if self.auto_approve:
                    self.grant(approval.id)
                else:
                    task.error = "approval required"
                    self.store.save_task(task, workflow.id)
                    answers.append(f"{task.objective}: awaiting approval ({approval.id})")
                    continue

            self._advance(task, "Ready", workflow, trace_id)

            evidence = None
            needs_user = False
            for attempt in range(task.retry_limit + 1):
                self._advance(task, "Executing", workflow, trace_id)
                try:
                    result = self._execute(task)
                except Exception as exc:
                    result = {"observed": str(exc), "verified": False, "error": str(exc)}
                if isinstance(result, dict) and result.get("needs_user"):
                    handover = self.handovers.request(
                        task.id,
                        str(result.get("question", "I need your input to continue.")),
                        str(result.get("kind", "ask")),
                        {"objective": task.objective, "steps": result.get("steps", []),
                         "screen": result.get("screen", "")},
                    )
                    self.handed_off[task.id] = handover
                    self._advance(task, "Escalated", workflow, trace_id)
                    task.error = "handover required"
                    self.store.save_task(task, workflow.id)
                    self.events.publish(
                        "handover.requested", task.id, trace_id,
                        handover_id=handover.id, question=handover.question,
                    )
                    self.store.append_audit("handover.requested", self.principal.id, handover.question, trace_id)
                    answers.append(f"{task.objective}: needs your input ({handover.id})")
                    needs_user = True
                    break
                self._advance(task, "Verifying", workflow, trace_id)
                evidence = self.verifier.verify(task, result)
                self.store.save_evidence(evidence)
                self.events.publish(
                    "task.verified", task.id, trace_id,
                    passed=evidence.passed, method=evidence.method.value,
                )
                if evidence.passed:
                    break
                if attempt < task.retry_limit:
                    self._advance(task, "Recovering", workflow, trace_id)
                    self._advance(task, "Ready", workflow, trace_id)
            if needs_user:
                continue

            if approval is not None and approval.id in self.pending:
                ok, reason = self.approvals.validate(
                    approval.id, self.principal.id, task.tool or "unknown",
                    task.objective, task.objective, str(task.tool_args.get("amount", "")),
                )
                if not ok:
                    self._fail(task, reason, trace_id)
                    answers.append(f"{task.objective}: approval invalid ({reason})")
                    continue
                self.approvals.consume(approval.id)
                self.pending.pop(approval.id, None)

            if evidence is not None and evidence.passed:
                self._advance(task, "Completed", workflow, trace_id)
                answers.append(f"{task.objective}: done ({evidence.observed[:120]})")
            else:
                self._fail(task, "verification failed", trace_id)
                answers.append(f"{task.objective}: verification failed")

        workflow.answer = "\n".join(answers)
        workflow.state = "Completed" if all(t.state == "Completed" for t in tasks) else "Failed"
        self.events.publish("workflow.completed", workflow.id, trace_id, state=workflow.state)
        return workflow

    def grant(self, approval_id: str) -> None:
        self.approvals.grant(approval_id)
        approval = self.store.approval(approval_id)
        if approval is None:
            return
        task = self.store.get_task(approval.task_id)
        if task is not None and task.state == "AwaitingApproval":
            transition(task, "Ready")
            task.updated_at = time.strftime("%Y-%m-%dT%H:%M:%S")
            self.store.save_task(task)
            self.events.publish("approval.granted", task.id, "", approval_id=approval_id)

    def resume(self, workflow_id: str, approval_id: str) -> Workflow:
        workflow = self.workflows[workflow_id]
        approval = self.store.approval(approval_id)
        if approval is None:
            raise KeyError(approval_id)
        task = self.store.get_task(approval.task_id)
        if task is None:
            raise KeyError(approval.task_id)
        if not approval.granted:
            self.grant(approval_id)
            task = self.store.get_task(approval.task_id)

        ok, reason = self.approvals.validate(
            approval_id, self.principal.id, task.tool or "unknown",
            task.objective, task.objective, str(task.tool_args.get("amount", "")),
        )
        if not ok:
            self._fail(task, reason, "")
            self._refresh(workflow)
            return workflow

        evidence = None
        for attempt in range(task.retry_limit + 1):
            self._advance(task, "Executing", workflow, "")
            try:
                result = self._execute(task)
            except Exception as exc:
                result = {"observed": str(exc), "verified": False}
            self._advance(task, "Verifying", workflow, "")
            evidence = self.verifier.verify(task, result)
            self.store.save_evidence(evidence)
            if evidence.passed:
                break
            if attempt < task.retry_limit:
                self._advance(task, "Recovering", workflow, "")
                self._advance(task, "Ready", workflow, "")

        self.approvals.consume(approval_id)
        self.pending.pop(approval_id, None)
        if evidence is not None and evidence.passed:
            self._advance(task, "Completed", workflow, "")
        else:
            self._fail(task, "verification failed", "")
        self._refresh(workflow)
        return workflow

    def resume_handover(self, workflow_id: str, handover_id: str, answer: str) -> Workflow:
        workflow = self.workflows[workflow_id]
        handover = self.handovers.answer(handover_id, answer)
        task = self.store.get_task(handover.task_id)
        if task is None:
            raise KeyError(handover.task_id)
        task.inputs["user_answer"] = answer
        task.error = ""
        self.store.save_task(task, workflow.id)
        self._advance(task, "Ready", workflow, "")
        self.events.publish("handover.answered", task.id, "", handover_id=handover_id)
        self.store.append_audit("handover.answered", self.principal.id, answer[:120], "")

        self._advance(task, "Executing", workflow, "")
        result = self._execute(task)
        self._advance(task, "Verifying", workflow, "")
        evidence = self.verifier.verify(task, result)
        self.store.save_evidence(evidence)
        if evidence.passed:
            self._advance(task, "Completed", workflow, "")
        else:
            self._fail(task, "verification failed", "")
        self.handed_off.pop(task.id, None)
        self._refresh(workflow)
        return workflow

    def replan(self, workflow_id: str, task_id: str, reason: str = "verification failed") -> Workflow:
        workflow = self.workflows[workflow_id]
        original = self.store.get_task(task_id)
        if original is None:
            raise KeyError(task_id)
        replacement = self.replanner.alternative(original, reason)
        workflow.tasks.append(replacement)
        self.store.save_task(replacement, workflow.id)

        for state in ("Understood", "Planned", "PolicyCheck", "Ready", "Executing"):
            self._advance(replacement, state, workflow, "")
        try:
            result = self._execute(replacement)
        except Exception as exc:
            result = {"observed": str(exc), "verified": False}
        self._advance(replacement, "Verifying", workflow, "")
        evidence = self.verifier.verify(replacement, result)
        self.store.save_evidence(evidence)
        self.events.publish("task.replanned", replacement.id, "", reason=reason, passed=evidence.passed)
        if evidence.passed:
            self._advance(replacement, "Completed", workflow, "")
        else:
            self._fail(replacement, "replan verification failed", "")
        self._refresh(workflow)
        return workflow

    def cancel(self, workflow_id: str, reason: str = "user cancelled") -> Workflow:
        workflow = self.workflows[workflow_id]
        task_ids = {t.id for t in workflow.tasks}
        for task in workflow.tasks:
            saved = self.store.get_task(task.id)
            if saved is None or saved.state in TERMINAL:
                continue
            try:
                transition(saved, "Cancelled")
            except InvalidTransition:
                saved.state = "Cancelled"
            saved.error = reason
            self.store.save_task(saved)
            task.state = saved.state
            task.error = reason
            self.events.publish("task.cancelled", task.id, "", reason=reason)
            self.store.append_audit("task.cancelled", self.principal.id, f"{task.objective}: {reason}", "")
        for approval_id in list(self.pending):
            approval = self.store.approval(approval_id)
            if approval is None or approval.task_id not in task_ids:
                continue
            approval.granted = False
            approval.expires_at = 0
            self.store.save_approval(approval)
            self.pending.pop(approval_id, None)
        for task_id in task_ids:
            self.handed_off.pop(task_id, None)
        workflow.state = "Cancelled"
        workflow.answer = reason
        self.events.publish("workflow.cancelled", workflow.id, "", reason=reason)
        self.store.append_audit("workflow.cancelled", self.principal.id, reason, "")
        return workflow

    def _refresh(self, workflow: Workflow) -> None:
        done = []
        for task in workflow.tasks:
            saved = self.store.get_task(task.id)
            if saved is not None:
                task.state = saved.state
                task.error = saved.error
                task.inputs = saved.inputs
                task.outputs = saved.outputs
                done.append(task.state == "Completed")
        workflow.state = "Completed" if done and all(done) else "Failed"
        workflow.answer = f"{sum(done)}/{len(done)} tasks completed"

    def _execute(self, task: Task) -> Any:
        if self.on_execute is not None:
            return self.on_execute(task)
        if task.tool and self.tools.get(task.tool):
            return self.tools.invoke(task.tool, task.tool_args)
        return {"observed": task.objective, "verified": False, "needs_human": True}

    def _advance(self, task: Task, state: str, workflow: Workflow, trace_id: str) -> None:
        try:
            transition(task, state)
        except InvalidTransition as exc:
            self.store.append_audit("transition.invalid", self.principal.id, str(exc), trace_id)
            raise
        self.store.save_task(task, workflow.id)
        self.events.publish("task.state", task.id, trace_id, state=state)
        self.store.append_audit(f"task.{state.lower()}", self.principal.id, task.objective, trace_id)

    def _fail(self, task: Task, reason: str, trace_id: str) -> None:
        if task.state in TERMINAL:
            return
        try:
            transition(task, "Failed")
        except InvalidTransition:
            task.state = "Failed"
        task.error = reason
        self.store.save_task(task)
        self.events.publish("task.failed", task.id, trace_id, reason=reason)
        self.store.append_audit("task.failed", self.principal.id, f"{task.objective}: {reason}", trace_id)
