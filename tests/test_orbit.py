from pathlib import Path

import orbit.server as server
from orbit.core.agent import AgentConfig, OrbitAgent
from orbit.redact import name_candidates

ROOT = Path(__file__).resolve().parents[1]


def test_shell_guard_blocks_chains_and_driving():
    agent = object.__new__(OrbitAgent)
    assert agent._shell_command_class("echo hi | sh") == "blocked"
    assert agent._shell_command_class("sh -c 'rm -rf /data'") == "blocked"
    assert agent._shell_command_class("ls\nrm -rf /data") == "blocked"
    assert agent._shell_command_class("pm install /data/app.apk") == "blocked"
    assert agent._shell_command_class("cat /proc/kcore") == "blocked"
    assert agent._shell_command_class("am force-stop com.example.app") == "driving"
    assert agent._shell_command_class("dumpsys activity top") == "ok"


def test_shell_guard_flags_risky_and_allows_reads():
    agent = object.__new__(OrbitAgent)
    assert agent._shell_command_class("find /sdcard -delete") == "risky"
    assert agent._shell_command_class("settings put global x 1") == "risky"
    assert agent._shell_command_class("pm list packages") == "ok"


def test_shell_is_off_by_default():
    assert AgentConfig().shell_enabled is False


def test_request_body_limit_is_bounded():
    assert server.MAX_BODY <= 4 * 1024 * 1024


def test_redaction_does_not_require_of_separator():
    found = name_candidates(["owner alex Pixel 8"], ["alex Pixel 8"])
    assert "owner" in found


def test_package_identity():
    gradle = (ROOT / "android-app/app/build.gradle.kts").read_text(encoding="utf-8")
    manifest = (ROOT / "android-app/app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
    strings = (ROOT / "android-app/app/src/main/res/values/strings.xml").read_text(encoding="utf-8")
    assert 'applicationId = "com.orbit.assistant"' in gradle
    assert 'namespace = "com.orbit.assistant"' in gradle
    assert "networkSecurityConfig" in manifest
    assert 'name="app_name">ORBIT<' in strings


def test_no_legacy_source_markers():
    banned = ("phone" + "agent", "phone" + "cli", "phone" + "go", "".join(["H", "K", "U", "D", "S"]), "text" + "text")
    for path in ROOT.rglob("*.py"):
        if ".git" in path.parts or "tests" in path.parts:
            continue
        text = path.read_text(encoding="utf-8", errors="ignore").lower()
        for token in banned:
            assert token.lower() not in text, f"{token} in {path}"
