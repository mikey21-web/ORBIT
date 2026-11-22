from __future__ import annotations

from typing import Protocol

from .models import RiskLevel, new_id
from .policy import classify_risk


class BrowserBackend(Protocol):
    def navigate(self, url: str) -> str: ...
    def text(self) -> str: ...
    def click(self, text: str) -> bool: ...
    def fill(self, selector: str, value: str) -> bool: ...


class BrowserAdapter:
    def __init__(self, backend: BrowserBackend, egress_allowlist: set[str] | None = None) -> None:
        self.backend = backend
        self.egress = egress_allowlist or set()

    def open(self, url: str) -> dict:
        if self.egress and not any(domain in url for domain in self.egress):
            return {"observed": "egress denied", "verified": False, "error": "domain not allowed"}
        observed = self.backend.navigate(url)
        return {"observed": observed[:500], "verified": True, "method": "ui_state"}

    def read(self) -> dict:
        return {"observed": self.backend.text()[:4000], "verified": True, "method": "ui_state"}

    def click(self, text: str) -> dict:
        risk = classify_risk(f"click {text}", content=text)
        ok = self.backend.click(text)
        return {"observed": f"clicked {text}", "verified": ok, "risk": risk.value,
                "requires_approval": risk in (RiskLevel.HIGH, RiskLevel.CRITICAL)}

    def fill(self, selector: str, value: str) -> dict:
        risk = classify_risk(f"type into {selector}", content=value)
        ok = self.backend.fill(selector, value)
        return {"observed": f"filled {selector}", "verified": ok, "risk": risk.value,
                "requires_approval": risk in (RiskLevel.HIGH, RiskLevel.CRITICAL)}


class PlaywrightBrowser:
    def __init__(self, headless: bool = True) -> None:
        from playwright.sync_api import sync_playwright
        self._playwright = sync_playwright().start()
        self._browser = self._playwright.chromium.launch(headless=headless)
        self._page = self._browser.new_page()

    def navigate(self, url: str) -> str:
        self._page.goto(url, wait_until="domcontentloaded")
        return self._page.url

    def text(self) -> str:
        return self._page.locator("body").inner_text()

    def click(self, text: str) -> bool:
        try:
            self._page.get_by_text(text, exact=False).first.click(timeout=5000)
            return True
        except Exception:
            return False

    def fill(self, selector: str, value: str) -> bool:
        try:
            self._page.locator(selector).first.fill(value, timeout=5000)
            return True
        except Exception:
            return False

    def close(self) -> None:
        self._browser.close()
        self._playwright.stop()


class MockBrowser:
    def __init__(self) -> None:
        self.url = ""
        self.text_value = "sample page"
        self.clicks: list[str] = []
        self.fills: list[tuple[str, str]] = []

    def navigate(self, url: str) -> str:
        self.url = url
        self.text_value = f"page at {url}"
        return self.text_value

    def text(self) -> str:
        return self.text_value

    def click(self, text: str) -> bool:
        self.clicks.append(text)
        return True

    def fill(self, selector: str, value: str) -> bool:
        self.fills.append((selector, value))
        return True
