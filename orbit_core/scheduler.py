from __future__ import annotations

import time

from .models import new_id
from .store import Store, now_iso


class TaskScheduler:
    def __init__(self, store: Store) -> None:
        self.store = store

    def schedule(self, task_id: str, delay: float = 0.0, deadline: float = 0.0) -> None:
        self.store.conn().execute(
            "INSERT OR REPLACE INTO scheduled VALUES (?,?,?,?,?)",
            (task_id, time.time() + max(0.0, delay), deadline, "scheduled", now_iso()),
        )
        self.store.conn().commit()

    def due(self, limit: int = 20) -> list[str]:
        rows = self.store.conn().execute(
            "SELECT task_id FROM scheduled WHERE state='scheduled' AND run_at<=? ORDER BY run_at LIMIT ?",
            (time.time(), limit),
        ).fetchall()
        return [row["task_id"] for row in rows]

    def mark_running(self, task_id: str) -> None:
        self.store.conn().execute(
            "UPDATE scheduled SET state='running' WHERE task_id=?", (task_id,),
        )
        self.store.conn().commit()

    def pause(self, task_id: str) -> None:
        self.store.conn().execute(
            "UPDATE scheduled SET state='paused' WHERE task_id=?", (task_id,),
        )
        self.store.conn().commit()

    def resume(self, task_id: str, delay: float = 0.0) -> None:
        self.store.conn().execute(
            "UPDATE scheduled SET state='scheduled', run_at=? WHERE task_id=?",
            (time.time() + max(0.0, delay), task_id),
        )
        self.store.conn().commit()

    def cancel(self, task_id: str) -> None:
        self.store.conn().execute(
            "UPDATE scheduled SET state='cancelled' WHERE task_id=?", (task_id,),
        )
        self.store.conn().commit()

    def complete(self, task_id: str) -> None:
        self.store.conn().execute(
            "UPDATE scheduled SET state='completed' WHERE task_id=?", (task_id,),
        )
        self.store.conn().commit()

    def state(self, task_id: str) -> str:
        row = self.store.conn().execute(
            "SELECT state FROM scheduled WHERE task_id=?", (task_id,),
        ).fetchone()
        return row["state"] if row else "unknown"

    def expired(self, task_id: str) -> bool:
        row = self.store.conn().execute(
            "SELECT deadline FROM scheduled WHERE task_id=?", (task_id,),
        ).fetchone()
        if row is None or not row["deadline"]:
            return False
        return time.time() > row["deadline"]

    @staticmethod
    def new_run_id() -> str:
        return new_id("run")
