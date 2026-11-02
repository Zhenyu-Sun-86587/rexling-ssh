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
| Kotlin model/navigation/appearance JVM tests | 31 passed |
| Native offline unit tests | 16 passed |
| Explicit real-server actor host tests | 6 passed, including separate Mac/Linux hosts and session closing |
| Actual JNI bridge on host JVM | Passed in earlier validation |
| Android instrumentation | 11 emulator tests passed; earlier base-font check passed on S22+ |
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


## Rexlet branding, icons and revised header

The first program-icon APK was installed on RFCT21390SD before header work,
as requested (ADB Success; SHA256
`1b9815c34a9589a2d846e54d75f628c4a5dd38d3f344be5a616d1b84039e3f71`).
It included blue dark mode, 6 dp horizontal / 8 dp top terminal-card padding,
neutral menus and the centered + button. The + fix centers the 36 dp control
inside its 41 dp circular background. Surface container/accent roles are explicit
and dropdowns use the app surface with zero tonal elevation.

The later build is named Rexlet, with the connection-screen subtitle
“Unofficial Android client for Rex.” The package/application ID is unchanged.
AAPT and the APK verifier confirm the launcher label. The launcher terminal
mark uses blue; Rex control connections identify their app as rexlet.

The revised header shows a selected program icon/name and cwd, with the session
below. Multi-pane tabs use real per-pane icon stacks in the header and bottom
tabs; single-pane tabs have one icon. The active bottom tab uses a full-width blue underline,
with touch feedback clipped to rounded tab bounds. Pane swipe feedback
includes the destination program icon and the footer shows dots alone.

Emulator checks verified both appearances, the new header, multi-pane stacks,
a swipe from Neovim to a Claude report fixture, pill selection, session picker,
app branding, and neutral menus. Native read-only metadata recognized actual
Neovim 0.12.6. Claude/Codex visuals used explicit disposable OSC 7501 fixtures;
no agent account or existing user terminal was driven. Saved-session startup
now restores the requested session: the pager's initial page emission no longer
overrides persisted selection. Phone interaction tests were not performed.

All 20 Kotlin JVM tests and all 16 native tests pass (12 offline, 4 owned-server
integrations). Clippy with warnings denied, Android debug build and lint pass.
The renderer/typography instrumentation suite passed all 3 emulator tests during
this delivery. APK signature, arm64 libraries and 16 KiB alignment pass. All
changes are committed by scope. The only unrelated untracked file is Android
Studio's `gradle/gradle-daemon-jvm.properties`.


## Scrollback, full-width cards and final voxel icon

The requested voxel dinosaur supersedes the earlier smooth illustration. Its
original is preserved in `artwork/rexlet/source.png`. Built-in imagegen extracted
the transparent foreground and cleaned alpha edges; both prompts and checksums
are recorded alongside it. Android applies the white background and launcher
mask. The packaged adaptive icon is inflated/drawn in instrumentation and its
rendered preview is visually inspected.

Cards now span the screen width. Internal padding extends adjacent visible-cell
backgrounds through all four edges. The emulator's two-color Neovim split verifies
that the sidebar's color extends left/top while the editor's different color
extends right/top. The cursor is filled with a contrasting glyph. Header icon
tiles have distinct dark program colors, bright glyphs, border and subtle shadow.
The active tab underline spans its tab; the permanent pill fill is removed while
pressed feedback remains rounded.

Real-server history and viewport round-trip tests pass. Emulator touch scrolling
shows old HISTORY lines and the Latest button, then returns to live output on tap.
Vertical drag does not switch panes or type. Instrumentation covers adjacent
padding colors, dimensions excluding insets, filled cursor, original cell-seam
and wide-character regressions, typography, and launcher inflation (7 tests).
All 21 JVM tests and 17 native tests pass (13 offline, 4 owned-server integrations).
Clippy, debug build, lint, signature and 16 KiB alignment pass. UI tests only use
disposable sessions, which are removed after validation.


Final Rexlet voxel-icon APK installed via USB on Galaxy S22+ RFCT21390SD with
ADB Success. SHA256:
`d596333eb0a8d1f1957dc45797c6f3821b777c634582f802696d7625fecf932b`.
`validation/apk.json` records the signed installed artifact. After replacing the
artwork, the launcher/gesture instrumentation class passed again and the actual
192 px adaptive launcher render was visually inspected. The final header also
renders its session chevron beside the session label.


## Nerd Fonts, reflective tiles and connected-server navigation

The terminal now bundles JetBrainsMonoNL Nerd Font Mono 3.5.1, base 2.304, in
four styles. The release archive SHA256 is
`04d5e8f903693f9dd13e16f867e994834e681eb3c72c0d337a770dcda09010cf`.
The source record, per-file hashes, licenses and attribution table ship in APK
assets. Emulator typography tests verify ASCII and Nerd Font glyphs including
Powerline, folder/terminal/devicons and supplementary U+F031D at all supported
sizes and styles, each using exactly one terminal cell. No server-side font
installation is required. Program tiles now share colored gradient faces,
reflective highlights, a thin rim and shadow in the header and tabs.

Saved servers retain independent native connections and terminal state. Real
Mac and Linux endpoints authenticated through existing Tailscale HTTPS listeners.
A native two-host regression verifies independent input, selection, disconnect
and lifecycle. Android ViewModel instrumentation verifies two live actors,
hidden-view input rejection, combined session selection, cross-server last-session
history and removal of disconnected hosts from the catalog. JVM tests cover
colliding session IDs on separate hosts, stable server grouping, disconnected
hosts, loading-location preservation and correct per-host attachment counts.
The previous test fixtures no longer existed at the first rerun; fresh owned
fixtures were created and the complete instrumentation suite then passed.

A real emulator header swipe crossed from the last Linux session to the first
Mac session without takeover or typing; long-press returned to the Linux test
session. The combined picker then selected the disposable Mac fixture. Dark and
Light screenshots were inspected only on the fixtures. The header is 50 dp
(previously 58 dp); front tiles are 30 dp in the header and 18 dp in tabs. The
full-width terminal card retains 12 dp inner side padding, and the active tab
uses a plain full-width 2 dp blue line with rounded touch feedback.

Final verification: 29 JVM tests, 9 Android emulator instrumentation tests,
18 native tests (13 offline + 5 live, unchanged native implementation), Clippy
with warnings denied, debug APK/test APK builds, lint, signing and 16 KiB
alignment all pass. The emulator's previous app preferences were restored and
only the two newly created disposable server sessions were destroyed. Existing
user sessions and server configuration remain intact. No phone UI automation
was performed for this delivery.

The final build was installed on Galaxy S22+ RFCT21390SD (SM_S906E) via USB;
ADB returned Success. APK SHA256:
`8c337154936573817467dd6fac58d3012f6654a3e651883f9b9731f7e171db00`.
`validation/apk.json` records this signed artifact (40996753 bytes).
Changes are committed separately for fonts, tile rendering, content spacing,
multiple connections, cross-server sessions, compact chrome and documentation.


## Performance pass, rectangular icons and session actions

The live display path uses condition-variable revision notifications and binary
row deltas instead of periodic full-screen JSON polling. Metadata and terminal
flows are separate; a live two-host assertion confirms that terminal output alone
does not publish new chrome state. One initial test raced the first program
metadata result; it now waits for that independent result before measuring screen
updates. Hidden hosts apply VT without extracting frames and publish a fresh
screen when selected. Native tests verify this without losing hidden output.
Handle destruction wakes waiting readers; JNI waits release the global registry
before blocking, so other server handles remain usable.

Terminal rows cache drawing commands. Compatible ASCII text is batched and other
glyph advances use a bounded cache; unchanged rows retain their list identity.
A pixel regression compares cached versus fresh drawing and proves that changing
one row records only that row. Existing color seams, wide glyphs, padding, cursor,
Nerd Font and typography checks pass. Pane translation reads state in the graphics
layer, and destination badges only recompose when the destination changes.

Measurements are saved in `validation/performance.json`:

| Workload | Result |
| --- | --- |
| Dense 100×50 terminal, one changed row, software Canvas median | 8.127 → 0.844 ms (about 90% less CPU draw time) |
| Same renderer workload p95 | 8.644 → 1.803 ms |
| Local full-frame / one-row JNI packet | 100,265 / 2,072 bytes |
| Input dispatch → Android terminal state, including network, 10 samples | median 17.769 ms; p95 33.985 ms |
| Optimized cold startup with / without Baseline Profile | median 583.848 / 588.291 ms; overlapping distributions |
| Offline gesture frame CPU p50 / p95 after translation deferral | 1.711 / 17.479 ms |

These are emulator results, not S22+ performance guarantees. The draw workload
is isolated software rendering; input timing ends before final pixel display.
The gesture workload is offline preview pane-edge dragging. Its long frame tail
still needs physical-device profiling; no blanket 60/120 fps or battery claim is
made. Profile installation/packaging is verified, but the emulator did not establish
a material startup gain from profiles alone. Raw Perfetto traces remain in the
ignored benchmark build output. An offline rerun initially found the live-fixture
preferences; it was rerun successfully after resetting only the test emulator.

The baseline generator records the real offline startup → terminal → appearance
journey. It produced 17,986 rules; R8 rewrites these into the packaged ART profile.
The release is non-debuggable, minified and resource-shrunk. It keeps the existing
sideload signing identity and package, preserving phone preferences. The separate
optimized variant adds profiling support. The generator script rejects phone
serials because Gradle's connected-test cleanup may uninstall the target app/data.

Header/tab tiles are now compact landscape rectangles with white rims, program
colors, reflection highlights and rounded corners. A disposable two-pane release
session verified both swipe directions, destination icons and stacked tiles.
Dark and Light visuals were inspected on owned fixtures. The session sheet uses
plain “previous” text and has no footer gesture hints. Either swipe direction
reveals Close; ⋮ and accessibility actions expose it too. Confirmation includes
session/server names and explains that all programs will end. Cancel sent no
mutation; confirmation closed only the disposable target. The inactive-session
native test preserves the viewed session; another closes the current session.
The actual installed server schema is in `validation/session-destroy-schema.json`.

Final checks: 31 JVM tests, 22 native tests (16 offline + 6 owned-server tests),
11 Android instrumentation tests, one successful profile-generation journey and
three Macrobenchmark tests pass. The affected gesture benchmark passed again
after translation deferral. Debug/release builds, lint, native Clippy and the
host JVM JNI smoke pass. Release verification checks the six JNI exports,
arm64 libraries, signature, 16 KiB ELF/ZIP alignment, non-debuggable manifest and
packaged ART profile. No phone UI automation was performed.

Final release APK: `app/build/outputs/apk/release/app-release.apk`,
15806134 bytes. SHA256:
`152fd90a9c1fc1548a1673e65f301f86c04f2b6d7c99d755eb66d6f1474c21d6`.
ADB installed this build on Galaxy S22+ RFCT21390SD; the installed base APK's
SHA256 was read back and matched. `validation/apk.json` records that artifact.
The emulator received the same release, its original preferences were restored,
and only disposable test sessions/panes were removed. Existing user sessions,
server settings and authentication remain intact. Changes are committed by scope.


## Rexlink release: scrolling, fitting, grouping and theme follow-up

The app is renamed Rexlink, with the unofficial subtitle and package unchanged.
Its new full-body dinosaur foreground was prepared with built-in imagegen,
packaged with alpha, and drawn through the real adaptive launcher in instrumentation.
Header/tab front tiles and label line boxes share their vertical center.

All 34 JVM tests, 24 native tests (17 offline + 7 owned-server tests), and 14
Android instrumentation tests pass. Clippy with warnings denied, the host-JVM
JNI smoke, debug/release builds and lint pass. Profiles were regenerated from
the current offline journey (17,998 rules). Binary RXT2 flags survive snapshots
and JNI; RXT1 compatibility remains tested.

An owned raw-mode Python fixture negotiated the same full-screen SGR mouse modes
used by modern TUIs and had zero terminal history. Drags moved its visible counter;
the first application scroll acquired control without showing the keyboard.
A separate content tap acquired control and opened the keyboard. This fixture
is not a launched Codex agent and made no model/API calls. A 160-column view
retained its rightmost END marker before takeover and resized to the phone grid
after ownership. Group order was checked with both Linux and Mac selected. Search,
plain previous labels and confirmed close actions remain available.

A configured Neovim 0.12.5 session on the Mac passed light/dark, takeover and
pause/resume assertions after the explicitly approved Rex-aware config change.
Its statusline plugin initially hid the test indicator; the fixture now reports
its actual &background independently of that plugin. A clean fixture also
verified the protocol behavior. Existing user editor sessions were not driven.
The separate dotfiles commit is `1e2c9c3`; a pre-edit copy is retained at
`/private/tmp/rexlink-nvim-init-before.lua`. Running editors need one restart.
No Rex server/authentication configuration changed.

The final non-debuggable release was installed over USB on S22+ RFCT21390SD.
ADB returned Success and the installed APK SHA256 matched:
`36bba3df87131a16a7bbb822852b84f29626a7ddc375b67e917a541e70803549`.
The artifact is `app/build/outputs/apk/release/app-release.apk` (15584982 bytes),
with signature, arm64 libraries, 16 KiB alignment and ART profiles verified.
`validation/apk.json` records it. App settings and saved connections were preserved.


## Fixed-font reflow correction

The automatic zoom-to-fit approach was rejected after delivery. It is removed:
font size stays fixed while viewing desktop-width terminals. The user's selected
policy is explicit takeover/reflow on content tap, not automatic control when a
tab opens. The cached phone columns/rows now accompany takeover, resizing the
actual PTY so its program redraws. A disposable 160-column terminal was tapped
and reported 63 columns afterward, with the keyboard visible and the requested
font preserved. The affected six Android renderer regressions pass, including
a pixel assertion that wide remote frames do not shrink the font cells. Build,
JVM checks, lint and Clippy pass. No extra broad benchmark pass was added.

The multi-pane content/footer reservation is reduced from 22 dp to 14 dp; Latest
moves with it. This reduces the space between terminal content and pane dots.
The corrected release was installed on S22+ RFCT21390SD (ADB Success), and its
installed APK hash matches `validation/apk.json`:
`611bc0c2f3e2b8772f9aba4de6fcbcd468513e587b55b7251387673a3da3a3ef`.
The prior zoom-to-fit screenshots/claims above are historical, not current policy.


## Compact header, task titles, extra keys and stale-session follow-up

All 36 JVM tests and 25 native tests pass, including owned-server title changes,
repeat close of a missing session, unowned dark-pane viewing, takeover and
reconnection. Configured Mac Neovim also passes the same-scheme takeover case
with a 63-column resize. A test indicator now uses its disposable buffer to avoid
creating a hit-enter prompt during resize; the first apparent reproduction was
that test artifact, not a confirmed failure in production focus delivery.

The live user Mac pane was inspected without sending editor input: macOS was
Dark, native Rex Beta owned it, canonical palette entries matched Android light,
and Neovim reported background=light / catppuccin-latte. The approved local
config now resolves the owner before choosing OS versus terminal appearance
(dotfiles commit 0704207). Mocked checks cover both owners, lookup failure and
stale asynchronous results. A real read-only ownership lookup against that pane
selected dark OS appearance. Running Neovim must be restarted for the new helper;
its in-place running user editor was not changed or restarted by automation.

Emulator UI checks used one disposable two-pane fixture and verified the task
title in both header and tab, disappearance of the canceled pane overlay,
F1-F12 expansion/collapse, and the strip resetting to Esc. Screenshots were
visually checked; all four arrows fit without edge clipping at emulator width.
Original emulator preferences were restored and only the fixture was removed.
Debug/release builds, JVM checks, release lint, and Clippy with warnings denied
pass. Existing baseline profiles remain packaged; no unrelated benchmark rerun.

The non-debuggable release was installed on Galaxy S22+ RFCT21390SD over USB.
ADB returned Success; its installed base APK SHA256 matched the verified build:
`354a19dbc7c1cee51df9706cac961b4a1105629ae0ecd92f56825c75a7d773ea`.
`validation/apk.json` records signature, profiles and arm64 alignment checks.
Saved phone connections and settings were preserved. Changes are committed per scope.


## Shell captions, working underline and corrected keyboard reveal

37 JVM tests pass, including shell-directory duplication and preserving Codex
and custom task titles. All 19 offline native tests and the affected live
selected/background program-status test pass; the live test transitions working
to cleared and rejects stale ownership. Clippy with warnings denied and release
build/lint pass. No unrelated full live or performance rerun was added.

Emulator checks verify the chevron starts hidden before Esc, a rightward drag
reveals it, F12 is available when expanded, and collapse hides the chevron again.
The ordinary keys fill the width to the keyboard control. An initial gap assertion
was corrected for Android's expanded minimum touch bounds (which can overlap
slightly); screenshot inspection confirms the actual 3 dp painted gap. A drag
starting farther from the system back edge verified the reveal gesture.

Two screenshots of the active working tab showed 2,327 changed pixels within
its bounds, verifying animation without extra indicator space. An idle report
stopped the working state. The shell fixture visibly shows fish once as the
header program and ~ as its separate directory. The final shell check was resumed
after the standalone launcher instrumentation stopped the test activity; it
passed after relaunch. The adaptive launcher test passed, and its rendered icon
was visually checked. Only owned disposable fixtures were driven and removed;
the emulator's original preferences were restored.

The cropped dinosaur was prepared with built-in imagegen; source, hashes and
prompt are in artwork/rexlink. Android supplies the white adaptive background.
The final release was installed on S22+ RFCT21390SD with ADB Success; the installed
base APK hash matches validation/apk.json:
`f9b4d12bc54f8f19180ce105740a7b51a62bbc58e0784c7cd97cddf6f2a59b08`.
Saved phone settings and connections were preserved. Changes are committed by scope.


## Live title frames, text selection, pane picker and official catalog delivery

All 27 native checks (20 offline + 7 owned-server tests) pass with both server
environment variables supplied. The first broad invocation lacked the second
server variable; the corrected invocation passes. The title test observes three
successive braille-prefixed terminal titles, each within 700 ms of its input,
then verifies a title update in a background pane. A static status record cannot
mask them. Unit coverage rejects old process owners, stale polling responses
and resurrection after clearing a title. Clippy with warnings denied passes.

All 41 JVM tests pass, including four selection tests for wide characters,
sparse-cell spaces, reversed multi-line ranges and moving either endpoint.
Five Android interaction/launcher tests pass, including selection without
ownership and preserving the frozen frame while live output changes.
Debug/release builds and release lint pass. The source-only linear extraction
cleanup was included in the final JVM/build pass.

Emulator UI checks observed each live title in both header and tab, showed the
new pane picker with program names/directories and catalog tiles, and confirmed
zero changed pixels in the header icon between animation frames. Long press
visibly highlighted TITLE_READY and displayed native Copy / Select all without
taking ownership. The floating toolbar was absent from the UiAutomator tree;
the coordinate-based clipboard roundtrip did not complete and is not claimed
as a passing check. Selection and frozen-frame behavior are covered at model/interaction level;
the clipboard action was not exercised by instrumentation. Physical copy
behavior remains for user testing. Original emulator preferences were restored and the
owned disposable fixture was removed. No user editor was driven.

The catalog was accessed through native Edge window automation after the
browser-specific host failed. The user then supplied the complete SVG download,
which was used directly. Ten selected source SVGs, source hashes, attribution
and reproducible WebKit rendering script are committed. Codex/Claude renderings
were visually inspected. No authentication material is packaged.

At the user's request to ship promptly, the final launcher uses their supplied
transparent paper dinosaur PNG exactly; no image generation or editing was
applied to this final file. The release was installed over USB on Galaxy S22+
RFCT21390SD and its installed base APK SHA256 was compared with the build.
validation/apk.json records the delivered artifact. Saved phone settings and
connections were preserved. Feature and artwork changes are committed by scope.


The user's final replacement is the already-transparent paper dinosaur supplied
as 5BD6830D-F403-4478-9E09-6AC36F3C38F5.png. Its bytes and alpha were preserved.
The installed S22+ release hash was read back and matched:
`561e5fdc370b01568cfef5f33d89c9259cc1258380cf1b2dea56624f206c573c`.
This supersedes the briefly installed opaque paper-icon build. The final APK
signature, non-debuggable setting, native alignment and ART profiles are verified.


## Close controls and shareable release — 2026-11-01

Targeted live native close test passed: stale-session and changed-tab guards, background ownership preservation, repeated missing-pane close, active-neighbor selection and final empty session. Clippy with warnings denied passed. Debug/release builds, release lint and 42 JVM tests passed, including fresh-install/legacy-server migration checks.

Emulator checks exercised tab long-press and pane-picker close, cancellation and confirmation. The last-pane helper initially indexed an omitted `windows` field; the empty-session check was corrected to accept the server's omitted field. Fresh-install setup and offline preview were checked. Physical interaction remains for user testing.

The optimized, non-debuggable release is copied to `dist/Rexlink-0.1.0-arm64-release.apk` with a SHA256 sidecar. Signature, native alignment and ART profiles passed verification. Installed S22+ base APK readback matches SHA256 `3782dee4540e3afb23baf54a7f1ddf23c22f7865dde2cf0c08c332da99d7c5a1`. Existing phone data was preserved. Fresh installs no longer contain a preset personal server.


## Shift keyboard release — 2026-11-02

44 JVM tests pass. Four new emulator Compose gesture tests pass: one-shot Shift and Shift+Tab, holding Shift through navigation scrolling and multiple taps, cancellation, and disabled controls. The scrolling test asserts that no input is emitted by the scroll gesture. AndroidX test runner/JUnit and Espresso were updated to stable API-37-compatible versions; the initial older Espresso failed before injection because InputManager.getInstance was removed. See [AndroidX Test release notes](https://developer.android.com/jetpack/androidx/releases/test#espresso-3.7.0).

Debug/release builds and release lint pass. The optimized release passes APK signature, non-debuggable, native alignment and ART profile checks. Shareable artifact: `dist/Rexlink-0.1.0-arm64-release.apk`, SHA256 `55d53e1f372eaa829847cb8dbf27ef14d0c710c8c32e6dde5db3ad9d6fbd57eb`. After USB reconnection, this keyboard release was installed on S22+ RFCT21390SD; its installed base APK hash matches the shareable artifact. Existing application data was preserved.


## Corrected single-row keyboard — 2026-11-02

This supersedes the two-row accessory. Five separate vector-symbol modifier buttons keep their spacing, followed by an independently scrolling, clipped group of four arrows and a keyboard toggle. Navigation keys return to the optional expanded panel. The normal accessory has one row.

All five emulator Compose tests pass: one-shot Shift/Shift+Tab, held Shift with scrolling and repeated arrow taps, cancellation, disabled controls, and a 320dp-wide layout asserting all controls share a vertical center and do not overlap. Arrow scrolling is checked to leave modifier positions unchanged and emit no input. All 44 JVM tests, debug/release builds and release lint pass. APK release checks pass.

Updated shareable APK: `dist/Rexlink-0.1.0-arm64-release.apk`, SHA256 `5b0b7c8e9e599dc2bd5dab5df708aba1fd05f2fdd83cef01851b922e8a11c9fa`. After USB reconnection, the corrected release was installed on S22+ RFCT21390SD; its installed base APK hash matches the shareable artifact. Existing application data was preserved.


## Readable keyboard icons while viewing — 2026-11-02

Connected, ready terminals now retain full keyboard icon contrast when not owned. Interactivity still requires ownership. Disconnected/busy controls retain their inactive styling. Program catalog tiles were already independent of ownership and are unchanged.

All six emulator keyboard tests pass. The added regression compares every toolbar pixel before/after switching to read-only mode, then asserts disabled semantics and no emitted input when those keys are tapped. The 44 JVM tests, debug/release builds, release lint and APK checks pass. Updated shareable release SHA256: `62c65b9d404789bc45b35e0367eefb6665cc51c12ae7ab5694369bcd7066bd02`. Installed on S22+ RFCT21390SD and verified by installed base APK hash; existing settings preserved.


## Pane indicator continuity and pointer ownership toggle — 2026-11-02

The swipe badge retains the target pane through settling and the timed display; its dismissal timer starts after motion. Animation handoff state updates together, and badge identity stays bound to the target instead of briefly using an old composition index. Derived state preserves the existing efficient animation rendering. Cancelled gestures clear the badge without resurrecting it.

Two manual-clock emulator tests pass: sampling 22 frames across the handoff verifies continuous visibility and target identity, and a cancelled gesture stays dismissed after settling and the old timeout. A third test verifies take/release action semantics, selected state and the long-press label on the pointer-only ownership toggle. The intermediate cached-index variant failed a handoff assertion and was corrected before delivery. No eye icon is used for ownership.

All 44 JVM tests, debug/release builds and release lint pass; APK checks pass. Updated shareable artifact SHA256: `7e29850bb61ee16cc9826022faa646be72733fb89d90d1d4cc012ee27ed5c19f`. Installed on S22+ RFCT21390SD with matching installed base APK hash; settings preserved.


## Tappable visible web URLs — 2026-11-02

49 JVM tests pass, including URLs with paths/queries/fragments, localhost/private IP/IPv6, Unicode paths and wide-cell prefixes, punctuation trimming, missing hosts, disallowed schemes, hidden text and sparse rows. Seventeen emulator checks pass: five URL interactions plus existing terminal selection/scrolling, background/cursor/cache and rendering performance tests. Browser intents were captured by a ContextWrapper; tests did not browse external pages.

URL taps send ACTION_VIEW with BROWSABLE and the exact detected URL, with no activation/input. Tests cover swiping, cancellation, long-press copy, output changing between down/up, padding, ordinary taps, and rejecting possible wrapped prefixes while adjacent-row state changes. Debug/release builds, release lint and static APK checks pass.

Shareable release SHA256: `63a0d0bc907ef78d41c7902e109ed8d9c065c033cf4d5b1b31ffb6ec86bba61c`. Installed on S22+ RFCT21390SD and verified by installed base APK hash; settings preserved.


## OSC 8, stable navigation keys and compact control — 2026-11-02

52 JVM tests pass, including RXT1/RXT2 compatibility, RXT3 sparse ranges, destination-only changes/removal, untouched-row reuse, invalid ranges, explicit-link precedence and non-web/hidden-text rejection. Native workspace tests pass (22 non-live tests); Clippy with warnings denied passes. New native tests verify Ghostty OSC 8 parsing, wrapped cells, snapshot preservation and sparse metadata-only deltas.

The targeted owned-server OSC 8 test passes on Linux and Mac: create a disposable session, restore the initial link from a snapshot, replace the target through live output, pause/resume, remove the link without replacing its label, then destroy the fixture. An initial fixture test omitted explicit pane selection; the harness was corrected before these successful runs. No user sessions or server settings were modified.

All 28 emulator checks pass. These cover OSC 8 target precedence at a row boundary; existing URL, copy, scroll, render-cache and pane tests; and pixel-identical keyboard appearance through navigation's disabled state while input remains blocked. The smaller pointer keeps its accessible take/release action and tooltip. Debug/release builds, release lint, signature, non-debuggable setting, native alignment and ART-profile checks pass.

Shareable release SHA256: `fd7fb1a89fef60f55caa75daf3eca5421b41b631d42dee9c748919302ec8f62e`. Installed on S22+ RFCT21390SD and verified by installed base APK hash; settings preserved.


## Wrapped links, link actions and hold-Ctrl popup — 2026-11-02

56 JVM tests and 23 non-live native tests pass; Clippy with warnings denied passes. Added native coverage confirms soft-wrap and continuation flags distinguish hard newlines and survive snapshots. Logical-link tests verify multi-row URLs/hit ranges, incomplete-fragment rejection and changed-tail invalidation of unchanged prefix rows. Shortcut tests verify the eight emitted control bytes. Existing live snapshot/stream/resume checks pass on both owned Linux and Mac servers; disposable fixtures were removed.

All 37 emulator checks pass, including real hold/slide/release selection across the popup window, outside cancellation, short Ctrl toggle and read-only guards. Link-menu checks copy the actual destination through Android's clipboard (restoring prior clipboard contents), capture Open and Sharesheet intents, preserve frozen destinations through output changes and keep ordinary text selection. No messages were sent and no external browser page was opened by tests. Wrapped continuation taps open the complete URI. Existing keyboard/pane pixel stability, selection, rendering-cache and performance checks pass.

Debug/release builds, release lint, signature, non-debuggable, native alignment and ART-profile checks pass. Updated shareable release SHA256: `0f3d4bf23105661f360df058da2657d83b12d63f8c7b91cdc72457fbe1d401e5`. Installed on S22+ RFCT21390SD and verified by installed base APK hash; existing settings preserved.


## Keyboard repeat and multi-server activity — 2026-11-02

All 44 emulator checks pass, including held-arrow repeat/release, scroll cancellation, target changes, disabled input, activity grouping/filtering, stale records and the combined pane menu with Activity on the right. Existing Ctrl popup, Shift, links, selection and rendering checks still pass. 59 JVM tests and 24 non-live native tests pass; Clippy with warnings denied passes. Debug/release builds and release lint pass.

The disposable-session live activity test passes on both owned Linux and Mac servers. It verifies root/child reports from multiple sessions, exact-pane navigation, no ownership or dimension changes, and stale-pane handling. Fixture sessions were removed. No user sessions or server settings were changed.

Signature, non-debuggable release, ART profiles and 16KiB native alignment are verified. Installed on S22+ RFCT21390SD; installed base APK matches shareable release `dist/Rexlink-0.1.0-arm64-release.apk`, SHA256 `d3d35b6f3ff2cb34569cf3e93c928c5db347d07029e0b03b67a4919ce76062a8`. Existing application data was preserved. Performance review follows this delivery.


## Activity title and eye-button correction — 2026-11-02

The rounded background is on the rightmost Activity eye, with a plain pane-options button beside it. All three Activity emulator checks pass. Native title coverage verifies live OSC precedence, exact owner/session binding, child-task independence and completed reports from exited processes. The live activity fixture changes its terminal title while its status title remains static and passes on both owned servers. All 25 non-live native tests, 59 JVM tests, Clippy and release lint pass; debug/release APKs build.

Activity titles in the attached session consume existing title events; other sessions refresh on the four-second activity scan. Signature, non-debuggable release, ART-profile and 16KiB checks pass. Installed on S22+ RFCT21390SD and verified by installed base APK hash `9cd369e300abb5e3dac4079d67272a843cd46fd6cc8eacd173634f7129d2085d`. The shareable APK and checksum were updated. This correction was shipped before resuming performance optimization.


## S22+ release performance review — 2026-11-02

Requested order was preserved: the Activity/title and eye-button fixes were installed before performance optimization resumed. Measurements and reproducible benchmark commands are in `validation/performance-2026-11-02.json`; prior emulator measurements remain in `validation/performance.json`.

On the S22+ (SM-S906E, Android 16/API 36), four force-stopped launches reached initial activity display in 435, 438, 363 and 446 ms (median 436.5 ms). This excludes completion of remote terminal restoration. A no-touch but animated live terminal sample recorded 4/1,203 modern-jank frames (0.33%), p95 13 ms/p99 15 ms. The Activity open/scroll sample recorded 47/1,425 (3.30%), p95 17 ms/p99 19 ms; this global counter includes the underlying window. These are separate workload observations, not before/after speedup figures.

The review found hidden cached activity being serialized into ordinary title-update metadata. The isolated fix keeps the cache natively for reopening while publishing null activity when the sheet is closed. In a 200-sample optimized Mac benchmark with 256 records, the previous-path-equivalent payload was 54,950 bytes, median 470 µs/p95 1,197 µs; the optimized closed path is 389 bytes, median 10 µs/p95 19 µs. Open-sheet behavior is unchanged. These timings are host measurements, not S22+ timings or an app-wide speedup. A native regression checks hidden payload exclusion, retained cache and reopening.

Next profiling target: Activity transitions/list drawing alongside live title and underline animation. Visible-terminal CPU samples were high (roughly 92–96% of one core), but later thread sampling occurred after drawing stopped, so attribution is unresolved. A controlled foreground trace is needed before selecting rendering changes or claiming battery improvements. Physical key-to-pixel latency was not measured; repeat delivery/cancellation was verified in the emulator tests.

All 26 non-live native tests, 59 JVM tests, Clippy and release lint pass. The live Activity fixture passes after optimization, including title updates and unchanged control/size. Signature, non-debuggable release, ART profiles and 16KiB alignment pass. Installed optimized release on S22+ RFCT21390SD; installed base APK matches shareable `dist/Rexlink-0.1.0-arm64-release.apk`, SHA256 `1f9e92d5f13a637d77aed027858f54e6b6ee32c52247b2908ae0b3a035f9eaf6`. Existing settings were preserved. Raw diagnostic files stay in `/private/tmp/rexlink-performance-20261102` and are not committed because UI captures can contain user terminal titles.


## Automatic active-pane ownership and Ctrl+J — 2026-11-02

All 64 JVM tests, 27 non-live native tests, Clippy with warnings denied, debug/release builds and release lint pass. New policy tests cover readiness/dimensions, one claim per activation, external ownership changes, explicit viewing through resume, hidden/paused clients and stale owned frames. Native target-guard tests cover wrong session/block/navigation IDs, hidden/paused take and resize commands. The native lifecycle fixture passes on both owned servers; the two-server native isolation fixture passes after hidden panes began explicitly releasing ownership.

All 46 emulator checks pass. The two-server Android fixture verifies automatic take/resize, old-server release, explicit viewing mode, Activity-entry takeover, returning across servers and foreground recovery. A takeover-metadata race in the test was corrected by waiting for the final notice/process data before asserting terminal-only output leaves chrome unchanged. The stale-owned-frame activation race was fixed and covered. No user terminal input was used; only disposable fixture sessions were driven and removed. The separate Ctrl+J test holds Ctrl, slides onto the ninth cell and releases; the emitted line-feed byte is checked by JVM coverage. Existing Ctrl W selection, popup cancellation, repeat/Shift, links, selection and pane tests still pass.

Signature, non-debuggable setting, ART profiles and 16KiB native alignment pass. Installed on S22+ RFCT21390SD and verified by installed base APK hash `6faa75d532b586ecaf6cbeece9e6dcd41b4f2fbfd5b129fe6b0f8db7ac20e332`. Shareable APK `dist/Rexlink-0.1.0-arm64-release.apk` and checksum were updated; existing settings preserved.


## Preset themes, 1 sp sizing and reconnect ownership — 2026-11-02

68 JVM checks pass, including all 41 catalog entries plus two original defaults, wrong/missing-ID fallback, 256-color mapping and preserved RGB, default UI identity and invalid-color rejection. All 50 emulator checks pass, including theme selection without changing mode, independently persisted light/dark choices and font size, 1 sp controls/slider and light/dark sheet rendering. Both screenshot variants were visually inspected. Existing keyboard, popup, links, selection, terminal caches, panes and two-server ownership checks pass. The saved-preferences fixture uses a unique temporary store and removes it.

30 non-live native tests and Clippy with warnings denied pass. New ownership tests verify delayed resize APCs cannot override canonical ownership, unowned layout resizes are quiet while typing is rejected, and ownership confirmation clears only the stale ownership error. The extended live lifecycle fixture passes on both owned Linux and Mac servers: four reconnect/take/resize/type cycles per server, with another client's canonical takeover checked each time. Disposable sessions were removed; no user sessions or server configuration were changed.

Debug/release builds, release lint, signature, non-debuggable setting, ART profiles and 16KiB checks pass. The preset asset is 128,064 bytes uncompressed. Default-theme startup skips catalog parsing; no new phone startup or battery-performance claim is made. The headless emulator had disconnected before the first UI run; it was restarted and the complete 50-check run passed.

Installed on S22+ RFCT21390SD after USB reconnection and verified against installed base APK SHA256 `3794798a37c1d2d82fc9fd540bff3fbfb2a2cee0a8538e2d359fe403b3617aca`. Updated shareable `dist/Rexlink-0.1.0-arm64-release.apk` and checksum; phone settings preserved.


## Activity, network recovery and contextual ownership — 2026-11-02

74 JVM tests and 33 non-live native tests pass. Clippy with warnings denied, release lint and debug/release builds pass. All 56 emulator checks pass, including the new attention/seen actions, contextual takeover cancellation, ready feedback and retry states. A one-frame stale takeover notice found during validation was corrected. The live disposable Activity fixture and four-cycle lifecycle reconnect fixture pass against both owned Linux and Mac servers. Activity checks include a blocked report from an unselected session while the sheet is closed, local mark-seen and a changed report alerting again. Fixtures were removed; user sessions/server settings were unchanged.

In the optimized Mac host benchmark, repeated title updates with 256 cached reports produce 431-byte JNI packets both open and closed; median publication/encoding is 5 µs, p95 5 µs open and 6 µs closed. This is a metadata microbenchmark, not a whole-app or phone speedup. The list also memoizes filtering/sorting and pauses the underlying working-tab animation while Activity is visible. Physical-device network transitions and new frame-time measurements have not been performed for this follow-up.

An initial feature release was installed on S22+ before follow-up checks, per requested delivery order. The final release adds the transient-feedback overlay and immediate notice dismissal. Its installation attempt found the phone disconnected; USB reconnection is pending. The shareable artifact is `dist/Rexlink-0.1.0-arm64-release.apk`, SHA256 `9d918582e01106bbd0817116c7f694905e17d1c95a3d1a58776c7c0e69a0ee19`. Signature, non-debuggable packaging, ART profiles, ARM64 JNI exports and 16KiB alignment pass. `ACCESS_NETWORK_STATE` is the only new platform permission.

Activity visual follow-up: the six-dp eye attention dot is inset seven dp so it remains entirely inside the circular button. Per-row acknowledgment uses a compact “Mark seen” text action instead of the tick glyph, and “Seen” status uses the muted foreground. The updated release builds and passes static APK verification. Installation was attempted before follow-up checks; S22+ remains disconnected.

Three-line header: title now uses the available width, the working directory has its own smaller muted line when nonempty, and the session strip shows session · server. The icon remains vertically centered against the stack. Normal text measures approximately 53 dp tall with a directory and retains the 50-dp minimum without one. Release build and APK verification pass; the five existing SessionCatalog tests pass with the new label order. Installation was attempted immediately after building; S22+ was disconnected.


## Monorepo, Apache 2.0 and Activity badge diagnosis — 2026-11-02

Both source histories were merged without rewriting IDs: 6 probe commits and
138 Android commits are reachable, including separately signed commits for the
previously pending Activity, reconnect and three-line header changes. Original
checkouts remain intact; their ignored caches were cloned only as local build
accelerators. The old Android daemon-JVM settings were not imported. There are
no nested Git repositories or source/build dependencies on the old checkouts.
Signed migration commits were cryptographically verified with the configured
public key. The original signatures and commit objects remain unchanged.

The source-build command in the root README succeeds from the monorepo root.
Release build, 74 JVM tests, release lint, all-feature Rust workspace tests
(45 passed; live tests remain opt-in), Clippy with warnings denied, and the actual
host JVM/JNI smoke check pass. The smoke assertion was corrected from RXT2 to the
already existing RXT3 format; the protocol format was not changed by migration.
The standalone probe build also succeeds. APK signature, stable Android package,
JNI exports, ART profile and 16KiB alignment checks pass. The built APK contains
byte-identical copies of the root Apache 2.0 LICENSE and NOTICE. The artifact
hash and history audit are recorded in validation/apk.json and validation/monorepo.json.

The six targeted emulator Activity checks pass against an owned disposable
Linux-server fixture, including actual blue-pixel rendering for a blocked report,
local acknowledgment without opening a terminal, and the hidden-sheet path.
The committed runner reproduces the check without an old repo dependency and
removes its own session. The user's “Working while Codex says Action required”
case is a missing structured blocked transition, not reproduced by the normal
blocked-report fixture. No screen-text heuristic, Codex configuration or server
authentication/configuration change was added.


## Session rename and shorter overflow menu — 2026-11-02

Added **session row ⋮ → Rename session** with a prefilled field, server label,
Unicode-aware 128-character validation, Save and Cancel. The request stays bound
to the original server/session. The live native fixture passes on both owned
Linux and Mac servers: active/background rename, whitespace/Unicode,
external-client label events, invalid controls and missing-session errors.
The active block, ownership and 40×20 grid remain unchanged. Only its own two
fixture sessions were created and removed on each server.

All 76 JVM tests and three targeted emulator rename tests pass. The UI checks
cover nested clicks without selecting/closing a session, prefill, validation,
trimmed Save and Cancel without sending. Native unit tests, all-feature Clippy
with warnings denied, debug/release builds and release lint pass. Redundant
Switch pane / Close pane / Close tab entries were removed from the top ⋮; the
pane/tab controls remain. Feature and menu changes were committed separately.

ADB reported Success installing the release on S22+ RFCT21390SD immediately after
building and before follow-up checks, using -r to retain app data. The phone
subsequently disconnected before installed-APK hash readback, so that optional
comparison was not completed. The shareable APK has SHA256 `e0bf1a516d77dcf19e713c0278ef34a5e133a7e48e7d08213808435433f856f6`.
Signature, non-debuggable packaging, JNI exports, ART profiles and 16KiB alignment
pass. The APK/checksum are in dist/ and validation/apk.json records this release.


## Fanned program icons and circular swiping — 2026-11-02

Multi-pane stacks expose their back tiles horizontally and use stable -6° front,
+3° near-back and +6° far-back rotations; single-pane icons remain upright.
A rendered header screenshot was inspected, then the existing header gesture
check was rerun after rotation. The same component styles both header and tabs.

Session paging uses two sentinel pages with logical-index rebasing after settling.
Only a change of logical session emits navigation. Catalog membership changes
recreate the pager at the current session. Pane previews wrap within their tab;
first/last neighbors are prefetched. Two-pane rings render one neighbor on the
gesture side, maintaining unique AndroidView keys. Animation direction follows
the swipe rather than the numerical destination index.

All 77 JVM tests and nine targeted emulator gesture tests pass. The checks
cover bidirectional first/last wrapping, two-item rings, single/empty catalogs,
catalog changes without unsolicited navigation, target-badge continuity and
cancellation at the boundary. The one-test header rerun after the rotation also
passes. Debug/release builds and release lint pass. No Rex protocol, ownership
policy or server settings were changed.

Both the initial sideways-stack release and the final rotated-stack release
were installed on S22+ RFCT21390SD immediately after building. ADB reported
Success. Follow-up static verification passes signature, non-debuggable APK,
JNI exports, ART profiles and 16KiB alignment. The final shareable release is
`dist/Rexlink-0.1.0-arm64-release.apk`, SHA256 `8b5e6689f15dc9a09436c79e41744774564cded33af7aaed33700f8593ce3158`. Icon styling, circular navigation,
and the angle correction were recorded in separate signed commits on main.


Tab spacing follow-up: the explicit icon/title gap is reduced from 5 dp to 2 dp. The release builds and passes static APK verification. It was installed on S22+ RFCT21390SD with ADB Success before follow-up checks. Release SHA256: `9655d431561287dbb5398dcf938817988685ec2a86626a9007fedc3fd0914976`.
