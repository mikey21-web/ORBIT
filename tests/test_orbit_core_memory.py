import time

from orbit_core import ContextResolver, MemoryStore
from orbit_core.models import MemoryProposal


def test_memory_add_and_recall():
    store = MemoryStore()
    store.add(MemoryProposal("preference", "user prefers short answers", "chat"))
    store.add(MemoryProposal("fact", "meeting with Rahul tomorrow", "chat"))
    found = store.recall("short answers")
    assert len(found) == 1
    assert "short answers" in found[0].content


def test_memory_delete_is_hard():
    store = MemoryStore()
    item = store.add(MemoryProposal("secret", "card number 1234", "chat"))
    assert store.delete(item.id)
    assert store.recall("card number") == []
    assert item.content == ""
    assert store.get(item.id).tombstone


def test_memory_expiry():
    store = MemoryStore()
    store.add(MemoryProposal("fact", "temporary", "chat", expires_at=time.time() - 1))
    assert store.recall("temporary") == []


def test_purge_scope():
    store = MemoryStore()
    store.add(MemoryProposal("fact", "one", "chat", scope="user"))
    store.add(MemoryProposal("fact", "two", "chat", scope="team"))
    assert store.purge("user") == 1
    assert len(store.all()) == 1


def test_context_resolver_includes_observations():
    store = MemoryStore()
    store.add(MemoryProposal("preference", "likes tea", "chat"))
    resolver = ContextResolver(store)
    result = resolver.build("tea", ["Settings screen", "Bluetooth on"])
    assert "likes tea" in result.message
    assert "Bluetooth on" in result.message
    assert len(result.used_ids) == 1
