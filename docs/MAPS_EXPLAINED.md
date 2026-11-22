# App maps

App maps are YAML "cheat sheets" for apps the agent already knows. They let it
replay recorded taps instead of reasoning from the screen on every step.

## What is in a map

`android-app/app/src/main/assets/maps/settings.yaml` is the only map shipped
right now. It records:

- each screen's text and controls
- normalized tap coordinates (`center`)
- where a tap leads (`leads_to`)
- alternative phrases a user might say (`aliases`)
- a compiled path from a cold start to each screen (`screen_macros`)

A map is generated on a device with `python3 -m orbit.build_map`, which walks
the app's screens and saves a YAML file. Device-built maps can contain account
or device text, so `orbit/maps/` is gitignored; only sanitized maps are copied
into the APK by `android-app/sync-maps.sh`.

`clock.yaml` is referenced by that script but currently does not exist in this
workspace.

## How the app uses them

1. `AgentService.extractMaps()` copies assets from `assets/maps` into
   `filesDir/maps` at startup.
2. `orbit/server.py` scans that directory and loads each file with
   `orbit.core.app_map.AppMap`.
3. `AppMap.build_operations()` turns the recorded screens into a catalogue of
   operations the router can choose from.
4. `orbit/core/agent.py` first tries these known operations (fast path) and
   falls back to screen-driven vision when the task does not match a map.

The Maps button in the app lets you see, pin, or remove maps. A pinned map wins
over an automatically selected one; when several maps match, the one covering
more screens wins.

In short: maps are recorded navigation experience. They make known tasks fast
and reliable; the vision loop handles everything else.
