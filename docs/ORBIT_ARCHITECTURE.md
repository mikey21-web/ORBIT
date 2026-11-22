# ORBIT architecture (target)

ORBIT is a local-first, multimodal, event-driven, permissioned personal-agent platform.

## Five independent systems

| System | Owns | Code |
| --- | --- | --- |
| Intelligence | understanding, planning, decisions | `orbit_core/intent.py`, `planner.py`, `gateway.py` |
| Delegation | task graph, agents, budgets, recovery | `orbit_core/orchestrator.py` |
| Authority | permissions, approvals, policy | `orbit_core/policy.py`, `approval.py` |
| Execution | tools, phone/API/browser adapters | `orbit_core/tools.py`, `orbit/agent.py` |
| Verification | evidence, read-back, proof | `orbit_core/verification.py` |

The LLM is never the authority for its own permissions. Deterministic code decides policy,
approval validity, state transitions, and verification.

## Request lifecycle

```
User goal
  -> IntentInterpreter (goal / task / question)
  -> Planner (task graph with dependencies)
  -> PolicyEngine (fail-closed, scopes, tool allowlist, risk)
  -> ApprovalGateway (exact-action binding for high/critical actions)
  -> ToolRegistry / executor
  -> Verifier (API read-back, UI state, DB state, file hash, human)
  -> Audit + events + memory update
  -> Answer with evidence
```

## Task states

`Received -> Understood -> Planned -> PolicyCheck -> AwaitingApproval | Ready -> Executing -> Verifying -> Completed | Recovering | Failed | Escalated | Cancelled`

Transitions are enforced in `orbit_core/state.py`; illegal transitions raise.

## Risk classes

| Risk | Example | Default |
| --- | --- | --- |
| low | read weather, summarise | execute if permitted |
| moderate | create reminder, organise file | execute within scope |
| high | send message, change settings | contextual approval |
| critical | transfer money, purchase, delete | explicit confirmation immediately before execution |

Approvals bind to principal, device, action, target, content hash, amount, expiry and a
one-time use id. Any parameter change invalidates the approval; replay is rejected.

## Data model

`orbit_core/models.py` defines the shared entities: Principal, Tool, Skill, Observation,
Evidence, Approval, AuditEvent, Task, Workflow, Memory. Every claim has provenance and
time; every important action has evidence.

## Current implementation status

Implemented now (Phase 1 + Phase 2 core):
- typed universal entities
- task state machine with enforced transitions
- SQLite durable store (tasks, events, approvals, evidence, hash-chained audit)
- event bus with idempotency keys
- risk classifier, fail-closed policy engine, tool registry
- exact-action approval gateway with invalidation and one-time use
- model gateway with local/cloud routing hooks
- intent interpreter, task-graph planner, evidence verifier
- orchestrator with approval pause/resume and verification-driven completion
- 11 unit tests, demo CLI (`python -m orbit_core.cli --demo`)

Also implemented: phone executor adapter, UI tree sanitization and screenshot redaction,
CaMeL-style injection defenses, memory with provenance and hard erasure, knowledge graph,
local retrieval, scheduler, secrets, replanning, guardrails, benchmark harness, browser
adapter with egress control, scoped MCP bridge, privacy dashboard, and 19 capability
domains with keyword routing.

Not yet implemented: Android keystore/Binder IPC hardening, on-device voice and offline
models, OCR/document pipelines, external service integrations (calendar/mail/maps/payments),
smart glasses, and hardware-backed live-device verification. See the tracked todo list.

## Hard limits

Android permissions, background restrictions, app APIs, FLAG_SECURE screens, and hardware
mean not every capability works universally. Some need integrations; some need user
interaction. The phone must stay useful when the cloud is unavailable.
