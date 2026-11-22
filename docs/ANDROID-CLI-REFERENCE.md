# ORBIT Android tooling reference

A compact reference for the Android-side helper package.

## Crawler entrypoint

`evaluation/build_map.py` walks an app screen by screen and records controls,
transitions and reusable paths. It is copied into the APK by the sync script.

Inputs: a device controller, a target app package, a display name and an output
path. Output: a YAML map with screens and operations.

## Device controller

The controller sends ADB commands, captures screen XML and performs taps, text
entry and scrolls. It also handles launching an app on a chosen display.

## Special handling

A few app families hide their controls from the accessibility tree. Dedicated
checks recognize those apps and route them through a different strategy, such as
hands-off operation on the visible screen.

## Maintenance notes

- Keep package identifiers current when a target app changes id.
- Avoid adding device-specific account data to maps.
- Validate a map before shipping it; the validator catches broken links and
  private values.
- Rebuild the APK after changing any file in this package.
