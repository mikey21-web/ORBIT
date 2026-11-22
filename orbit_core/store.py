from __future__ import annotations

import hashlib
import json
import sqlite3
import threading
from typing import Any

from .models import Approval, AuditEvent, Evidence, Task


class Store:
    def __init__(self, path: str = ":memory:") -> None:
        self.path = path
        self._local = threading.local()
        self._init()

    def conn(self) -> sqlite3.Connection:
        conn = getattr(self._local, "conn", None)
        if conn is None:
            conn = sqlite3.connect(self.path, check_same_thread=False)
            conn.row_factory = sqlite3.Row
            conn.execute("PRAGMA journal_mode=WAL")
            self._local.conn = conn
        return conn

    def _init(self) -> None:
        c = self.conn()
        c.executescript(
            """
            CREATE TABLE IF NOT EXISTS tasks (
                id TEXT PRIMARY KEY, workflow TEXT, objective TEXT, kind TEXT,
                parent_id TEXT, depends_on TEXT, capability TEXT, tool TEXT,
                tool_args TEXT, inputs TEXT, outputs TEXT, scopes TEXT,
                risk TEXT, state TEXT, budget INTEGER, deadline REAL,
                retries INTEGER, error TEXT, created_at TEXT, updated_at TEXT
            );
            CREATE TABLE IF NOT EXISTS events (
                seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT UNIQUE, kind TEXT,
                task_id TEXT, trace_id TEXT, payload TEXT, created_at TEXT
            );
            CREATE TABLE IF NOT EXISTS approvals (
                id TEXT PRIMARY KEY, task_id TEXT, principal_id TEXT, tool TEXT,
                action TEXT, target TEXT, content_hash TEXT, amount TEXT,
                expires_at REAL, one_time_id TEXT, used INTEGER, granted INTEGER,
                binding TEXT, created_at TEXT
            );
            CREATE TABLE IF NOT EXISTS evidence (
                id TEXT PRIMARY KEY, task_id TEXT, method TEXT, claimed TEXT,
                observed TEXT, passed INTEGER, created_at TEXT
            );
            CREATE TABLE IF NOT EXISTS audit (
                seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT, kind TEXT,
                actor TEXT, detail TEXT, trace_id TEXT, prev_hash TEXT,
                hash TEXT, created_at TEXT
            );
            CREATE TABLE IF NOT EXISTS scheduled (
                task_id TEXT PRIMARY KEY, run_at REAL, deadline REAL,
                state TEXT, created_at TEXT
            );
            """
        )
        c.commit()

    def save_task(self, t: Task, workflow: str = "") -> None:
        self.conn().execute(
            "INSERT OR REPLACE INTO tasks VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            (
                t.id, workflow, t.objective, t.kind.value, t.parent_id,
                json.dumps(t.depends_on), t.capability, t.tool,
                json.dumps(t.tool_args), json.dumps(t.inputs), json.dumps(t.outputs),
                json.dumps(sorted(t.required_scopes)), t.risk.value, t.state,
                t.budget, t.deadline, t.retries, t.error, t.created_at, t.updated_at,
            ),
        )
        self.conn().commit()

    def get_task(self, task_id: str) -> Task | None:
        row = self.conn().execute("SELECT * FROM tasks WHERE id=?", (task_id,)).fetchone()
        if row is None:
            return None
        return Task(
            id=row["id"], objective=row["objective"],
            parent_id=row["parent_id"] or "",
            depends_on=json.loads(row["depends_on"] or "[]"),
            capability=row["capability"] or "", tool=row["tool"] or "",
            tool_args=json.loads(row["tool_args"] or "{}"),
            inputs=json.loads(row["inputs"] or "{}"),
            outputs=json.loads(row["outputs"] or "{}"),
            required_scopes=set(json.loads(row["scopes"] or "[]")),
            state=row["state"], budget=row["budget"], deadline=row["deadline"] or 0.0,
            retries=row["retries"], error=row["error"] or "",
            created_at=row["created_at"], updated_at=row["updated_at"],
        )

    def publish(self, event_id: str, kind: str, task_id: str, trace_id: str, payload: dict[str, Any]) -> None:
        self.conn().execute(
            "INSERT OR IGNORE INTO events (id,kind,task_id,trace_id,payload,created_at) VALUES (?,?,?,?,?,?)",
            (event_id, kind, task_id, trace_id, json.dumps(payload, default=str), now_iso()),
        )
        self.conn().commit()

    def events(self, task_id: str = "") -> list[dict[str, Any]]:
        if task_id:
            rows = self.conn().execute("SELECT * FROM events WHERE task_id=? ORDER BY seq", (task_id,)).fetchall()
        else:
            rows = self.conn().execute("SELECT * FROM events ORDER BY seq").fetchall()
        return [dict(r) for r in rows]

    def save_approval(self, a: Approval) -> None:
        self.conn().execute(
            "INSERT OR REPLACE INTO approvals VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            (a.id, a.task_id, a.principal_id, a.tool, a.action, a.target,
             a.content_hash, a.amount, a.expires_at, a.one_time_id,
             int(a.used), int(a.granted), json.dumps(a.binding()), a.created_at),
        )
        self.conn().commit()

    def approval(self, approval_id: str) -> Approval | None:
        row = self.conn().execute("SELECT * FROM approvals WHERE id=?", (approval_id,)).fetchone()
        if row is None:
            return None
        return Approval(
            id=row["id"], task_id=row["task_id"], principal_id=row["principal_id"],
            tool=row["tool"], action=row["action"], target=row["target"],
            content_hash=row["content_hash"], amount=row["amount"] or "",
            expires_at=row["expires_at"] or 0.0, one_time_id=row["one_time_id"],
            used=bool(row["used"]), granted=bool(row["granted"]),
            created_at=row["created_at"],
        )

    def save_evidence(self, e: Evidence) -> None:
        self.conn().execute(
            "INSERT OR REPLACE INTO evidence VALUES (?,?,?,?,?,?,?)",
            (e.id, e.task_id, e.method.value, e.claimed, e.observed, int(e.passed), e.created_at),
        )
        self.conn().commit()

    def append_audit(self, kind: str, actor: str, detail: str, trace_id: str = "") -> AuditEvent:
        c = self.conn()
        last = c.execute("SELECT hash FROM audit ORDER BY seq DESC LIMIT 1").fetchone()
        prev = last["hash"] if last else ""
        raw = f"{prev}|{kind}|{actor}|{detail}|{trace_id}"
        digest = hashlib.sha256(raw.encode()).hexdigest()
        item = AuditEvent(
            id=hashlib.sha256(f"{digest}{now_iso()}".encode()).hexdigest()[:24],
            kind=kind, actor=actor, detail=detail, trace_id=trace_id,
            prev_hash=prev, hash=digest,
        )
        c.execute(
            "INSERT INTO audit (id,kind,actor,detail,trace_id,prev_hash,hash,created_at) VALUES (?,?,?,?,?,?,?,?)",
            (item.id, kind, actor, detail, trace_id, prev, digest, item.created_at),
        )
        c.commit()
        return item

    def audit(self, limit: int = 100) -> list[dict[str, Any]]:
        rows = self.conn().execute("SELECT * FROM audit ORDER BY seq DESC LIMIT ?", (limit,)).fetchall()
        return [dict(r) for r in rows]


def now_iso() -> str:
    from datetime import datetime, timezone
    return datetime.now(timezone.utc).isoformat()
