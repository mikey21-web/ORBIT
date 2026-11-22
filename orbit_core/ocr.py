from __future__ import annotations

import io

from PIL import Image

from .models import Observation, new_id


class OCRReader:
    def __init__(self) -> None:
        self._engine = None

    def _get(self):
        if self._engine is None:
            from rapidocr_onnxruntime import RapidOCR
            self._engine = RapidOCR()
        return self._engine

    def read(self, png: bytes) -> list[Observation]:
        image = Image.open(io.BytesIO(png)).convert("RGB")
        import numpy as np
        result, _ = self._get()(np.array(image))
        if not result:
            return []
        items = []
        for box, text, score in result:
            xs = [point[0] for point in box]
            ys = [point[1] for point in box]
            items.append(Observation(
                id=new_id("ocr"), source="camera_or_image", kind="text",
                text=str(text), bounds=(int(min(xs)), int(min(ys)), int(max(xs)), int(max(ys))),
                confidence=float(score), trust="untrusted_external_content",
            ))
        return items
