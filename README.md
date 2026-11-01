# Rexlink

**Unofficial Android client for Rex.**

Rexlink connects directly to the user's Superlogical Rex server. Kotlin/Compose
supplies the UI and a Canvas terminal view. A Rust actor owns the direct
authenticated WebSocket connections and a native `libghostty-vt` terminal replica.
There is no bridge, proxy, embedded Rex server, or `rex` executable dependency.

This build targets **arm64-v8a**, including the Galaxy S22+. The latest optimized
release build was installed on the S22+ over USB. Base-font checks passed earlier
on the real phone; current Nerd Font, renderer, networking and interaction
regressions run on the API 37 arm64 emulator.

## Build and open

Open this directory in Android Studio. Prerequisites used for the verified build:

- Android SDK platform **37.0**, Build Tools **36.0.0**, NDK **30.0.16248370**.
- Rust **1.93** or newer; the build adds `aarch64-linux-android` if missing.
- Android Studio's bundled JDK; the tested installation supplied JDK 25.
- Python 3.9+ and `curl`.

```sh
./scripts/gradle.sh assembleRelease
# Development build with instrumentation support:
./scripts/gradle.sh assembleDebug
```

The release APK is produced at:

```text
app/build/outputs/apk/release/app-release.apk
```

Local release builds use R8 code/resource optimization, packaged Baseline/Startup
Profiles, and the existing development signing identity so they can update this
prototype without clearing its data. They are non-debuggable. Production signing
can be configured separately when publishing. The `optimized` variant has the
same optimization settings plus shell profiling for benchmarks.

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
   `adb install -r app/build/outputs/apk/release/app-release.apk` yourself.
3. Open **Rexlink**. Connect to the prefilled server URL, or enter another explicit
   `https://`, `http://`, `wss://`, or `ws://` Rex server address.
4. **⋮ → Servers** saves additional hosts and keeps them connected together.
   Switch hosts in that sheet, or swipe the header session name through all
   sessions across connected servers, grouped in saved-server order. Server
   names distinguish the sessions. Tap the name to search the combined list,
   or long-press to jump to the last session, including on another server. The list searches both session and server names, puts the current session
   first within the current server group, marks the previous one with plain text, and offers **New** to create a
   session and shell. Swipe a list row either way to reveal **Close**, or use its
   ⋮ menu. Confirmation names the session and server: closing ends every program
   in that session. Switching away keeps programs running; Disconnect belongs
   to the server sheet and only closes this app's connection.
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
7. **Tap terminal content** to take control, fit the terminal to the phone and
   open the keyboard. **Take over** remains available in the header. **Release**
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
   The default is 10 sp in bundled JetBrainsMonoNL Nerd Font Mono, independent
   of OEM fonts. Powerline, devicons and Nerd Font symbols use one terminal cell;
   the shell/editor must emit them. No font installation is needed on the server.

Swipe vertically to browse the selected terminal's real Ghostty scrollback,
including history imported in the initial Rex snapshot. Drag/fling, a mouse
wheel, and Shift+PageUp/PageDown scroll locally without taking ownership.
**↓ Latest** jumps to live output; typing/pasting also returns to the bottom.
Full-screen apps that enable mouse reporting receive mode-correct wheel events
from vertical drags or a mouse wheel; this takes control without opening the IME.
Ordinary shell history stays local and needs no ownership. Wider remote grids
fit the card while viewing, then resize to the requested font grid on takeover.

Terminal cards span the screen width, aligned with the dock. Their 12 dp side /
8 dp top content insets extend the adjacent terminal cell backgrounds at each
edge, including separate sidebar/editor colors. The cursor is a filled block.
The header and tab program tiles use matching reflective faces, thin rims and
program colors. Their compact rectangular faces have white rims. These are
gradient highlights, without live backdrop blur.

The 50 dp header shows the selected program, its working directory (`~` for
its real home), and the session name underneath. Multiple panes stack their program tiles; single-pane tabs show one smaller tile. The session name still supports
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
    │ JNI commands + notified binary row updates
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
  Ghostty handle stay on their server's native owner thread.
- `native/ghostty-source.json`: pinned Ghostty revision
  `f54340ad77814fe319d136e5e915d6a48de8dd23`, Zig 0.17.0, URLs and checksums.
- `app/`: independent RexConnection instances retain a native actor and terminal
  state for each saved connected server. The RexModel coordinator routes
  server-qualified session keys to their owning connection; only actual session
  IDs reach Rex. Hidden-view input callbacks cannot type into another host.
  Switching releases the old claim while retaining its connection and viewport.
  Lifecycle pause/resume applies to every connected host.
- `app/`: Compose session picker and a Canvas view of Ghostty's cells. JNI state
  uses revision notifications and binary deltas for live screen rows. Metadata
  and live terminal flows are separate, so terminal output does not recompose
  the header or session catalog. Visible delivery follows the display clock;
  hidden hosts keep their streams but skip screen extraction until selected.
  The renderer caches unchanged row drawing commands and glyph advances, batches
  compatible ASCII runs, and preserves cell RGB colors, common styles, graphemes,
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

Saved server names/URLs, desired connected hosts, the active server, appearance,
and current/last session IDs are persisted. Disconnect affects one host; Forget
removes its local profile and never deletes remote sessions. Ticket/reconnect tokens remain in native
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
checks APK signing, arm64 ELF files, the six JNI exports, 16 KiB load/ZIP
alignment, packaging, and manifest permissions. See [VALIDATION.md](VALIDATION.md)
and the verified creation methods in [PROTOCOL.md](PROTOCOL.md).

## Remaining device work

The font grid was verified on the S22+, and the build was installed there. Continue testing soft-keyboard/IME composition,
hardware keyboard behavior, font/emoji rendering, insets, rotation, Tailscale DNS,
network loss and foreground/background transitions on the real phone.

Touch selection,
mouse reporting, graphics rendering, modern keyboard-protocol encoding and a
production foreground-session policy are not implemented. Host tests cover the
core/lifecycle logic. The emulator smoke run covers launch, native rendering and
a live connection; it does not replace real-phone lifecycle/network-loss tests.

JetBrainsMonoNL Nerd Font Mono 3.5.1 (base JetBrains Mono 2.304) is bundled
in regular/bold/italic/bold-italic, with its OFL, Nerd Fonts license/attributions,
release URL and checksums in the APK license assets. UI labels retain the smaller
unpatched JetBrains Mono family.

Ghostty's MIT license is retained in `native/GHOSTTY_LICENSE.txt` and packaged in
the APK assets. Rust and AndroidX dependencies are declared in Cargo.lock and
Gradle with their upstream licenses. This is an unofficial client prototype.

The typography regression can be run on a selected connected device with:

```sh
ANDROID_SERIAL=<device-id> ./scripts/gradle.sh connectedDebugAndroidTest
```


The launcher uses the user-selected voxel dinosaur as an Android adaptive icon.
Original full-body artwork, checksums and the built-in imagegen preparation
prompt are in `artwork/rexlink/`; its transparent foreground is packaged in
`app/src/main/res/drawable-nodpi/rexlink_dinosaur.png`. Prior artwork is retained
in `artwork/rexlet/`.


## Performance checks

`validation/performance.json` records emulator measurements, workload definitions
and limits. A fixed 100×50 dense screen with one changing row reduced software
Canvas drawing from about 8.1 ms to 0.84 ms median. This is a renderer measurement,
not a whole-app or physical-phone speed multiplier. Input-to-Android-state
latency is measured separately against disposable owned fixtures and includes
the network; it does not measure the final displayed pixel. Macrobenchmark
captures cold startup and terminal gesture frame timing, with Perfetto traces.
The emulator showed no material startup difference from profiles alone.

Reproduce on a dedicated emulator (Gradle's test runner may uninstall its target
app and data; the profile helper deliberately rejects phone serials):

```sh
python3 scripts/generate_profiles.py --serial emulator-5554
./scripts/gradle.sh assembleOptimized assembleRelease
ANDROID_SERIAL=emulator-5554 ./scripts/gradle.sh :benchmark:connectedOptimizedAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.shulhi.rex.benchmark.TerminalBenchmark \
  -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR
python3 scripts/verify_apk.py --apk app/build/outputs/apk/release/app-release.apk --require-release
```

Profiles come from the actual offline startup → terminal → appearance journey.
`app/src/main/baseline-prof.txt` and `startup-prof.txt` contain the measured rules;
R8 rewrites and compiles them into the release APK. Fresh test-emulator preferences
are required for the offline journey. Benchmark output lives under ignored
`benchmark/build/outputs/connected_android_test_additional_output/`.
For representative startup/frame timing, use a separate physical test device;
these emulator results do not establish S22+ frame-rate or battery guarantees.


The benchmark setup follows Android's [Macrobenchmark guidance](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview)
and [Baseline Profile guidance](https://developer.android.com/topic/performance/baselineprofiles/create-baselineprofile).

Server groups show the current host first and retain search and close actions.
Saved connection profiles are local to this app; server icon/name customization
is not synchronized with the macOS client. The application label is Rexlink and
the package stays `dev.shulhi.rex`, preserving existing installations and settings.
