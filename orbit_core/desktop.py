from __future__ import annotations

import json
import os
from typing import Any


class DesktopRuntime:
    def __init__(self, root: str) -> None:
        self.root = os.path.abspath(root)
        os.makedirs(self.root, exist_ok=True)

    def path(self, name: str) -> str:
        target = os.path.abspath(os.path.join(self.root, name))
        if not target.startswith(self.root + os.sep) and target != self.root:
            raise PermissionError("path escapes runtime root")
        return target

    def write(self, name: str, content: str) -> str:
        target = self.path(name)
        os.makedirs(os.path.dirname(target), exist_ok=True)
        with open(target, "w", encoding="utf-8") as handle:
            handle.write(content)
        return target

    def read(self, name: str) -> str:
        with open(self.path(name), encoding="utf-8") as handle:
            return handle.read()

    def list(self, prefix: str = "") -> list[str]:
        base = self.path(prefix)
        if not os.path.isdir(base):
            return []
        return sorted(os.listdir(base))


def browser_extension_manifest(origin: str = "http://127.0.0.1:8765") -> dict[str, Any]:
    return {
        "manifest_version": 3,
        "name": "Orbit Bridge",
        "version": "0.1.0",
        "description": "Consented bridge between the browser and the local Orbit agent.",
        "permissions": ["activeTab", "scripting"],
        "host_permissions": [f"{origin}/*"],
        "background": {"service_worker": "background.js"},
        "action": {"default_title": "Orbit Bridge"},
    }


def write_extension(root: str, origin: str = "http://127.0.0.1:8765") -> str:
    runtime = DesktopRuntime(root)
    runtime.write("manifest.json", json.dumps(browser_extension_manifest(origin), indent=2))
    runtime.write(
        "background.js",
        "chrome.action.onClicked.addListener((tab) => "
        "chrome.scripting.executeScript({target:{tabId:tab.id},"
        "func:()=>document.title}));\n",
    )
    return runtime.root
