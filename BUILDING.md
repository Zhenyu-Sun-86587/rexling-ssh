# Building Rexlink

Run commands below from the `rexlink/` repository root unless noted otherwise.
Builds are supported by the native helper on macOS and Linux. The verified local
environment is Apple Silicon macOS with Android Studio's JDK 25. The APK targets
arm64-v8a Android 9/API 28 or newer; other Android ABIs are not built yet.

## Prerequisites

- Rust 1.93+ and rustup, with Cargo available on PATH or under `~/.cargo/bin`.
- Python 3.9+ and curl for the native build helper.
- Android Studio, or an Android SDK plus JDK 25.
- SDK platform `platforms;android-37`, Build Tools `build-tools;36.0.0`, NDK
  `ndk;30.0.16248370`, and `platform-tools` for ADB.

Install SDK components through Android Studio's SDK Manager or its command-line
tools, accepting licenses interactively:

```sh
sdkmanager 'platforms;android-37' 'build-tools;36.0.0' \
  'ndk;30.0.16248370' 'platform-tools'
sdkmanager --licenses
rustup target add aarch64-linux-android
```

Set `ANDROID_HOME` to your SDK if it is outside `~/Library/Android/sdk` on macOS.
On Linux set `ANDROID_HOME` and `JAVA_HOME` explicitly. The native helper also
accepts `ANDROID_NDK_HOME`. SDK paths in `rexlink-android/local.properties` are
machine-local and ignored by Git. No toolchain or SDK installation is committed.

The wrapper selects Android Studio's bundled JDK on macOS and changes into the
Android project. To select another JDK on macOS, run `./gradlew` directly from
`rexlink-android/` with the desired `JAVA_HOME`.

## Android APK

```sh
./rexlink-android/scripts/gradle.sh :app:assembleDebug
./rexlink-android/scripts/gradle.sh :app:assembleRelease
```

Open `rexlink-android/` in Android Studio. Gradle runs the native build helper
automatically. Ghostty revision, source checksums, Zig downloads and NDK version
are pinned in `rexlink-core/native/ghostty-source.json`. Rust dependencies share
`rexlink-core/Cargo.lock`. The AGP, Kotlin/Compose, Gradle and AndroidX versions
remain pinned by the Android project.

Release output: `rexlink-android/app/build/outputs/apk/release/app-release.apk`.
Debug output: `rexlink-android/app/build/outputs/apk/debug/app-debug.apk`.

Release uses R8, resource shrinking and packaged ART profiles. It currently signs
with the existing local development key so it can update this prototype without
clearing data. For Play Store or long-term public distribution, configure a
dedicated signing key in the Android project; keep keys and passwords outside Git.
The package and JNI ABI were intentionally retained during the repository move.

Connect a USB-debugging-authorized phone, or launch an arm64 emulator:

```sh
adb devices -l
adb -s <serial> install -r rexlink-android/app/build/outputs/apk/release/app-release.apk
```

Keep Tailscale active on the device when accessing a private tailnet Rex server.
Building the APK does not contact or change a Rex server.

## Rust probe without Android or Ghostty

```sh
cargo build --locked --manifest-path rexlink-core/Cargo.toml -p rex-probe
rexlink-core/target/debug/rex-probe --help
rexlink-core/target/debug/rex-probe -S https://your-rex-host sessions
```

The probe's optional `ghostty` feature enables `rex-view`. Build the host Ghostty
library first with the helper below, then use `--features rex-probe/ghostty` for
workspace commands. Protocol-probe usage and evidence are in
`rexlink-core/probe/README.md` and `rexlink-core/probe/PROTOCOL.md`.

## Checks without a phone

```sh
# Host Ghostty, all Rust workspace tests/features, and actual JNI/JVM smoke test:
python3 rexlink-android/scripts/test_jni.py

GHOSTTY_PREFIX="$PWD/rexlink-core/.build/ghostty-host" \
  CARGO_TARGET_DIR="$PWD/rexlink-core/.build/rust" \
  cargo clippy --locked --manifest-path rexlink-core/Cargo.toml \
    --workspace --all-targets --all-features -- -D warnings

./rexlink-android/scripts/gradle.sh :app:testDebugUnitTest :app:lintRelease
python3 rexlink-android/scripts/verify_apk.py \
  --apk app/build/outputs/apk/release/app-release.apk --require-release
```

The APK verifier interprets its `--apk` path relative to `rexlink-android/`.
Build caches and native outputs stay under `rexlink-core/.build/`, Rust's default
`rexlink-core/target/`, and the Android project's ignored Gradle/build directories.

Device UI checks (use an emulator or a dedicated test device):

```sh
ANDROID_SERIAL=<serial> ./rexlink-android/scripts/gradle.sh :app:connectedDebugAndroidTest
```

The test runner may replace/uninstall its target app and data. Some multi-server
tests require explicit owned-server fixture arguments; see
`rexlink-android/TESTING.md`. Live Rust tests are opt-in, create disposable
sessions, and remove only those fixtures:

```sh
REX_TEST_SERVER=https://your-owned-rex-host \
  GHOSTTY_PREFIX="$PWD/rexlink-core/.build/ghostty-host" \
  CARGO_TARGET_DIR="$PWD/rexlink-core/.build/rust" \
  cargo test --locked --manifest-path rexlink-core/Cargo.toml \
    -p rexlink-jni live_activity_ -- --ignored
```

Historical validation documents retain commands and paths as they were when
recorded. Use this guide for the current monorepo paths. No server configuration
or authentication change is required by the migration.
