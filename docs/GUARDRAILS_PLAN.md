# Guardrails plan

This is the design draft for the guardrails layer; implementation is pending
discussion.

## What it must cover

- **Money and irreversible actions:** keep the existing confirmation gate for
  paying, ordering and submitting; add an explicit allow-list of packages and
  intents before any purchase path runs.
- **Personal data:** never log API keys, pairing keys, OTPs, passwords or full
  chat logs; redact phone numbers and addresses from traces.
- **Device control:** restrict shell commands to a small allow-list, keep the
  existing shell-off mode, and block any command that would disable security
  settings or grant permissions silently.
- **Prompt injection:** treat all web pages, map text and screenshots as
  untrusted content; never allow their text to change the system prompt or
  enable autonomous mode.
- **Rate and cost limits:** cap tokens per task, daily spend per provider, and
  retries; surface a clear message when a limit is reached.
- **Stop conditions:** immediate cancel on user "stop", on repeated identical
  actions, or when the agent leaves its assigned display unexpectedly.

## Where it fits

- `orbit/core/agent.py`: preflight checks and per-step policy hook.
- `orbit/server.py`: task-level limits and audit events.
- `orbit/redact.py`: shared redaction for logs and traces.
- Android: `MainActivity` settings surface plus a small local policy file in
  app-private storage.

No UI or behaviour changes should be made from this draft until the exact
policy list is agreed.
