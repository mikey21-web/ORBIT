import hashlib
from pathlib import Path

from orbit import redact
from orbit.server import Handler

ROOT = Path(__file__).resolve().parents[1]


class FakeHandler:
    def __init__(self, headers):
        self.headers = headers
        self.path = "/task"


def test_token_accepted_only_from_header():
    assert Handler._authorized(FakeHandler({"X-Agent-Token": "secret"}), "secret") is True
    fake = FakeHandler({})
    fake.path = "/task?token=secret"
    assert Handler._authorized(fake, "secret") is False


def test_phone_pattern_matches_unmasked_number():
    assert redact.PHONE_RE.search("call 13800138000 now")


def test_network_security_config_scopes_cleartext():
    path = ROOT / "android-app/app/src/main/res/xml/network_security_config.xml"
    text = path.read_text(encoding="utf-8")
    assert 'cleartextTrafficPermitted="false"' in text
    assert "127.0.0.1" in text


def test_manifest_declares_no_package_attribute():
    text = (ROOT / "android-app/app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
    assert "networkSecurityConfig" in text
    assert "package=" not in text


def test_gradle_wrapper_is_8_9():
    text = (ROOT / "android-app/gradle/wrapper/gradle-wrapper.properties").read_text(encoding="utf-8")
    assert "gradle-8.9-bin.zip" in text


def test_helper_jar_hash_matches_record():
    jar = ROOT / "android-app/app/src/main/assets/pa-helper.jar"
    recorded = (ROOT / "android-app/app/src/main/assets/pa-helper.sha256").read_text(encoding="utf-8").strip()
    assert hashlib.sha256(jar.read_bytes()).hexdigest() == recorded


def test_confirmation_prompt_is_readable():
    text = (ROOT / "orbit/core/agent.py").read_text(encoding="utf-8")
    assert "This command will change the phone" in text


def test_tracked_sources_have_no_cjk():
    excluded = {".git", "build", ".gradle", "__pycache__", ".pytest_cache"}
    for path in ROOT.rglob("*"):
        if not path.is_file() or any(part in excluded for part in path.parts):
            continue
        if path.suffix.lower() not in {".py", ".kt", ".java", ".xml", ".yaml", ".kts", ".md"}:
            continue
        text = path.read_text(encoding="utf-8", errors="ignore")
        assert not any("\u4e00" <= char <= "\u9fff" for char in text), path


def test_shipped_map_builds_operations_with_empty_label():
    from orbit.core.app_map import AppMap

    path = ROOT / "android-app/app/src/main/assets/maps/settings.yaml"
    assert AppMap(path).build_operations()
