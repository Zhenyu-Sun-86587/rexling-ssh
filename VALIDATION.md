# Build validation

Verified on the development Mac and **API 37 arm64 Android emulator** on
**2026-11-01**. The app was installed and launched on `Medium_Phone_API_37.0`
(`emulator-5554`). The S22+ received the latest build over USB and passed the
terminal-font instrumentation check. No Rex server settings were changed.
Older layout descriptions below are retained as historical validation notes.

| Check | Result |
| --- | --- |
| Android arm64 Ghostty + Rust JNI compilation | Passed |
| Emulator installation and launch | Passed; cold launch completed |
| Emulator native terminal preview | Rendered captured snapshot, colors, Unicode and cursor |
| Emulator live connection | Authenticated server connection and live terminal visible; control mode and keyboard observed |
| Emulator crash log | No AndroidRuntime errors or libc fatal entries during smoke run |
| `assembleDebug` | Passed; debug APK signed |
| Kotlin model/navigation/appearance JVM tests | 16 passed |
| Native offline unit tests | 10 passed |
| Explicit real-server actor host tests | 2 passed on the existing Tailscale host; lifecycle and tab/pane creation |
| Actual JNI bridge on host JVM | Passed in earlier validation |
| S22+ typography instrumentation | 1 passed; four bundled styles at every supported size |
| Latest mockup APK installed on S22+ | USB install returned Success |
| Android `lintDebug` | No errors; version/target-scope suggestions remain |
| Native Clippy with warnings denied | Passed |
| JNI exports and packaged ABI | Four expected JNI exports; arm64-v8a only |
| 16 KiB ELF and APK ZIP alignment | Passed |

The actor integration test created an isolated shell, connected the app's native
worker directly, selected its session/block, took ownership, sent Unicode input,
resized, paused, generated output during the gap, resumed, released control and
disconnected. Cleanup destroyed the disposable session. Existing user terminals
were not used for test input.

The host JVM test loaded the real JNI library, created a worker, requested the
offline stream replay, received Unicode and screen state, verified no ticket or
reconnect-token fields reached the UI, rejected invalid input, and destroyed the
handle safely. The Kotlin compiled native method declarations were inspected and
match the exported symbols.

The packaged native libraries are the Rust/Ghostty JNI library, the NDK C++
runtime, and AndroidX's graphics-path library. Each has 16 KiB-compatible load
segments, and each is stored uncompressed with the required APK alignment. The
debug signature was verified with the SDK tools.

Artifact details and checksum are recorded in [validation/apk.json](validation/apk.json).
Re-run `python3 scripts/verify_apk.py` after rebuilding; debug build bytes and
checksums can change. Build caches, native binaries and APKs are intentionally
ignored by Git; the source, wrapper, lockfile, fixtures and build recipes are
tracked.

The first emulator run revealed that Canvas background drawing escaped the
terminal viewport and covered the header. Explicit Canvas clipping fixed it;
the updated live view retained the header and terminal controls. System bars
were also switched to light icons for the dark app theme. The rebuilt APK,
Kotlin tests and lint passed after these changes.

The emulator reached the existing Tailscale server through its host networking
without a guest VPN setup. This does not establish phone-side VPN/DNS behavior.
The live UI smoke run was observed manually; no comprehensive emulator test suite
was run. IME composition, real-phone rendering, rotation, network loss and
background/process-death behavior still need testing on the S22+.

## Compact navigation update

The normal terminal layout now uses a 44 dp session/pane/control header, a 36 dp
scrollable window tab strip, and a 40 dp terminal key row. The title, dimension
row, session/pane chip rows and routine status footer no longer take shell space.
Navigation remains visible while the keyboard is open. Server and terminal
options live in the overflow menu.

The rebuilt app was installed on the same API 37 emulator. A disposable test
session contained an Editor tab with two panes and a Logs tab with one pane;
a second disposable session exercised cross-session switching. ADB-driven UI
checks confirmed selecting a session, changing tabs and panes, restoring the
last pane when returning to a tab/session, and access to all top navigation
controls while the IME was open. Input sent through the app produced
`REPLY:compact-input-check` in the disposable shell, verified using the independent
probe's snapshot command. Both test sessions were destroyed afterward.

The IME-visible terminal viewport measured `[0,274][1080,1323]` on the emulator;
the terminal reported 49 columns by 22 rows at the 14 sp font used for that check. The prior
layout's observed IME-visible terminal was 49 by 17. Navigation history unit tests
also cover removed panes/windows and empty windows. `assembleDebug`, all five
Kotlin tests and `lintDebug` passed; lint has no errors. The crash log contained
no AndroidRuntime error or libc fatal entries during these checks.

## Swipe panes and single-strip navigation

The latest layout replaces the former 44 + 36 dp navigation rows with one 48 dp
strip: session access, scrollable tab pills, a + creation menu and overflow. The
40 dp key row remains. Pane position is overlaid on the terminal without taking
viewport height. A newly selected tab scrolls into view. The default font is now
10 sp (the earlier 14 sp viewport measurements above are historical).

Nine Kotlin tests pass, including horizontal gesture direction, taps vs short
drags, vertical/cancelled gesture rejection, same-tab neighbours, edge clamping,
and pane history/fallbacks. The native suite passes eleven tests: eight core,
one preview and two real-server actor tests. The new actor test creates a tab
and split pane, opens their snapshots, checks the resulting window/pane counts,
and rejects stale session/anchor IDs before mutation. It destroys its disposable
session afterward. Native Clippy with warnings denied also passes.

On the API 37 emulator, ADB swipes selected the next/previous panes and updated
the floating indicator. A vertical drag and a swipe before the first pane kept
the current pane. + New pane created and opened pane 2/3; + New tab created and
opened Terminal 3. Independent server inspection confirmed three windows with
3, 1 and 1 panes. Emulator input `printf ANDROID_TAB_OK` reached the new shell
and returned the marker in the independent probe's snapshot. The UI remained
usable with the IME open. Tests used only the disposable `android-swipe-test`
workspace; the user's pre-existing terminal received no test input.

The final polished build also verified swiping through all three panes, clamping
at the last pane, opening the picker from the selected tab title, and restoring
pane 2/3 after visiting another tab. Session access is a separate target on the
selected tab's subtitle and the left header button. Both the tab strip and native
view are clipped to their bounds. The app returned to the original workspace
and the disposable swipe workspace was destroyed. No Java/native crash entries
were present during these checks.

## Mockup delivery: carousel, appearance and sessions

The delivered UI follows the provided dark/light references: lime/olive accents,
compact session strip and takeover control, rounded terminal cards, pane swipe
feedback, bottom tabs and a dock of terminal keys. The session sheet provides
search, New, current-first ordering, last-session marking and gesture hints.
The session header supports sideways swiping and long-press return to last.

The S22+'s OEM monospace alias measured M at 19 px and i at 6 px at the tested
size. The bundled JetBrains Mono NL measured both at 14 px. The real-phone test
verified all printable ASCII advances in regular/bold/italic/bold-italic at
8, 10, 12, 14, 16 and 18 sp. Integer cell spacing and RGB/Unicode rendering were
also inspected in actual app captures. The phone was left available for user
use; remaining scripted UI checks ran on the emulator.

Emulator captures verified Light and Dark terminal cards, the session sheet
with actual attachment/count metadata, long-press last-session switching, direct
bottom-tab taps and an animated pane swipe showing PANE_B. Off-screen frames are
read-only snapshots, not continuously streamed replicas. Only the selected pane
is live. Explicit application RGB colors remain unchanged by appearance mode.

The owned-server actor test also validates read-only neighbor snapshot retrieval
without changing the selected block or ownership, session counts/attachment
metadata, and creation of a new session and shell. Each host test cleans up its
own sessions. Navigation request IDs guard rapid tab changes and prevent typing
until the matching snapshot is ready. The core tests cover exact theme color
roles. Native tests total 12 (10 offline plus 2 owned-server tests); Clippy passes
with warnings denied. Android build, all 16 JVM tests and lint pass.

The mockup debug APK was installed over USB on RFCT21390SD (Galaxy S22+) after
reconnection, with ADB reporting Success. No program-icon work is included.


## Neovim fixes and blue light theme

The previous renderer failed two pixel regressions: a uniform explicit RGB
background developed seams at quarter/half/three-quarter pixel canvas offsets,
and a wide glyph's default-colored spacer erased half its background. Cell
backgrounds now use a separate non-antialiased Paint and merge equal contiguous
row fills. Wide leading cells cover their spacers. Carousel translation is
snapped to whole pixels. Text retains antialiasing and the bundled font grid.
Both background regressions plus the typography test pass on the emulator
(3 instrumentation tests). The tests failed before the renderer fix.

All 13 native tests pass: 10 offline tests plus 3 owned-server integration tests.
The new integration runs real Neovim 0.12.6 and verifies `&background` changes
through Rex `set_theme`, deferred non-owner appearance, takeover, and lifecycle
pause/resume. All test sessions, including the version-probe session, were
cleaned up. Clippy with warnings denied, all 16 Kotlin JVM tests, Android debug
build and lint pass. APK signature and 16 KiB alignment pass.

Light now uses blue #3156f4 accents with cool gray chrome and white terminal
cards. The emulator Appearance picker and a captured preview visually confirm
the new palette. The redundant `/ shell` header label, visible pane fractions
and “swipe to switch” text are removed. Dots remain tappable to open the pane
picker; accessibility descriptions still identify the pane position. The
explicit Take over/Release control remains.

The final APK was installed via USB on RFCT21390SD (Galaxy S22+, SM_S906E),
with ADB reporting Success. No phone UI automation or program-icon work was
performed for this delivery. `validation/apk.json` records the installed APK.
