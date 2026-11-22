# ORBIT Android tooling

The `orbitcli_android` tree holds helpers that understand Android maps, device
controllers and the app-specific checks used by the agent.

## Layout

| Path | Purpose |
|---|---|
| `adb_client.py` | ADB bridge used by host tools |
| `agent/` | Model adapters for local scripts |
| `evaluation/build_map.py` | Crawler entrypoint copied into the APK |
| `page_executor/` | Screen text extraction helpers |
| `templates/` | Prompt templates for screen descriptions |
| `utils_mobile/` | Device controllers and XML helpers |

## App maps

Android maps describe screens, controls and recorded paths. They are generated on
a device and stored as YAML. Only maps that have been measured for reliable
landing are shipped in the APK; the sync script handles the selection.

## Device checks

Some apps need extra handling because their screens are not exposed through the
normal accessibility tree. The checks under `utils_mobile` detect those cases and
let the agent switch strategy.

## Build use

During a build, the sync script copies the crawler entrypoint and its dependencies
into the bundled Python tree. Keep imports within this package or the shared
`orbitcli` package so the copy stays self-contained.
