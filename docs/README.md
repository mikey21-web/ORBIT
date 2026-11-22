# ORBIT

ORBIT is an AI assistant that runs on an Android phone. You give it a task in plain
language; it works through the apps needed to complete the task and reports back.

## What it does

- Runs directly on the phone; no computer is required at task time.
- Uses a chat screen for instructions and results.
- Can work on a separate virtual display so the phone stays usable.
- Supports hands-off mode when an app must be operated on the visible screen.
- Ships with a small Python agent, app maps and a local command server.

## Requirements

- Android 14 or newer on an ARM64 device
- JDK 17, Android SDK platform 34 and Gradle 8.x for building
- Python 3 for the bundled agent and host tools

## Build

```bash
cd android-app
bash sync-python.sh
bash sync-maps.sh
gradle assembleDebug
```

See `docs/ORBIT_BUILD_GUIDE.md` in the ORBIT project for release builds and the
helper jar.

## Layout

| Path | Role |
|---|---|
| `android-app/` | Android application and build files |
| `android-helper/` | Virtual-display helper source |
| `orbit/` | Agent core: planning, prompts, memory, device control |
| `orbitcli/`, `orbitcli_android/` | Command-line tools and the crawler used by the app |
| `configs/` | Sample model settings |

These documents are local reference copies for the ORBIT project.
