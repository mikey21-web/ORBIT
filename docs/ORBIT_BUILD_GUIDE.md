# ORBIT build guide

The build setup described here matches the repository configuration. Commands have
not been executed in this workspace, so treat this as the documented procedure
rather than a claim of a verified build.

## Prerequisites

- JDK 17
- Android SDK platform 34 and build tools
- Gradle 8.x installed on the machine (there is no `gradlew` wrapper in this repository)
- Python 3 for Chaquopy and host-side tooling
- `rsync` for the sync scripts
- For release builds: your own keystore and `android-app/keystore.properties`

## Project layout

The Gradle project root is `android-app/`; the app module is `android-app/app/`.
The build uses Android Gradle Plugin 8.7.3, Kotlin 1.9.24 and Chaquopy 16.0.0.
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
gradle assembleDebug
```

The output is `android-app/app/build/outputs/apk/debug/`.

## Release build

Copy `android-app/keystore.properties.example` to
`android-app/keystore.properties` and fill in the real values. That file is not
committed. The default key alias and keystore name are `orbit`.

```bash
cd android-app
gradle assembleRelease
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
