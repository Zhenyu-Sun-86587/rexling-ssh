# Rex Android

A native Android client for the user's Superlogical Rex server. Kotlin/Compose
supplies the UI and a Canvas terminal view. A Rust actor owns the direct
authenticated WebSocket connections and a native `libghostty-vt` terminal replica.
There is no bridge, proxy, embedded Rex server, or `rex` executable dependency.

This first build targets **arm64-v8a**, including the Galaxy S22+. It was compiled
and checked on the Mac, then installed and run on the **API 37 arm64 emulator**.
A live authenticated Rex terminal was displayed with control mode and the Android
keyboard. The Galaxy S22+ has not been tested yet.

## Build and open

Open this directory in Android Studio. Prerequisites used for the verified build:

- Android SDK platform **37.0**, Build Tools **36.0.0**, NDK **30.0.16248370**.
- Rust **1.93** or newer; the build adds `aarch64-linux-android` if missing.
- Android Studio's bundled JDK; the tested installation supplied JDK 25.
- Python 3.9+ and `curl`.

```sh
./scripts/gradle.sh assembleDebug
```

The APK is produced at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

The Gradle `preBuild` task runs `scripts/build_native.py`. It downloads
checksum-verified Ghostty/Zig source and tools, cross-compiles Ghostty, builds the
Rust JNI library, and packages the arm64 libraries. Source downloads, compiler
caches and native outputs stay under ignored `.build/` and `app/src/main/jniLibs/`.
The initial build needs network access. No sibling repository is required; an
existing `rex-probe` download cache is used only as an optional optimization.

`scripts/gradle.sh` selects Android Studio's bundled JDK on macOS and discovers the
standard SDK location. It does not modify global Java or shell settings. The
native helper also finds Rust under `~/.cargo/bin` for Android Studio GUI builds.
Set `ANDROID_HOME` or `ANDROID_NDK_HOME` for custom SDK/NDK locations. To use a
custom JDK on macOS, invoke `./gradlew` directly with the desired `JAVA_HOME`.

Build versions are pinned: AGP 9.1.1, Gradle 9.3.1 with distribution checksum,
AGP's built-in Kotlin 2.2.10 and matching Compose compiler. The AndroidX versions
are a deliberately fixed working set; lint may report newer versions.

## First run, when the phone is ready

1. Keep Tailscale connected on the phone and ensure this app is included in its
   VPN routing. The app uses the Android network stack and your existing tailnet
   identity; it does not embed a second VPN or send identity headers.
2. Install the debug APK through Android Studio, or later run
   `adb install -r app/build/outputs/apk/debug/app-debug.apk` yourself.
3. Open **Rex**. Connect to the prefilled server URL, or enter another explicit
   `https://`, `http://`, `wss://`, or `ws://` Rex server address.
4. The terminal has one compact top strip. **‹** opens the session picker;
   the selected tab shows the session name underneath its title (tap that name
   to switch sessions too). Tap another tab to switch Rex windows, or tap the
   selected tab title to open its pane picker. Each tab remembers its last pane.
5. **Swipe left/right across the terminal** for the next/previous pane in that
   tab. The floating bars and `2/3` label show your position; tap the indicator
   for the pane picker. Swipes stop at the first/last pane. Vertical drags and
   multi-finger gestures do not switch panes. Switching releases the old claim.
6. **+ → New tab** starts a new shell in a Rex window. **+ → New pane** starts
   a shell beside the current pane in the server's layout, then opens it here.
   These create real server resources, visible to other Rex clients. Creation
   uses the server's default shell and is never automatically replayed after a
   lost reply. Check the tabs/panes before retrying an uncertain creation.
7. Tap **Type** in the bottom key row to take control and fit the terminal to
   the viewport. The keyboard icon reopens the IME while controlled. **⋮ →
   Release terminal control** returns to viewing. Esc, Ctrl, Alt, Tab and arrows
   remain in the compact key row. Navigation stays available with the IME open.
8. **⋮** also contains server settings, status, dimensions, font size, copy,
   paste and refresh. Terminal text defaults to **10 sp**; the font-size menu
   cycles through 8, 10, 12, 14, 16 and 18 sp.

**Try preview** restores a captured disposable test stream locally. It displays
an actual Ghostty snapshot and later VT output without requiring a connection.
It is a read-only preview, not a simulated shell.

## Architecture

```text
Compose UI + TerminalView (Android Canvas / IME)
    │ JNI commands and revisioned display state
    ▼
Rust worker thread + current-thread Tokio runtime
    ├─ Rex control WebSocket: methods, identity, sessions, input, ownership
    ├─ Rex data WebSocket: binary snapshot + incremental VT, tickets/resume
    └─ libghostty-vt: snapshot import, VT parsing, screen cells/cursor
    │ direct connection through the phone's Tailscale VPN
    ▼
Existing authenticated Rex server
```

- `native/rex-core/`: verified protocol and terminal code vendored from the
  sibling investigation at commit `c816b81cff09c9a8da3c199329a93bcd10cd8480`.
  The mobile port removes CLI dependencies, identifies itself as an app, and
  adapts native linking. The source is self-contained here.
- `native/rex-jni/`: JNI handles, bounded command queue, connection/lifecycle
  recovery and display-state serialization. All connections and the non-Send
  Ghostty handle stay on one native owner thread.
- `native/ghostty-source.json`: pinned Ghostty revision
  `f54340ad77814fe319d136e5e915d6a48de8dd23`, Zig 0.17.0, URLs and checksums.
- `app/`: Compose session picker and a Canvas view of Ghostty's cells. JNI state
  is polled only while foregrounded and is transferred only when its revision
  changes. The renderer preserves cell RGB colors, common styles, graphemes,
  wide-cell spacing and cursor position.

The server's terminal answers PTY queries; the replica installs no reply callback.
Rex APCs are parsed at Ghostty's parser boundaries and accepted only with the
channel's exact u64 nonce. Initial snapshots are buffered through FINISH, bounded
to 128 MiB, then validated by Ghostty. The investigated server API range is 7–8;
future snapshot/API compatibility must be verified rather than assumed.

When backgrounded, the worker drops connections abnormally and stops paint and
heartbeat timers. Foregrounding attempts control/data resume, refreshes session
state, and falls back to a fresh snapshot if needed. Input with an uncertain
outcome is never automatically resent. Terminals keep running on Rex while the
app is away. There is no background/foreground service.

Only the server URL is persisted. Ticket/reconnect tokens remain in native
memory and are not returned by JNI. Cloud backup and device transfer of app data
are excluded. The APK requests Internet access, with no analytics or account SDK.
Plain HTTP is supported for an already secured tailnet listener; the app never
changes server authentication or listener configuration.

## Checks without a device

```sh
./scripts/gradle.sh testDebugUnitTest lintDebug
python3 scripts/test_jni.py
python3 scripts/verify_apk.py

# Optional host integration against an explicitly selected owned server.
# Creates and destroys only a disposable test session.
REX_TEST_SERVER=https://rex.taildaac1.ts.net \
  GHOSTTY_PREFIX="$PWD/.build/ghostty-host" CARGO_TARGET_DIR="$PWD/.build/rust" \
  cargo test --locked --manifest-path native/Cargo.toml --workspace -- --include-ignored
```

The JNI smoke test uses a host JVM and the same native entry points as Kotlin.
It checks handle lifetime, the worker, real snapshot decoding, Unicode transfer,
secret exclusion and cleanup. It is not an Android runtime test. APK verification
checks debug signing, arm64 ELF files, the four JNI exports, 16 KiB load/ZIP
alignment, packaging, and manifest permissions. See [VALIDATION.md](VALIDATION.md)
and the verified creation methods in [PROTOCOL.md](PROTOCOL.md).

## Remaining device work

The APK is built, not yet proven on the S22+. Verify soft-keyboard/IME composition,
hardware keyboard behavior, font/emoji rendering, insets, rotation, Tailscale DNS,
network loss and foreground/background transitions on the real phone.

The current UI shows the active viewport. Scrollback navigation, touch selection,
mouse reporting, graphics rendering, modern keyboard-protocol encoding and a
production foreground-session policy are not implemented. Host tests cover the
core/lifecycle logic. The emulator smoke run covers launch, native rendering and
a live connection; it does not replace real-phone lifecycle/network-loss tests.

Ghostty's MIT license is retained in `native/GHOSTTY_LICENSE.txt` and packaged in
the APK assets. Rust and AndroidX dependencies are declared in Cargo.lock and
Gradle with their upstream licenses. This is an unofficial client prototype.
