from __future__ import annotations

import uuid
from dataclasses import dataclass, field
from datetime import datetime, timezone
from enum import Enum
from typing import Any


def new_id(prefix: str) -> str:
    return f"{prefix}_{uuid.uuid4().hex[:16]}"


def now() -> str:
    return datetime.now(timezone.utc).isoformat()


class RiskLevel(str, Enum):
    LOW = "low"
    MODERATE = "moderate"
    HIGH = "high"
    CRITICAL = "critical"


class TaskKind(str, Enum):
    GOAL = "goal"
    TASK = "task"
    QUESTION = "question"


class VerificationMethod(str, Enum):
    API_READBACK = "api_readback"
    UI_STATE = "ui_state"
    DATABASE_STATE = "database_state"
    FILE_HASH = "file_hash"
    EXTERNAL_ACK = "external_ack"
    HUMAN = "human"


@dataclass
class Principal:
    id: str
    kind: str
    name: str = ""
    scopes: set[str] = field(default_factory=set)


@dataclass
class Tool:
    id: str
    name: str
    description: str = ""
    risk: RiskLevel = RiskLevel.LOW
    input_schema: dict[str, Any] = field(default_factory=dict)


@dataclass
class Skill:
    id: str
    name: str
    version: str
    digest: str
    capabilities: list[str] = field(default_factory=list)
    tool_allowlist: set[str] = field(default_factory=set)
    egress_allowlist: set[str] = field(default_factory=set)
    scopes: set[str] = field(default_factory=set)


@dataclass
class Observation:
    id: str
    source: str
    kind: str
    text: str = ""
    bounds: tuple[int, int, int, int] | None = None
    confidence: float = 1.0
    trust: str = "untrusted_external_content"
    created_at: str = field(default_factory=now)


@dataclass
class Evidence:
    id: str
    task_id: str
    method: VerificationMethod
    claimed: str
    observed: str
    passed: bool
    created_at: str = field(default_factory=now)


@dataclass
class Approval:
    id: str
    task_id: str
    principal_id: str
    tool: str
    action: str
    target: str
    content_hash: str
    amount: str = ""
    expires_at: float = 0.0
    one_time_id: str = field(default_factory=lambda: new_id("use"))
    used: bool = False
    granted: bool = False
    created_at: str = field(default_factory=now)

    def binding(self) -> dict[str, str]:
        return {
            "principal": self.principal_id,
            "tool": self.tool,
            "action": self.action,
            "target": self.target,
            "content": self.content_hash,
            "amount": self.amount,
        }


@dataclass
class AuditEvent:
    id: str
    kind: str
    actor: str
    detail: str
    trace_id: str = ""
    prev_hash: str = ""
    hash: str = ""
    created_at: str = field(default_factory=now)


@dataclass
class Task:
    id: str
    objective: str
    kind: TaskKind = TaskKind.TASK
    parent_id: str = ""
    depends_on: list[str] = field(default_factory=list)
    capability: str = ""
    tool: str = ""
    tool_args: dict[str, Any] = field(default_factory=dict)
    inputs: dict[str, Any] = field(default_factory=dict)
    outputs: dict[str, Any] = field(default_factory=dict)
    required_scopes: set[str] = field(default_factory=set)
    risk: RiskLevel = RiskLevel.LOW
    state: str = "Received"
    budget: int = 10
    deadline: float = 0.0
    retry_limit: int = 1
    retries: int = 0
    evidence: list[Evidence] = field(default_factory=list)
    error: str = ""
    created_at: str = field(default_factory=now)
    updated_at: str = field(default_factory=now)


@dataclass
class Workflow:
    id: str
    goal: str
    tasks: list[Task] = field(default_factory=list)
    answer: str = ""
    state: str = "Received"
    created_at: str = field(default_factory=now)


@dataclass
class Memory:
    id: str
    kind: str
    content: str
    source: str
    confidence: float = 1.0
    scope: str = "user"
    expires_at: float = 0.0
    tombstone: bool = False
    created_at: str = field(default_factory=now)


@dataclass
class MemoryProposal:
    kind: str
    content: str
    source: str
    confidence: float = 1.0
    expires_at: float = 0.0
    scope: str = "user"


@dataclass
class MemoryResult:
    message: str
    used_ids: list[str] = field(default_factory=list)
