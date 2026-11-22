from __future__ import annotations

from dataclasses import dataclass

from .models import Task, TaskKind, new_id
from .policy import classify_risk


SPECIALIST_TOOLS = {
    "secretary": ["calendar.read", "calendar.create", "reminder.create"],
    "communication": ["message.read", "message.draft", "email.read", "email.draft"],
    "research": ["web.open", "web.read", "web.search"],
    "productivity": ["file.search", "doc.summarise", "doc.create"],
    "finance": ["finance.read", "finance.categorise", "finance.budget"],
    "coding": ["repo.read", "repo.review", "code.explain"],
    "learning": ["lesson.plan", "lesson.quiz", "lesson.progress"],
    "travel": ["travel.search", "travel.itinerary", "travel.reservation"],
    "shopping": ["product.search", "product.compare", "cart.prepare"],
    "health": ["health.summary", "habit.track", "appointment.organise"],
    "smart_home": ["home.device.read", "home.device.set", "home.routine"],
    "entertainment": ["media.play", "media.discover", "social.draft"],
    "identity": ["account.inventory", "subscription.track", "permission.review"],
    "life": ["timeline.build", "commitment.track", "goal.progress"],
    "digital_ops": ["workflow.schedule", "workflow.resume", "workflow.report"],
    "agent_network": ["agent.delegate", "agent.verify", "agent.result_merge"],
    "ecosystem": ["device.sync", "desktop.companion", "browser.extension"],
    "experimental": ["predict.plan", "simulate.decision", "workflow.discover"],
    "proactive": ["context.suggest", "conflict.detect", "routine.detect"],
}

KEYWORDS = {
    "secretary": ("calendar", "meeting", "remind", "schedule", "agenda", "appointment"),
    "communication": ("message", "whatsapp", "email", "reply", "draft", "call", "notification"),
    "research": ("research", "investigate", "find out"),
    "productivity": ("file", "document", "pdf", "summarise", "summarize", "report"),
    "finance": ("expense", "budget", "spend", "bill", "subscription", "emi", "savings"),
    "coding": ("code", "github", "pull request", "bug", "repository", "compile"),
    "learning": ("learn", "teach", "study", "quiz", "practice", "interview"),
    "travel": ("travel", "flight", "hotel", "trip", "itinerary", "route"),
    "shopping": ("buy", "shop", "product", "price", "cart", "order"),
    "health": ("health", "workout", "sleep", "meal", "fitness", "habit"),
    "smart_home": ("light", "thermostat", "smart home", "plug", "appliance"),
    "entertainment": ("music", "movie", "song", "playlist", "video", "social post"),
    "identity": ("account", "password", "login", "permission", "renewal"),
    "life": ("timeline", "commitment", "goal", "promise", "progress report"),
    "digital_ops": ("workflow", "queue", "background", "resume", "schedule task"),
    "agent_network": ("delegate", "another agent", "verify result", "multi-agent"),
    "ecosystem": ("desktop", "browser extension", "sync", "glasses"),
    "experimental": ("predict", "simulate", "discover pattern", "what if"),
    "proactive": ("suggest", "remind me before", "conflict", "routine"),
}


@dataclass
class SpecialistResult:
    specialist: str
    tasks: list[Task]
    answer: str


class Specialist:
    def __init__(self, name: str) -> None:
        self.name = name
        self.tools = SPECIALIST_TOOLS.get(name, [])

    def plan(self, goal: str) -> list[Task]:
        tasks = []
        for tool in self.tools[:4]:
            tasks.append(Task(
                id=new_id("task"), objective=f"{self.name}: {tool} for '{goal}'",
                kind=TaskKind.TASK, tool=tool,
                required_scopes={tool}, risk=classify_risk(tool),
            ))
        return tasks


class SpecialistRegistry:
    def __init__(self) -> None:
        self._items = {name: Specialist(name) for name in SPECIALIST_TOOLS}

    def resolve(self, goal: str) -> Specialist:
        text = (goal or "").lower()
        for name, words in KEYWORDS.items():
            if any(word in text for word in words):
                return self._items[name]
        return self._items["secretary"]

    def run(self, goal: str) -> SpecialistResult:
        specialist = self.resolve(goal)
        tasks = specialist.plan(goal)
        return SpecialistResult(specialist.name, tasks, f"{specialist.name} planned {len(tasks)} tasks")
