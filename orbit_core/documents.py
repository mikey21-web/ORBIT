from __future__ import annotations

import json
import os

import pdfplumber
from pypdf import PdfReader

from .models import Observation, new_id


class DocumentReader:
    def __init__(self) -> None:
        self.last_metadata: dict = {}

    def read_pdf(self, path: str) -> list[Observation]:
        if not os.path.isfile(path):
            raise FileNotFoundError(path)
        reader = PdfReader(path)
        metadata = {"pages": len(reader.pages)}
        items: list[Observation] = []
        with pdfplumber.open(path) as pdf:
            for number, page in enumerate(pdf.pages, start=1):
                for word in page.extract_words():
                    items.append(Observation(
                        id=new_id("doc"),
                        source=f"pdf:{os.path.basename(path)}#page{number}",
                        kind="text",
                        text=word["text"],
                        bounds=(round(word["x0"], 1), round(word["top"], 1),
                                round(word["x1"], 1), round(word["bottom"], 1)),
                        confidence=1.0,
                    ))
        self.last_metadata = metadata
        return items

    def to_json(self, observations: list[Observation]) -> str:
        return json.dumps(
            [
                {
                    "id": item.id, "source": item.source, "kind": item.kind,
                    "text": item.text, "bounds": item.bounds, "confidence": item.confidence,
                }
                for item in observations
            ],
            ensure_ascii=False,
        )
