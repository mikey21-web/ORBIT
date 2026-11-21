"""orbit  -  a platform-agnostic phone agent core.

This package is the new home for the OrbitCLI algorithm (app maps, macro
replay, macro/VLM routing) with the device transport pulled out behind a
single protocol, so the same agent can run against:

    * iOS via WebDriverAgent        -> orbit.devices.wda.WdaDevice
    * Android via adb (host side)   -> orbit.devices.adb.AdbDevice
    * Android on-device (APK)       -> future: shell helper + virtual display
    * a canned recording (tests)    -> orbit.devices.mock.MockDevice

Layout::

    orbit/
      core/
        app_map.py     load/query YAML app maps (iOS + Android)
        macro.py       replay abstract macro steps against any device
      devices/
        base.py        PhoneDevice protocol + shared types
        adb.py         Android host-side backend (adb shell)
        wda.py         iOS backend (wraps orbitcli/device.py)
        mock.py        in-memory backend for tests

The source ``orbitcli/`` and ``orbitcli_android/`` trees are kept
untouched as reference implementations.
"""

__all__ = ["core", "devices"]
__version__ = "0.1.0"
