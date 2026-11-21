"""Device backends. Import the concrete class you need, not this package.

Only :mod:`orbit.devices.base` and :mod:`orbit.devices.mock` are
dependency-free; ``adb`` needs the adb binary and ``wda`` needs
``orbitcli.device`` (i.e. ``requests``).
"""

from .base import (
    BaseDevice,
    DeviceError,
    DeviceInfo,
    DeviceNotReady,
    PhoneDevice,
    UnicodeTextUnsupported,
    UnsupportedOperation,
)
from .mock import MockDevice, MockFailure

__all__ = [
    "BaseDevice",
    "DeviceError",
    "DeviceInfo",
    "DeviceNotReady",
    "PhoneDevice",
    "UnicodeTextUnsupported",
    "UnsupportedOperation",
    "MockDevice",
    "MockFailure",
]
