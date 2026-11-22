from __future__ import annotations

import hashlib
import json
import time
from dataclasses import dataclass, field

from .models import new_id


@dataclass
class SkillManifest:
    name: str
    version: str
    capabilities: list[str] = field(default_factory=list)
    tool_allowlist: set[str] = field(default_factory=set)
    egress_allowlist: set[str] = field(default_factory=set)
    scopes: set[str] = field(default_factory=set)

    def digest(self) -> str:
        payload = {
            "name": self.name, "version": self.version,
            "capabilities": sorted(self.capabilities),
            "tools": sorted(self.tool_allowlist),
            "egress": sorted(self.egress_allowlist),
            "scopes": sorted(self.scopes),
        }
        return hashlib.sha256(json.dumps(payload, sort_keys=True).encode()).hexdigest()


@dataclass
class InstalledSkill:
    id: str
    manifest: SkillManifest
    digest: str
    granted_scopes: set[str] = field(default_factory=set)
    active: bool = True
    installed_at: float = field(default_factory=time.time)


class SkillRegistry:
    def __init__(self) -> None:
        self._skills: dict[str, InstalledSkill] = {}

    def install(self, manifest: SkillManifest, granted_scopes: set[str] | None = None) -> InstalledSkill:
        skill = InstalledSkill(
            id=new_id("skill"), manifest=manifest, digest=manifest.digest(),
            granted_scopes=granted_scopes or set(),
        )
        self._skills[skill.id] = skill
        return skill

    def verify(self, skill_id: str) -> bool:
        skill = self._skills.get(skill_id)
        return bool(skill and skill.active and skill.digest == skill.manifest.digest())

    def authorize(self, skill_id: str, tool: str, domain: str = "") -> tuple[bool, str]:
        skill = self._skills.get(skill_id)
        if skill is None:
            return False, "unknown skill"
        if not skill.active:
            return False, "skill revoked"
        if not self.verify(skill_id):
            return False, "manifest changed"
        if tool not in skill.manifest.tool_allowlist:
            return False, f"tool not allowed: {tool}"
        if domain and skill.manifest.egress_allowlist and domain not in skill.manifest.egress_allowlist:
            return False, f"egress not allowed: {domain}"
        if not skill.manifest.scopes.issubset(skill.granted_scopes):
            return False, "scopes not granted"
        return True, "ok"

    def revoke(self, skill_id: str) -> bool:
        skill = self._skills.get(skill_id)
        if skill is None:
            return False
        skill.active = False
        skill.granted_scopes.clear()
        return True

    def get(self, skill_id: str) -> InstalledSkill | None:
        return self._skills.get(skill_id)
