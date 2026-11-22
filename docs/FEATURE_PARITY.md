# Feature parity notes

Most of the agent core, planner, memory and browser tools are present. The
remaining gaps are mostly shipped content and user-facing polish.

## Highest impact gaps

1. **App maps and profiles** — only the Settings map ships. Other apps need to
   be crawled on the device before deterministic replay works.
2. **Language selector** — the stored language preference currently has no
   effect; the UI is English-only.
3. **Provider presets** — only DeepSeek Flash, DeepSeek, Groq, OpenAI,
   Anthropic and Custom are offered. Other providers need Custom base/model.
4. **Documentation** — there is no user manual, getting-started guide or
   error reference for shipped features.
5. **Tooling** — the recorder and standalone map-build/evaluation tools are
   missing; only the in-app map builder remains.
6. **Non-English text handling** — memory, redaction and web block detection
   are English-only.
7. **Cross-app carry-over** — quote styles and recovery from "cannot see the
   value" answers are weaker than the original design.
8. **Shell restrictions** — multi-command chains and privileged commands are
   blocked by design; revisit only if a real task fails.
9. **Voice/audio** — no upstream implementation to port; this is a new feature.

## Recommended priority

1. Ship more verified app maps and profiles.
2. Make the language selector functional and add a user manual.
3. Restore the missing provider presets.
4. Bring back the recorder and map-build/evaluation tooling.
5. Broaden redaction and web detection to non-English text.
6. Improve planning recovery and carried values.
7. Build the voice stack as a separate feature.

See `docs/VOICE_AND_WAKE_PLAN.md` and `docs/GUARDRAILS_PLAN.md` for the two
new workstreams.
