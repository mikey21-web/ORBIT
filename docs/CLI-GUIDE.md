# ORBIT command-line tools

The `orbitcli` package contains host-side helpers used during development and by
the APK build.

## Common commands

Run these from the repository root with Python 3:

- `python -m orbit.check` - validate map files offline
- `python -m orbit.validate` - check map structure and privacy rules
- `python -m orbit.probe` - inspect device connectivity
- `python -m orbit.run` - run one task from the command line

## Supporting modules

| Module | Purpose |
|---|---|
| `llm_client.py` | Model request helper |
| `prompts.py` | Shared prompt templates |
| `app_map.py` | Map model used by the crawler |
| `device.py` | Device connection helpers |
| `sanitize_map.py` | Removes private values from maps |
| `validate_map.py` | Map integrity checks |

## Build integration

`android-app/sync-python.sh` copies this package into the APK alongside the agent.
The crawler entrypoint comes from `orbitcli_android/evaluation/build_map.py`.
Changes here require a fresh sync and rebuild before they appear in the app.
