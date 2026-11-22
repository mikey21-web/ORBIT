from __future__ import annotations

import math
import re
from collections import Counter

from .models import Memory

TOKEN = re.compile(r"[a-z0-9]+")


def tokens(text: str) -> list[str]:
    return TOKEN.findall((text or "").lower())


class LocalRetriever:
    def __init__(self) -> None:
        self._docs: dict[str, list[str]] = {}
        self._idf: dict[str, float] = {}

    def index(self, items: list[Memory]) -> None:
        self._docs = {m.id: tokens(m.content) for m in items if not m.tombstone}
        df: Counter[str] = Counter()
        for toks in self._docs.values():
            df.update(set(toks))
        total = max(1, len(self._docs))
        self._idf = {term: math.log((total + 1) / (count + 1)) + 1 for term, count in df.items()}

    def search(self, query: str, limit: int = 5) -> list[tuple[str, float]]:
        q = tokens(query)
        if not q or not self._docs:
            return []
        qtf = Counter(q)
        qvec = {term: (1 + math.log(count)) * self._idf.get(term, 1.0) for term, count in qtf.items()}
        qnorm = math.sqrt(sum(v * v for v in qvec.values())) or 1.0
        scored = []
        for doc_id, toks in self._docs.items():
            dtf = Counter(toks)
            dvec = {term: (1 + math.log(count)) * self._idf.get(term, 1.0) for term, count in dtf.items()}
            dnorm = math.sqrt(sum(v * v for v in dvec.values())) or 1.0
            dot = sum(qvec.get(term, 0.0) * value for term, value in dvec.items())
            scored.append((doc_id, dot / (qnorm * dnorm)))
        scored.sort(key=lambda pair: pair[1], reverse=True)
        return [pair for pair in scored[:limit] if pair[1] > 0]
