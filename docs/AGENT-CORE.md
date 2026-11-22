# ORBIT agent core

The `orbit` package is the reasoning and execution layer bundled into the app.

## Responsibilities

- Read a task and build a short plan.
- Choose between a known macro and step-by-step screen decisions.
- Track progress across screens and avoid repeating failed actions.
- Apply risk checks before sensitive steps.
- Remove personal data from maps and outputs before they are shared.
- Serve the local HTTP API used by the Android interface.

## Main modules

| Module | Purpose |
|---|---|
| `core/agent.py` | Task loop, planning and hand-off to the user |
| `core/prompts.py` | Instructions sent to the language and vision models |
| `core/app_map.py` | Loads and queries app maps |
| `core/macro.py` | Replays recorded operation paths |
| `core/memory.py` | Keeps short facts and procedures across runs |
| `devices/` | Device control, pairing and virtual-display transport |
| `server.py` | Local request handling for the app |
| `web.py` | Browser page handling |

## Execution model

The planner receives the task, the current screen summary and the available maps.
A matching macro gives a fast path. If no macro fits, the vision model proposes the
next action from the screen. Each action is checked, executed, and verified before
the loop continues.

Risky steps such as payments, permissions or login flows are handed back to the
person instead of being completed silently.
