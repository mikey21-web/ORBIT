from __future__ import annotations

import re
from dataclasses import dataclass

URGENT = re.compile(r"(urgent|asap|now|immediately|deadline|today|otp|verification code)", re.I)
TASK = re.compile(r"(remind|buy|send|call|meeting|pay|submit|reply|check)", re.I)
SCAM = re.compile(r"(click here|verify your account|winner|lottery|bank details|password)", re.I)


@dataclass
class NotificationInsight:
    priority: str
    category: str
    suspicious: bool
    task: str


class NotificationClassifier:
    def classify(self, text: str) -> NotificationInsight:
        value = text or ""
        urgent = bool(URGENT.search(value))
        suspicious = bool(SCAM.search(value))
        task = ""
        match = TASK.search(value)
        if match:
            task = value[match.start():][:120]
        if suspicious:
            priority = "high"
            category = "security"
        elif urgent:
            priority = "high"
            category = "time_sensitive"
        elif task:
            priority = "normal"
            category = "actionable"
        else:
            priority = "low"
            category = "informational"
        return NotificationInsight(priority, category, suspicious, task)
