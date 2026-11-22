from __future__ import annotations

import hashlib
import time

from .models import Approval, RiskLevel, new_id
from .policy import Decision
from .store import Store


class ApprovalGateway:
    def __init__(self, store: Store, timeout: float = 120.0) -> None:
        self.store = store
        self.timeout = timeout

    @staticmethod
    def content_hash(content: str) -> str:
        return hashlib.sha256(content.encode()).hexdigest()

    def request(self, task_id: str, principal_id: str, tool: str, action: str,
                target: str, content: str = "", amount: str = "") -> Approval:
        approval = Approval(
            id=new_id("apr"), task_id=task_id, principal_id=principal_id,
            tool=tool, action=action, target=target,
            content_hash=self.content_hash(content), amount=amount,
            expires_at=time.time() + self.timeout,
        )
        self.store.save_approval(approval)
        return approval

    def validate(self, approval_id: str, principal_id: str, tool: str, action: str,
                 target: str, content: str = "", amount: str = "") -> tuple[bool, str]:
        approval = self.store.approval(approval_id)
        if approval is None:
            return False, "unknown approval"
        if approval.used:
            return False, "approval already used"
        if not approval.granted:
            return False, "approval not granted"
        if time.time() > approval.expires_at:
            return False, "approval expired"
        expected = {
            "principal": principal_id, "tool": tool, "action": action,
            "target": target, "content": self.content_hash(content), "amount": amount,
        }
        if approval.binding() != expected:
            return False, "approval binding does not match the requested action"
        return True, "valid"

    def grant(self, approval_id: str) -> None:
        approval = self.store.approval(approval_id)
        if approval is None:
            raise KeyError(approval_id)
        approval.granted = True
        self.store.save_approval(approval)

    def consume(self, approval_id: str) -> None:
        approval = self.store.approval(approval_id)
        if approval is None:
            raise KeyError(approval_id)
        approval.used = True
        self.store.save_approval(approval)

    def require(self, decision: Decision, task_id: str, principal_id: str, tool: str,
                action: str, target: str, content: str = "", amount: str = "") -> Approval | None:
        if not decision.requires_approval:
            return None
        if decision.risk == RiskLevel.CRITICAL:
            return self.request(task_id, principal_id, tool, action, target, content, amount)
        return None
