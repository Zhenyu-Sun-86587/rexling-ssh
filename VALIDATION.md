# Build validation

Verified on the development Mac and **API 37 arm64 Android emulator** on
**2026-11-01**. The app was installed and launched on `Medium_Phone_API_37.0`
(`emulator-5554`). No physical phone was tested. No Rex server settings were changed.

| Check | Result |
| --- | --- |
| Android arm64 Ghostty + Rust JNI compilation | Passed |
| Emulator installation and launch | Passed; cold launch completed |
| Emulator native terminal preview | Rendered captured snapshot, colors, Unicode and cursor |
| Emulator live connection | Authenticated server connection and live terminal visible; control mode and keyboard observed |
| Emulator crash log | No AndroidRuntime errors or libc fatal entries during smoke run |
| `assembleDebug` | Passed; debug APK signed |
| Kotlin model/input/navigation JVM tests | 5 passed |
| Native offline unit tests | 9 passed |
| Explicit real-server actor host test | Passed on the existing Tailscale host |
| Actual JNI bridge on host JVM | Passed |
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
the terminal reported 49 columns by 22 rows at the default 14 sp font. The prior
layout's observed IME-visible terminal was 49 by 17. Navigation history unit tests
also cover removed panes/windows and empty windows. `assembleDebug`, all five
Kotlin tests and `lintDebug` passed; lint has no errors. The crash log contained
no AndroidRuntime error or libc fatal entries during these checks.
