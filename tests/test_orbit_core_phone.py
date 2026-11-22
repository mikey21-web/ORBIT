from dataclasses import dataclass, field
from io import BytesIO

from PIL import Image

from orbit_core import Orchestrator, RiskLevel, ToolRegistry
from orbit_core.perception import observations_from_ui, redact_screenshot, sanitize_ui_tree
from orbit_core.phone_adapter import PhoneExecutor, as_callback, register_phone_tools

XML = """<hierarchy>
  <node text="Bluetooth" content-desc="toggle" bounds="[10,20][100,60]" clickable="true" secret="x"/>
  <node text="1234" resource-id="com.app/password" bounds="[0,0][10,10]"/>
  <node text="Settings" content-desc="open"/>
</hierarchy>"""


def test_sanitize_removes_sensitive_nodes_and_unknown_attrs():
    clean = sanitize_ui_tree(XML)
    assert "Bluetooth" in clean
    assert "Settings" in clean
    assert "1234" not in clean
    assert "secret=" not in clean


def test_sanitize_caps_nodes():
    nodes = "".join(f'<node text="n{i}"/>' for i in range(50))
    clean = sanitize_ui_tree(f"<hierarchy>{nodes}</hierarchy>", max_nodes=5)
    assert clean.count("text=") <= 5


def test_observations_have_bounds_and_trust():
    items = observations_from_ui(sanitize_ui_tree(XML))
    assert len(items) == 2
    assert items[0].bounds == (10, 20, 100, 60)
    assert items[0].trust == "untrusted_external_content"
    assert items[0].source == "android_screen"


def test_redact_masks_top_of_image():
    buf = BytesIO()
    Image.new("RGB", (100, 100), (255, 255, 255)).save(buf, format="PNG")
    out = redact_screenshot(buf.getvalue())
    image = Image.open(BytesIO(out)).convert("RGB")
    assert image.getpixel((50, 1)) == (0, 0, 0)
    assert image.getpixel((50, 90)) == (255, 255, 255)


@dataclass
class FakeStep:
    round: int
    kind: str
    action: str
    detail: str


@dataclass
class FakeResult:
    ok: bool
    answer: str
    steps: list = field(default_factory=list)
    rounds: int = 1
    used_op: str = "op"


class FakeAgent:
    def run(self, text):
        return FakeResult(True, f"done: {text}", [FakeStep(0, "macro", "op", "replayed")])


def test_phone_executor_produces_evidence():
    adapter = PhoneExecutor(FakeAgent())
    result = adapter.run("open bluetooth")
    assert result["verified"]
    assert result["observed"] == "done: open bluetooth"
    assert result["steps"][0]["kind"] == "macro"


def test_orchestrator_with_phone_executor_completes():
    adapter = PhoneExecutor(FakeAgent())
    orchestrator = Orchestrator(on_execute=as_callback(adapter))
    wf = orchestrator.handle("open bluetooth")
    assert wf.state == "Completed"
    assert wf.tasks[0].evidence[0].method.value == "ui_state"
    assert "done: open bluetooth" in wf.answer


def test_register_phone_tools_risks():
    registry = ToolRegistry()
    register_phone_tools(registry)
    assert registry.get("shell").risk == RiskLevel.HIGH
    assert registry.get("tap").risk == RiskLevel.LOW
    assert registry.get("macro").risk == RiskLevel.MODERATE
    assert len(registry.names()) >= 20
