from __future__ import annotations

import html

from .eval import Benchmark
from .privacy import PrivacyDashboard
from .store import Store


class Dashboard:
    def __init__(self, store: Store, privacy: PrivacyDashboard | None = None,
                 benchmark: Benchmark | None = None) -> None:
        self.store = store
        self.privacy = privacy or PrivacyDashboard()
        self.benchmark = benchmark or Benchmark()

    def render(self) -> str:
        tasks = self.store.conn().execute(
            "SELECT id, objective, state, risk, error FROM tasks ORDER BY created_at DESC LIMIT 50"
        ).fetchall()
        audit = self.store.audit(limit=20)
        rows = "".join(
            f"<tr><td>{html.escape(str(row['id']))}</td><td>{html.escape(str(row['objective']))}</td>"
            f"<td>{html.escape(str(row['state']))}</td><td>{html.escape(str(row['risk']))}</td>"
            f"<td>{html.escape(str(row['error'] or ''))}</td></tr>"
            for row in tasks
        )
        events = "".join(
            f"<li>{html.escape(str(row['kind']))} — {html.escape(str(row['actor']))}: "
            f"{html.escape(str(row['detail']))}</li>"
            for row in audit
        )
        return f"""<!doctype html><html><head><meta charset="utf-8"><title>ORBIT report</title>
<style>body{{font-family:system-ui;background:#f5f4ef;color:#1f1e1d;margin:2rem}}
table{{border-collapse:collapse;width:100%}}td,th{{border-bottom:1px solid #ddd;padding:.5rem;text-align:left}}
code{{background:#ece9e2;padding:.15rem .3rem}}</style></head><body>
<h1>ORBIT report</h1><h2>Tasks</h2><table><tr><th>ID</th><th>Objective</th><th>State</th><th>Risk</th><th>Error</th></tr>{rows}</table>
<h2>Audit</h2><ul>{events}</ul>
<h2>Benchmark</h2><p>pass rate: <code>{self.benchmark.pass_rate:.0%}</code></p>
<h2>Disclosures</h2><pre>{html.escape(self.privacy.json())}</pre></body></html>"""
