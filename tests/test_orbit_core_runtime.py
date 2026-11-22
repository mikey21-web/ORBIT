import time

from orbit_core import (
    EncryptedFileBackend, MemoryBackend, SecretStore, Store, TaskScheduler,
)


def test_schedule_and_due():
    scheduler = TaskScheduler(Store())
    scheduler.schedule("t1", delay=0)
    assert "t1" in scheduler.due()


def test_future_task_not_due():
    scheduler = TaskScheduler(Store())
    scheduler.schedule("t2", delay=3600)
    assert "t2" not in scheduler.due()


def test_pause_resume_cancel():
    scheduler = TaskScheduler(Store())
    scheduler.schedule("t3")
    scheduler.mark_running("t3")
    scheduler.pause("t3")
    assert scheduler.state("t3") == "paused"
    scheduler.resume("t3")
    assert scheduler.state("t3") == "scheduled"
    scheduler.cancel("t3")
    assert scheduler.state("t3") == "cancelled"


def test_deadline_expiry():
    scheduler = TaskScheduler(Store())
    scheduler.schedule("t4", deadline=time.time() - 1)
    assert scheduler.expired("t4")


def test_secret_store_roundtrip():
    store = SecretStore(MemoryBackend())
    store.put("api_key", "sk-secret-value")
    assert store.get("api_key") == "sk-secret-value"
    assert store.delete("api_key")
    assert store.get("api_key") == ""


def test_secret_redaction():
    store = SecretStore(MemoryBackend())
    store.put("api_key", "sk-123456")
    text = "using key sk-123456 for the request"
    assert "sk-123456" not in store.redact(text)
    assert "***" in store.redact(text)


def test_encrypted_file_backend(tmp_path):
    path = tmp_path / "secrets.bin"
    key = b"0123456789abcdef0123456789abcdef"
    backend = EncryptedFileBackend(str(path), key=key)
    backend.put("token", "abc123")
    raw = path.read_text(encoding="utf-8")
    assert "abc123" not in raw
    assert backend.get("token") == "abc123"
    assert backend.delete("token")
    assert backend.get("token") == ""
