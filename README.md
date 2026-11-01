# Rexlet

**Unofficial Android client for Rex.**

Rexlet connects directly to the user's Superlogical Rex server. Kotlin/Compose
supplies the UI and a Canvas terminal view. A Rust actor owns the direct
authenticated WebSocket connections and a native `libghostty-vt` terminal replica.
There is no bridge, proxy, embedded Rex server, or `rex` executable dependency.

This build targets **arm64-v8a**, including the Galaxy S22+. The latest mockup
build was installed on the S22+ over USB. Its bundled terminal font passed a
real-phone instrumentation test; interaction and appearance smoke checks ran on
the API 37 arm64 emulator.
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
4. The header is a sideways session strip with neighboring names and position
   dots. Swipe it to change sessions, tap the name to search the session list,
   or long-press to jump to the last session. The list puts the current session
   first, marks the last one, and offers **New** to create a session and shell.
   Attachment status and tab/pane counts come from Rex; no remote last-used
   timestamps are fabricated when the server doesn't supply them.
5. **Swipe the terminal card** to move between panes in the current tab. Adjacent
   cards use real read-only Ghostty snapshots; the selected pane streams live.
   The footer dots show pane position; the temporary swipe badge shows the destination program icon. The split icon in
   the bottom dock opens the pane list. Switching releases the old claim.
6. Tabs live in the bottom dock above the keyboard controls. Each whole tab is
   a single selectable target and remembers its pane. A full-width underline
   marks the active tab; touch feedback keeps rounded clipping. Multi-pane tabs stack their program icons, with the
   remembered pane in front. **+ → New tab / New pane**
   starts a real shell on Rex. These resources are visible in other Rex clients.
   Creations are sent once, never automatically replayed after an uncertain reply.
7. **Take over** enables typing and fits the terminal to the viewport. **Release**
   returns to viewing. The bottom keys provide esc, ctrl, alt, tab and arrows;
   scroll the key row for additional arrows. The keyboard button opens/hides the
   IME. Input stays blocked while a different pane's snapshot is still loading.
8. **⋮ → Appearance** chooses **System**, **Dark**, or **Light**, and saves the
   choice. Light uses blue accents, cool gray surfaces and white terminal cards;
   Dark uses blue accents and charcoal surfaces. The selected pane receives Rex's `set_theme`
   request, allowing Neovim to follow terminal appearance. Rex applies it while
   this device owns the pane or it is unowned; another owner's theme stays active
   until you take over. Explicit RGB from programs is preserved. Neovim configs
   that explicitly force `background` or a fixed colorscheme may override this.
   The menu also provides copy/paste, connection settings and font sizes 8–18 sp.
   The default is 10 sp in bundled JetBrains Mono NL, independent of OEM fonts.

Swipe vertically to browse the selected terminal's real Ghostty scrollback,
including history imported in the initial Rex snapshot. Drag/fling, a mouse
wheel, and Shift+PageUp/PageDown scroll locally without taking ownership.
**↓ Latest** jumps to live output; typing/pasting also returns to the bottom.
Alternate-screen apps have their own scrolling and no terminal scrollback.

Terminal cards span the screen width, aligned with the dock. Their 6 dp side /
8 dp top content insets extend the adjacent terminal cell backgrounds at each
edge, including separate sidebar/editor colors. The cursor is a filled block.
The header program tile uses a contrasting program-colored surface.

The compact header shows the selected program, its working directory (`~` for
its real home), and the session name underneath. The header and tab icon stacks
only appear for tabs with more than one pane. The session name still supports
tap to open the picker, sideways swipe to cycle, and long-press to jump to last.

Bundled logos cover Claude Code, Codex (OpenAI logo), Neovim, Vim, Git, Python
and Node.js. Git's logo also covers lazygit/tig; the Node icon covers
npm/pnpm/yarn/Bun/Deno. SSH/mosh, tmux/zellij/screen and top/htop/btop have category
symbols. Other programs and shells use the terminal fallback. Detection uses
Rex's foreground process and matching-owner program reports, never tab titles
or screen-text guessing. Programs inside SSH or a multiplexer may show the
outer program's icon. Metadata refreshes approximately every four seconds,
with earlier refresh after selected-pane process/status events.

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

The server URL, appearance choice, and current/last session IDs are persisted. Ticket/reconnect tokens remain in native
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

The font grid was verified on the S22+, and the build was installed there. Continue testing soft-keyboard/IME composition,
hardware keyboard behavior, font/emoji rendering, insets, rotation, Tailscale DNS,
network loss and foreground/background transitions on the real phone.

The current UI shows the active viewport. Scrollback navigation, touch selection,
mouse reporting, graphics rendering, modern keyboard-protocol encoding and a
production foreground-session policy are not implemented. Host tests cover the
core/lifecycle logic. The emulator smoke run covers launch, native rendering and
a live connection; it does not replace real-phone lifecycle/network-loss tests.

JetBrains Mono NL 2.304 is bundled with its OFL license, pinned revision and
checksums in the APK license assets.

Ghostty's MIT license is retained in `native/GHOSTTY_LICENSE.txt` and packaged in
the APK assets. Rust and AndroidX dependencies are declared in Cargo.lock and
Gradle with their upstream licenses. This is an unofficial client prototype.

The typography regression can be run on a selected connected device with:

```sh
ANDROID_SERIAL=<device-id> ./scripts/gradle.sh connectedDebugAndroidTest
```


The launcher uses the user-selected voxel dinosaur as an Android adaptive icon.
Original artwork, checksums and built-in imagegen preparation/cleanup prompts
are in `artwork/rexlet/`; the transparent foreground is packaged in
`app/src/main/res/drawable-nodpi/rexlet_dinosaur.png`.
