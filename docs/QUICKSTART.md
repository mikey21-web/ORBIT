# ORBIT quick start

This guide covers the first run on a physical Android device.

## Before you start

1. Build a debug APK using the build guide in the ORBIT project.
2. Install it on an ARM64 device running Android 14 or newer.
3. Allow installation from unknown sources when the system asks.

## First launch

- Open ORBIT and grant the accessibility permission. The agent reads the control
  tree through this channel.
- Allow notifications so background task progress stays visible.
- Give the app permission to ignore battery optimisation on OEM skins, otherwise
  a long task can be frozen.
- Enter your model API key in Settings. Use a dedicated API key.

## Running a task

Type a clear instruction, for example "open WLAN settings". The app shows the
current step while it works and prints the result when the run ends.

Use the mode switch in Settings to choose how the agent drives the phone:

- Auto: follows known app maps and falls back to screen reading.
- Vision: decides every step from the screen.
- Hands-off: operates the visible screen so you can watch each step.

## If something goes wrong

Check the on-screen message first. Common causes are a missing permission, a
stale model key, or an app that refuses to run on a secondary display.
