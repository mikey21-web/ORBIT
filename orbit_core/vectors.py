from __future__ import annotations

from typing import Any


class VectorIndex:
    def __init__(self, model_name: str = "") -> None:
        self._model = None
        self._model_name = model_name
        self._store = None
        self._collection = None
        self._docs: dict[str, str] = {}

    def _ensure(self) -> None:
        if self._model is not None:
            return
        from chromadb import EphemeralClient
        from fastembed import TextEmbedding

        self._model = TextEmbedding(model_name=self._model_name) if self._model_name else TextEmbedding()
        self._store = EphemeralClient()
        self._collection = self._store.create_collection("orbit_memory")

    def add(self, doc_id: str, text: str, metadata: dict[str, Any] | None = None) -> None:
        self._ensure()
        vector = list(self._model.embed([text]))[0].tolist()
        self._docs[doc_id] = text
        self._collection.add(
            ids=[doc_id],
            embeddings=[vector],
            metadatas=[{"text": text, **(metadata or {})}],
        )

    def search(self, query: str, limit: int = 5) -> list[tuple[str, float]]:
        self._ensure()
        if not self._docs:
            return []
        vector = list(self._model.embed([query]))[0].tolist()
        result = self._collection.query(query_embeddings=[vector], n_results=min(limit, len(self._docs)))
        ids = result["ids"][0]
        distances = result["distances"][0]
        return [(doc_id, 1.0 - float(distance)) for doc_id, distance in zip(ids, distances)]

    def delete(self, doc_id: str) -> bool:
        self._ensure()
        if doc_id not in self._docs:
            return False
        self._collection.delete(ids=[doc_id])
        self._docs.pop(doc_id, None)
        return True

    def count(self) -> int:
        self._ensure()
        return len(self._docs)
