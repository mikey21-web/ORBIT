"""Optional cloud speech-to-text using the DeepSeek ASR tool API."""

from pathlib import Path

import requests

ASR_URL = "https://api.deepseek.com/v1/tools/asr"
MAX_BYTES = 8 * 1024 * 1024


def transcribe(path: str, api_key: str, timeout: float = 120.0) -> str:
    """Transcribe one WAV or Ogg Opus file and return its text."""
    audio = Path(path)
    data = audio.read_bytes()
    if not data:
        raise ValueError("empty audio file")
    if len(data) > MAX_BYTES:
        raise ValueError("audio file is larger than 8 MiB")
    response = requests.post(
        ASR_URL,
        headers={"Authorization": f"Bearer {api_key}"},
        files={"file": (audio.name, data, "application/octet-stream")},
        timeout=timeout,
    )
    if response.status_code != 200:
        raise RuntimeError(f"ASR HTTP {response.status_code}: {response.text[:300]}")
    return str(response.json().get("text", ""))
