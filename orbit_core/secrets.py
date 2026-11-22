from __future__ import annotations

import base64
import os
from typing import Protocol


class SecretBackend(Protocol):
    def put(self, key: str, value: str) -> None: ...
    def get(self, key: str) -> str: ...
    def delete(self, key: str) -> bool: ...


class MemoryBackend:
    def __init__(self) -> None:
        self._items: dict[str, str] = {}

    def put(self, key: str, value: str) -> None:
        self._items[key] = value

    def get(self, key: str) -> str:
        return self._items.get(key, "")

    def delete(self, key: str) -> bool:
        return self._items.pop(key, None) is not None


class EncryptedFileBackend:
    def __init__(self, path: str, key: bytes | None = None) -> None:
        self.path = path
        self.key = key or os.environ.get("ORBIT_SECRET_KEY", "").encode()
        self._memory = MemoryBackend()

    def _fernet(self):
        try:
            from cryptography.fernet import Fernet
        except ImportError:
            return None
        if not self.key:
            return None
        try:
            return Fernet(base64.urlsafe_b64encode(self.key[:32].ljust(32, b"0")))
        except Exception:
            return None

    def put(self, key: str, value: str) -> None:
        fernet = self._fernet()
        if fernet is None:
            self._memory.put(key, value)
            return
        lines = {}
        if os.path.exists(self.path):
            with open(self.path, encoding="utf-8") as handle:
                for line in handle:
                    if "=" in line:
                        k, v = line.rstrip("\n").split("=", 1)
                        lines[k] = v
        lines[key] = fernet.encrypt(value.encode()).decode()
        with open(self.path, "w", encoding="utf-8") as handle:
            for k, v in lines.items():
                handle.write(f"{k}={v}\n")

    def get(self, key: str) -> str:
        fernet = self._fernet()
        if fernet is None:
            return self._memory.get(key)
        if not os.path.exists(self.path):
            return ""
        with open(self.path, encoding="utf-8") as handle:
            for line in handle:
                if "=" in line:
                    k, v = line.rstrip("\n").split("=", 1)
                    if k == key:
                        try:
                            return fernet.decrypt(v.encode()).decode()
                        except Exception:
                            return ""
        return ""

    def delete(self, key: str) -> bool:
        fernet = self._fernet()
        if fernet is None:
            return self._memory.delete(key)
        if not os.path.exists(self.path):
            return False
        lines = []
        removed = False
        with open(self.path, encoding="utf-8") as handle:
            for line in handle:
                if "=" in line and line.split("=", 1)[0] == key:
                    removed = True
                else:
                    lines.append(line)
        with open(self.path, "w", encoding="utf-8") as handle:
            handle.writelines(lines)
        return removed


class SecretStore:
    def __init__(self, backend: SecretBackend | None = None, namespace: str = "orbit") -> None:
        self.backend = backend or MemoryBackend()
        self.namespace = namespace

    def _key(self, key: str) -> str:
        return f"{self.namespace}:{key}"

    def put(self, key: str, value: str) -> None:
        self.backend.put(self._key(key), value)

    def get(self, key: str) -> str:
        return self.backend.get(self._key(key))

    def delete(self, key: str) -> bool:
        return self.backend.delete(self._key(key))

    def redact(self, text: str) -> str:
        result = text or ""
        for key in ("api_key", "token", "password", "secret", "otp"):
            value = self.get(key)
            if value:
                result = result.replace(value, "***")
        return result
