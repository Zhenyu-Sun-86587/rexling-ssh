# Rexlink Android

Native Kotlin/Compose UI, Canvas terminal rendering and Android input/lifecycle
integration for the unofficial Rexlink client. Open this directory in Android
Studio. The Rust workspace is next door in `../rexlink-core/`; Gradle builds it
automatically. No separate old rex-probe or rex-android checkout is needed.

See [root README](../README.md) and [BUILDING.md](../BUILDING.md) for complete
toolchain requirements, reproducible commands and licensing.

From this directory:

```sh
./scripts/gradle.sh :app:assembleRelease
./scripts/gradle.sh :app:testDebugUnitTest :app:lintRelease
python3 scripts/test_jni.py
python3 scripts/verify_apk.py --apk app/build/outputs/apk/release/app-release.apk --require-release
```

The release APK is `app/build/outputs/apk/release/app-release.apk` (arm64, API 28+).
The package remains `dev.shulhi.rex`, preserving existing installs and settings.
Local releases retain the development signing identity; configure production
signing separately before publishing. SDK paths and caches remain ignored.

## Usage

- Keep Tailscale connected and use an explicit authenticated Rex server URL.
  **⋮ → Servers** saves hosts and keeps several connected at once.
- The compact header shows the task/program title, a working-directory line
  when available, then session · server. Tap the session to search, swipe to
  cycle across hosts, or long-press to return to the last session. In the session
  list, open a row’s **⋮ → Rename session** to change its name on the server.
- Swipe the content card to switch panes. The bottom tabs remember their pane;
  pane options switch, create or close panes/tabs. Destructive close actions
  require confirmation and end the programs in that resource.
- A newly selected ready pane is automatically claimed once and resized to the
  phone. Switching away/backgrounding releases it. Manual Take/Release live in
  **⋮**. Another owner's dimensions appear in a contextual takeover notice.
- The Activity eye opens reports grouped by server. Unseen attention reports
  produce a dot; **Mark seen** acknowledges a report locally. Activity does not
  take ownership merely to scan reports.
- The terminal streams snapshot plus incremental VT, with real Ghostty
  scrollback, copy selection, OSC 8/printed links, Nerd Fonts and mouse-aware
  full-screen scrolling. Shared PTY dimensions affect other attached clients.
- The one-row keyboard dock offers modifiers, a scrollable arrow group,
  hold/repeat, Ctrl shortcuts and an expandable panel with F1–F12.
- **⋮ → Appearance** saves independent light/dark presets, including the
  Rexlink defaults. Font size changes in 1-sp steps. Theme requests are sent
  while owning the pane; programs may override their own colorscheme.
- Reconnect notices expose input-paused state and Retry now. Network changes
  trigger retry; uncertain input is never automatically resent.

Protocol findings and historical check results remain in [PROTOCOL.md](PROTOCOL.md),
[TESTING.md](TESTING.md), and [VALIDATION.md](VALIDATION.md). Older entries use
the paths from before consolidation; current commands are in the root build guide.

Project code: [Apache 2.0](../LICENSE). Bundled third-party notices are retained in
`app/src/main/assets/licenses/` and listed in [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md).
