import json
import time

from reportlab.lib.pagesizes import A4
from reportlab.pdfgen import canvas

from orbit_core import (
    AutonomyEngine, Dashboard, DesktopRuntime, DocumentReader, Orchestrator,
    RiskLevel, Store, ToolRegistry, browser_extension_manifest, write_extension,
)


def test_document_parser_real_pdf(tmp_path):
    path = tmp_path / "doc.pdf"
    pdf = canvas.Canvas(str(path), pagesize=A4)
    pdf.drawString(72, 750, "ORBIT document test")
    pdf.showPage()
    pdf.save()
    reader = DocumentReader()
    items = reader.read_pdf(str(path))
    assert any("ORBIT" in item.text for item in items)
    assert items[0].bounds is not None
    assert reader.last_metadata["pages"] == 1
    assert json.loads(reader.to_json(items))[0]["text"]


def test_dashboard_renders_task_and_audit():
    store = Store()
    store.append_audit("workflow.created", "u1", "read weather")
    dashboard = Dashboard(store)
    html = dashboard.render()
    assert "ORBIT report" in html
    assert "read weather" in html
    assert "Benchmark" in html


def test_desktop_runtime_sandbox_and_extension(tmp_path):
    runtime = DesktopRuntime(str(tmp_path / "desk"))
    runtime.write("notes/a.txt", "hello")
    assert runtime.read("notes/a.txt") == "hello"
    assert "notes" in runtime.list()
    assert "a.txt" in runtime.list("notes")
    try:
        runtime.path("../escape.txt")
        assert False
    except PermissionError:
        pass
    root = write_extension(str(tmp_path / "ext"))
    manifest = json.loads(DesktopRuntime(root).read("manifest.json"))
    assert manifest["manifest_version"] == 3
    assert browser_extension_manifest()["permissions"] == ["activeTab", "scripting"]


def test_autonomy_goals_suggestions_and_delegation():
    tools = ToolRegistry()
    tools.register("read", lambda: {"observed": "ok", "verified": True}, RiskLevel.LOW)
    engine = AutonomyEngine(Orchestrator(tools=tools))
    goal = engine.add_goal("finish the report", deadline=time.time() + 3600)
    suggestions = engine.suggest()
    assert suggestions
    assert any("report" in item.action for item in suggestions)
    engine.advance(goal.id, "drafted outline")
    assert goal.progress == ["drafted outline"]
    helpers = [Orchestrator(tools=tools), Orchestrator(tools=tools)]
    answers = engine.delegate("finish the report", helpers)
    assert len(answers) == 2
    assert all(isinstance(answer, str) for answer in answers)
