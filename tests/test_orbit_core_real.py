from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

from orbit_core import (
    BrowserAdapter, OCRReader, PlaywrightBrowser, VectorIndex,
)


def test_vector_index_real_embeddings():
    index = VectorIndex()
    index.add("a", "the user likes strong coffee in the morning")
    index.add("b", "meeting with Rahul at five tomorrow")
    index.add("c", "python code review for the api service")
    assert index.count() == 3
    results = index.search("coffee in the morning", limit=2)
    assert results
    assert results[0][0] == "a"
    assert index.delete("a")
    assert index.count() == 2
    assert all(doc_id != "a" for doc_id, _ in index.search("coffee", limit=3))


def test_ocr_reader_real_ocr(tmp_path):
    font = ImageFont.truetype("C:\\Windows\\Fonts\\arial.ttf", 48)
    image = Image.new("RGB", (700, 160), "white")
    draw = ImageDraw.Draw(image)
    draw.text((20, 40), "HELLO ORBIT", fill="black", font=font)
    path = tmp_path / "ocr.png"
    image.save(path)
    items = OCRReader().read(path.read_bytes())
    assert items
    text = " ".join(item.text.upper() for item in items)
    assert "HELLO" in text or "ORBIT" in text


def test_playwright_browser_real_page(tmp_path):
    html = tmp_path / "page.html"
    html.write_text(
        "<html><body><h1>Orbit test page</h1>"
        "<input id='q'></input>"
        "<button onclick='document.body.setAttribute(\"data-clicked\",\"yes\")'>Go</button>"
        "</body></html>",
        encoding="utf-8",
    )
    backend = PlaywrightBrowser(headless=True)
    try:
        adapter = BrowserAdapter(backend, egress_allowlist={"file://"})
        opened = adapter.open(html.as_uri())
        assert opened["verified"]
        assert "Orbit test page" in adapter.read()["observed"]
        assert adapter.fill("#q", "hello")["verified"]
        assert adapter.click("Go")["verified"]
        assert backend._page.get_attribute("body", "data-clicked") == "yes"
    finally:
        backend.close()
