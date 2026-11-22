# ORBIT

ORBIT is an AI assistant that runs on an Android phone. It takes a task in plain
language, works through the apps needed to complete it, and reports the result in
the chat screen.

The agent runs inside the APK. It can drive the phone on a separate virtual display
so the person keeps using the device, or on the visible screen when an app requires
hands-on interaction.

## What it can do

- Route a task to a known app map and replay recorded operations.
- Fall back to screen-driven decisions when no map matches.
- Plan across apps, keep short-term memory and hand sensitive steps back to the user.
- Read and drive pages in an in-app browser with structured text access.
- Pair with adbd over wireless debugging and control the device through accessibility
  and shell channels.
- Build and validate app maps with host-side tools.
- Use DeepSeek Flash for text, images and tools; Groq is also supported.
- Plan voice input and a "Hey Diya" wake word (see `docs/VOICE_AND_WAKE_PLAN.md`).
- Export and import its own settings and maps as a backup archive.

## Requirements

- Android 14 or newer on an ARM64 device
- JDK 17
- Android SDK platform 34 with build tools
- Python 3 for the bundled agent and host tools

## Build

```bash
cd android-app
./gradlew assembleDebug
```

The APK is written to `android-app/app/build/outputs/apk/debug/`.

Before packaging, sync the Python agent and maps into the APK:

```bash
cd android-app
bash sync-python.sh
bash sync-maps.sh
```

For a release build, copy `android-app/keystore.properties.example` to
`android-app/keystore.properties`, fill in your own signing values and run
`./gradlew assembleRelease`. Never commit the keystore or its properties file.

## Tests

```bash
python -m pytest tests -q
```

The suite checks the shell guard, redaction rules, package identity and server limits.

## Layout

| Path | Role |
|---|---|
| `android-app/` | Android application, resources, Gradle build |
| `android-helper/` | Virtual-display helper source |
| `orbit/` | Agent core, device adapters and local server |
| `orbitcli/`, `orbitcli_android/` | Command-line tools and crawler |
| `agent/`, `page_executor/`, `templates/`, `utils_mobile/` | Host-side helpers |
| `configs/` | Example model settings |
| `docs/` | Build guide, maps explanation, voice/wake plan, guardrails draft and parity notes |
| `tests/` | Local verification suite |

## Deployment notes

The app targets Android 14+ on arm64 devices only. Wireless-debugging pairing and
broad app visibility are required, so ORBIT is installed from an APK rather than
distributed through an app store. Device-built maps stay local because they can
contain device-specific text; the APK ships a sanitized settings map.
