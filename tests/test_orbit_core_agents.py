import pytest

from orbit_core import (
    BrowserAdapter, MCPBridge, MockBrowser,
    MemoryProposal, MemoryStore, PrivacyDashboard, SpecialistRegistry,
)


def test_browser_open_and_read():
    backend = MockBrowser()
    adapter = BrowserAdapter(backend)
    opened = adapter.open("https://example.com")
    assert opened["verified"]
    assert backend.url == "https://example.com"
    assert "example.com" in adapter.read()["observed"]


def test_browser_egress_denied():
    adapter = BrowserAdapter(MockBrowser(), egress_allowlist={"trusted.example"})
    result = adapter.open("https://evil.example")
    assert not result["verified"]
    assert result["error"] == "domain not allowed"


def test_browser_write_requires_approval_for_payment():
    adapter = BrowserAdapter(MockBrowser())
    result = adapter.click("Confirm payment of 5000")
    assert result["requires_approval"]


def test_mcp_scoped_tools():
    bridge = MCPBridge()
    bridge.register("read", lambda: "ok", scopes={"read"})
    bridge.register("send", lambda: "sent", scopes={"send"})
    token = bridge.token("u1", {"read"})
    names = [t["name"] for t in bridge.list_tools(token)]
    assert names == ["read"]
    assert bridge.call("read", {}, token) == "ok"
    with pytest.raises(PermissionError):
        bridge.call("send", {}, token)


def test_mcp_one_time_token():
    bridge = MCPBridge()
    bridge.register("pay", lambda amount: amount, scopes={"pay"})
    token = bridge.token("u1", {"pay"}, one_time=True)
    assert bridge.call("pay", {"amount": 10}, token) == 10
    with pytest.raises(PermissionError):
        bridge.call("pay", {"amount": 10}, token)


def test_specialist_routing():
    registry = SpecialistRegistry()
    assert registry.resolve("schedule a meeting with Rahul").name == "secretary"
    assert registry.resolve("draft a reply to Priya").name == "communication"
    assert registry.resolve("research competitor pricing").name == "research"
    result = registry.run("summarise this PDF")
    assert result.specialist == "productivity"
    assert len(result.tasks) >= 2
    assert all(t.required_scopes for t in result.tasks)


def test_all_capability_domains_registered():
    registry = SpecialistRegistry()
    assert len(registry._items) == 19
    expected = {
        "finance": "budget for this month",
        "coding": "review this pull request",
        "learning": "quiz me on python",
        "travel": "plan a trip to Delhi",
        "shopping": "compare prices for this phone",
        "health": "track my workout",
        "smart_home": "turn on the lights",
        "entertainment": "find a movie to watch",
        "identity": "review my account permissions",
        "life": "show my commitments",
        "digital_ops": "resume the background workflow",
        "agent_network": "delegate this to another agent",
        "ecosystem": "sync my devices",
        "experimental": "predict my next decision",
        "proactive": "suggest actions based on my routine",
    }
    for name, goal in expected.items():
        assert registry.resolve(goal).name == name
        result = registry.run(goal)
        assert result.tasks


def test_privacy_dashboard_tracks_disclosures_and_deletion():
    memory = MemoryStore()
    memory.add(MemoryProposal("fact", "user likes tea", "chat"))
    dashboard = PrivacyDashboard(memory)
    dashboard.record("cloud-vlm", "screenshot", "redacted frame")
    dashboard.allow("cloud-vlm")
    assert dashboard.permitted("cloud-vlm")
    assert len(dashboard.report()["memories"]) == 1
    assert dashboard.json().find("cloud-vlm") >= 0
    memory.delete(next(iter(memory.all())).id)
    assert dashboard.report()["memories"] == []
