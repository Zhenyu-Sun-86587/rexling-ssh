# <img src="rexlink-android/app/src/main/res/drawable-nodpi/rexlink_dinosaur.png" alt="Rexlink app icon" width="40" height="40" align="absmiddle"> Rexlink

Unofficial native Android client for Superlogical Rex.
Connect directly to your authenticated Rex server over your private Tailscale network.
Kotlin/Compose supplies the UI; Rust handles the Rex protocol and JNI; libghostty-vt
maintains terminal state. No custom bridge, proxy or Rex subprocess is required.

| Light theme | Dark theme |
| :---: | :---: |
| <img src="docs/images/rexlink-android.png" alt="Rexlink light theme showing a Codex session, pane indicator, tabs, and terminal keyboard controls" width="340"> | <img src="docs/images/rexlink-android-dark.png" alt="Rexlink dark theme showing a Codex session, pane indicator, tabs, and terminal keyboard controls" width="340"> |

```text
rexlink/
├── rexlink-android/       Android Studio / Gradle app, UI, rendering and input
└── rexlink-core/          Rust workspace, shared protocol and terminal implementation
    ├── src/              rexlink-core crate
    ├── jni/              rexlink-jni crate; exports the existing librex_android ABI
    ├── probe/            original rex-probe / rex-view investigation CLI and evidence
    ├── native/           Ghostty shim, pinned source manifest and MIT license
    └── scripts/          native build helper
```

The probe keeps its original investigation implementation and evidence for protocol
experiments. The app uses the maintained `rexlink-core` crate, through `rexlink-jni`.
The crate alias inside JNI remains `rex_core`; Android's package `dev.shulhi.rex`
and native-library name are unchanged, preserving installed app data and updates.

## Install on Android

Rexlink currently supports **64-bit ARM phones running Android 9 or newer**,
including the Galaxy S22+. You need access to an existing Rex server. Install
and connect Tailscale on your phone when the server is on a private tailnet.
Rexlink uses that existing VPN connection; it does not embed another VPN.

Obtain the release APK from the project maintainer, or build it below. Open the
APK on your phone and allow installation from that file/browser app when Android
asks. Alternatively, enable USB debugging, connect and authorize your device,
then install with Android SDK platform-tools:

```sh
adb devices -l
adb -s <your-device-serial> install -r "Rexlink-<version>-build<code>-<commit>-arm64-release.apk"
```

Open **Rexlink**, enter your Rex server's authenticated private HTTPS URL, and
connect. Add other hosts under **⋮ → Servers**. Keep Tailscale connected and
ensure Rexlink is included in its VPN routing. The app does not configure a Rex
server or change its authentication. APKs are not yet published to an app store.

## Build and install from source

```sh
git clone <repository-url> rexlink
cd rexlink
```

Replace `<repository-url>` with the Git URL where you host this repository.


Install Android Studio, Android SDK platform 37, Build Tools 36.0.0, NDK
30.0.16248370, Rust 1.93 or newer, Python 3.9+, and curl. Android Studio's bundled
JDK 25 was used for validation. See [BUILDING.md](BUILDING.md) for setup, host
tests, debug builds, device installation and platform details.

From this repository root:

```sh
./rexlink-android/scripts/gradle.sh :app:assembleRelease
```

For a shareable APK whose filename includes the date, build number and commit,
run `./rexlink-android/scripts/gradle.sh :app:shareRelease`. The APK and SHA-256
checksum are written to `rexlink-android/dist/`.

To install the APK you just built:

```sh
adb devices -l
adb -s <your-device-serial> install -r \
  rexlink-android/app/build/outputs/apk/release/app-release.apk
```

The build downloads checksum-verified Ghostty and Zig, compiles the Rust JNI
library for Android arm64, and builds the APK. The first build needs network
access; later builds reuse ignored local caches. No old sibling checkout is needed.

```text
rexlink-android/app/build/outputs/apk/release/app-release.apk
```

Open **rexlink-android/** in Android Studio, rather than the repository root.
Release builds are optimized and non-debuggable. This prototype currently uses
the development signing identity; see the build guide before publishing a release.

## Run

Keep Tailscale connected on the phone and enter your Rex server's private HTTPS
address. Add hosts under **⋮ → Servers**. The header cycles through sessions
across connected hosts; the terminal card switches panes. Both loop at the ends.
A single fixed pane indicator stays at the bottom of the terminal card. Its
active capsule transfers width as you swipe; the pane's icon and program name
appear directly above it without moving the terminal content.
Rexlink saves the last session, tab and pane for each server, including the last
pane used in each tab, and restores them when reopened. Closed terminals fall
back to an existing pane.
Check **⋮ → Preferences → About Rexlink** for the date-based app version, build number and
commit; **Copy build info** copies these details for bug reports.
The bottom dock switches tabs and opens Activity. Its tab/pane button opens a
switcher directly: tap **Tabs** or **Panes**, or swipe between them. Select a row
to switch, use **New tab** or **New pane** in its section, or close an item with
its × button and confirmation. Panes lists the panes in the current tab.
Tabs fit their icon and title up to a width cap, giving the active title
priority. Short titles leave room for others; inactive tabs become compact
icons when space is tight. A single tab can use all the space before the
tab/pane menu button, showing its full title when it fits.
The strip scrolls when the tabs cannot fit; the switcher
keeps their longer titles available.
When space is limited, titles nearest the active tab take priority, with more
distant tabs collapsing to icons first.
On reconnect, tabs reuse their last known titles and icons while fresh metadata
loads. The tab strip waits for the terminal and tab metadata before appearing,
keeping its space reserved while loading. New panes use a neutral loading tile
instead of temporary window IDs or an assumed shell icon. **⋮ → Toggle Dark theme / Toggle Light theme**
quickly switches between your saved light and dark presets; **Preferences →
Appearance** also offers the system setting.
Hold a tab and drag it left or right to
rearrange it; hold near an edge to scroll farther. The order syncs with other Rex
clients. Hold and release without dragging for **Move left**, **Move right** and
**Close tab**. Rename a session from its row’s
**⋮ → Rename session** in the session list; changes sync through Rex.
Long-press Ctrl for common control shortcuts, including **Ctrl+J** and **Ctrl+]**.
The arrow joystick sits beside the keyboard controls. Hold it and slide toward
an arrow to send one step; holding for 500 ms starts a slower repeat every
160 ms. Its overlay stays centered on the button, using a compact ring when
the keyboard is hidden. Double-tap the joystick to open the arrow
popup for precise taps or held keys; a single tap on the trigger closes it.
PgUp and PgDn occupy the upper corners beside Up, with a slower held repeat.
Turn off **Preferences → Keyboard → Use joystick for arrows** to use a single
tap for the popup instead, with joystick gestures disabled.
With joystick enabled, a drag switches back to steering. Returning to the center pauses
repeat, and releasing stops immediately. The gesture stays captured so a right
slide does not trigger back or pane navigation. Enter has a dedicated key, and
swiping right on the key row reveals the chevron to the left of Esc for
F1–F12 and navigation keys. Closing the extra keys hides the chevron again.
Under **Preferences → Keyboard → Ctrl shortcuts**, choose **Hold** (the default)
or **Double tap** to open the list. When closed, single taps toggle Ctrl
immediately. Tapping Ctrl while its popup is open closes it and clears Ctrl.
Neovim panes have a draggable AssistiveTouch-style button for saved key shortcuts. Its
position is remembered and stays inside the terminal when its size changes.
Choose **Circular** (the default) or **Grid** in the Neovim shortcut preferences.
The circular layout places compact shortcut pills around the AssistiveTouch
button itself, without a large menu background. At an edge the button moves
inward while open, then returns to its saved position. Its center goes back
from a group; at the top level it closes the menu. Drag the center with the
radial menu open to move the whole cluster and save its position on release.
Both layouts show six shortcuts
at a time; swipe up/down for more pages, looping from last to first and back.
There is no fixed limit on the number of saved shortcuts.
It stays open after selecting shortcuts; tap outside to dismiss it.
Optional group headers such as `[Git]` put following shortcuts inside a subtly
shaded group pill. Tap it to show its shortcuts; Back returns
to the main menu. Use `[]` to return to ungrouped entries in the editor. Groups
have one level, and both the main menu and groups support vertical paging.
The defaults include `:w` and `:q`, which run immediately. Edit **Preferences →
Keyboard → Neovim shortcuts** with one
shortcut per line, optionally named as `LazyGit = <Space>lg`. Shortcuts start in
Normal mode; key notation includes `<Esc>`, `<CR>`, `<Tab>`, arrow keys and
`<C-x>`. Use your actual leader key, such as `<Space>`. Bare colon commands get
Enter automatically; other key sequences are sent as configured. The preference
toggle hides the button without deleting shortcuts; an empty list also hides it.
Swipe up/down on the top-bar program icon to switch to the next/previous
session, looping across connected servers like the horizontal session strip.
The icon follows the vertical gesture and springs back without moving the header text.
Tap the input cursor row in Codex or Claude to open the keyboard, including when
the program uses terminal mouse reporting. Other application clicks keep their
normal behavior.
The selected pane streams a Ghostty
snapshot followed by incremental VT output. Opening a pane claims it once when
ready; viewing another client's pane shows a contextual takeover notice. Manual
Take/Release remain in the overflow menu. Input is paused during reconnection and
uncertain input is never replayed.

### Image attachments through Portside

Image uploads use [Portside](https://github.com/shulhi/portside), a separate
project for Tailscale port forwarding and file uploads. Install Portside 0.3+
on the same machine or filesystem where Codex/Claude runs, and enable uploads
on its server. See the Portside repository for installation and hosting setup.

Open **⋮ → Preferences → Portside**, select a Rex server, and enter the Portside
address and optional relative upload folder. The **Use image uploads** switch
turns uploads on or off while keeping the address and folder; tap **Save** to apply.
These rows are Rex connections,
not an imported Portside server list. Enter the actual Portside address: a bare
hostname defaults to port 9417. Rex's service tailnet address may differ from the
Mac's Portside address even when both run on the same machine.
**Check connection** shows the destination and size limit. Portside authenticates your Tailscale
identity; no upload token is stored. **Open Portside** launches its Android app
if installed; Rexlink uploads directly to the server and does not need that app.

Copy an image and use **Paste** from your keyboard or Rexlink's menu, or choose
**⋮ → Attach image** to open the system photo picker. PNG, JPEG, GIF and WebP
images are streamed, up to the server's limit or 100 MiB, whichever is smaller.
The returned server path is inserted into the active Codex/Claude prompt without
pressing Enter. If you switch panes or servers, the result stays ready for
explicit insertion into the original pane. Other programs also require explicit
insertion. Upload progress overlays the terminal without changing its grid size.
You can cancel, retry a failed transfer, or copy the completed server path.

Keep Tailscale connected. On Android 17+, allow local network access when asked.
The feature does not change Rex or the Portside server, configure port forwards,
or share settings with the separate Portside app.

### Background alerts

Enable **⋮ → Preferences → Background alerts** to receive attention notifications while the app
is backgrounded or the phone is locked. Allow notifications when Android asks.
A quiet ongoing notification shows monitoring status and a **Stop** action.
Only input/approval/sign-in requests and errors alert; running and finished tasks
do not. Tapping an alert opens its server, session and pane. Terminal control is
released and rendering pauses in the background; monitoring is read-only.

Monitoring stops when you remove Rexlink from Recents, force-stop it, or tap Stop.
It does not start at boot or require server-side push. Network loss, Android Doze
and device battery management can delay alerts; instant delivery is not guaranteed.

See [Android usage](rexlink-android/README.md),
[protocol findings](rexlink-android/PROTOCOL.md), and
[validation history](rexlink-android/VALIDATION.md).

## Project status

Rexlink is maintained on a **best-effort basis**. Support, updates, bug fixes and
compatibility with future Rex versions are not guaranteed. Use it at your own
risk: it is provided **AS IS, without warranties or conditions of any kind**.
The warranty disclaimer and limitations of liability are set out in the
[Apache 2.0 license](LICENSE).

## History and license

Both original repositories' histories are retained without rewriting their commit
IDs. See [HISTORY.md](HISTORY.md) for provenance and history-navigation commands.

Copyright 2026 Shulhi Sapli and contributors. Project code is licensed under
[Apache License 2.0](LICENSE). Third-party code, fonts, logos and sourced assets
retain their own terms; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
Rexlink is an independent, unofficial client and is not an official Rex product.
