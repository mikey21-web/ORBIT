# ORBIT build guide

The debug build was verified with the checked-in Gradle wrapper. Release builds
need your own signing key and have not been verified here.

## Prerequisites

- JDK 17
- Android SDK platform 34 and build tools
- Gradle 8.9 via the checked-in wrapper (`android-app/gradlew`)
- Python 3.13 for Chaquopy and host-side tooling (must match the app runtime)
- `rsync` for the sync scripts
- For release builds: your own keystore and `android-app/keystore.properties`

Set `ANDROID_HOME` to your SDK path, or create a local `android-app/local.properties`
with `sdk.dir=...`. Set `ORBIT_PORT` before a build to change the agent's loopback
port; the default is 8765. Chaquopy 17 needs a build Python matching the app's
Python 3.13 runtime; if the default `python3.13` cannot be found, set `BUILD_PYTHON`
to a Python 3.13 executable.

## Project layout

The Gradle project root is `android-app/`; the app module is `android-app/app/`.
The build uses Android Gradle Plugin 8.7.3, Kotlin 1.9.24 and Chaquopy 17.2.0
with Python 3.13 for 16 KB page-size support.
Only `arm64-v8a` is built, and `minSdk`/`targetSdk` are 34.

## Sync bundled sources and maps

Chaquopy packages a copy of the agent. It is not edited in place.

```bash
cd android-app
bash sync-python.sh
bash sync-maps.sh
```

`sync-python.sh` copies `../orbit/`, the `orbitcli` package and the crawler entrypoint
from `orbitcli_android/evaluation/build_map.py` into `app/src/main/python/`.

## Debug build

```bash
cd android-app
./gradlew assembleDebug
```

The output is `android-app/app/build/outputs/apk/debug/`.

## Release build

Copy `android-app/keystore.properties.example` to
`android-app/keystore.properties` and fill in the real values. That file is not
committed. The default key alias and keystore name are `orbit`.

```bash
cd android-app
./gradlew assembleRelease
```

The output is `android-app/app/build/outputs/apk/release/`.

## Helper jar

The virtual-display helper is built separately when `android-helper/` changes:

```bash
bash build-helper/build.sh
```

The script defaults to macOS SDK and JDK paths. On Linux or Windows set
`ANDROID_HOME` and `JAVA_HOME` first. A prebuilt copy is already present at
`android-app/app/src/main/assets/pa-helper.jar`.

## Legacy shell build

`android-app/build.sh` builds a dependency-free APK with `aapt2`, `javac`, `d8` and
`apksigner`. It predates the current Kotlin/Chaquopy sources and is not the release
path.

## Deployment constraints

The app targets Android 14 or newer and arm64 devices only. The virtual-display
helper and the bundled Python runtime are built for that combination.

Wireless-debugging pairing and broad app visibility are required for the agent to
operate other apps. Those capabilities are not compatible with Google Play
distribution, so ORBIT is installed from an APK.

Device-built maps under `orbit/maps/` stay local because they can contain device
names and account text. The APK ships a sanitized settings map; other maps are
built on the device when needed.

## Known limitations

The pairing package contains its own RSA, Ed25519 and SPAKE2 implementations in
addition to maintained libraries. It has not received an independent crypto review.

Host helper trees under `agent/`, `page_executor/`, `templates/` and `utils_mobile/`
are still referenced by development scripts, so they remain in the repository.

Large source files such as `MainActivity.java` and `core/agent.py` have not been
split into smaller modules. Refactoring them is a separate maintenance task.
