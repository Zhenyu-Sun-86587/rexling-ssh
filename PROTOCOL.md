# Android creation protocol observations

Verified against the user's existing owned Tailscale Rex server on 2026-11-01,
API revision 7. The server's advertised schemas were read again before adding
creation. Both methods were exercised through the app's Rust actor and Android
UI in disposable sessions. No server configuration or authentication changed.

The base client/control/data protocol and native Ghostty source provenance are
described in README.md. The app connects directly with authenticated control and
data WebSockets. It does not invoke a Rex executable or a bridge at runtime.

## New tab

UI JNI command: `{"type":"new_tab","session":"session:<id>"}`.
The actor requires this session to still be selected and connected, then sends
`session.new_window` with:

```json
{
  "session_id": "session:<id>",
  "window_label": "Terminal 3",
  "focus": false,
  "layout": {"block": {"flavor": "com.superlogical.terminal.shell"}}
}
```

The label is generated from the current window count. The reply supplies
`window_id`, `block_ids` and `revision`. The app opens the first returned block
with the existing authenticated data-ticket/snapshot/VT path, refreshes the view,
and selects the corresponding local tab. `focus:false` avoids requesting a
change to the server's active window for other clients.

## New pane

UI JNI command: `{"type":"new_pane","session":"session:<id>","anchor":"block:<id>"}`.
Both IDs must still match the actor's selection. It sends `session.new_split`:

```json
{
  "session_id": "session:<id>",
  "anchor_id": "block:<id>",
  "direction": "horizontal",
  "side": "after",
  "focus": false,
  "layout": {"block": {"flavor": "com.superlogical.terminal.shell"}}
}
```

The reply supplies `anchor_id`, `block_ids`, `split_ids` and `revision`. The app
opens the new block as above. This changes the actual server layout: the new
shell is to the right of the anchored pane, with the server's default split
ratio. The phone displays one pane at a time.

The terminal flavor needs no custom options: it uses the user's default shell.
The advertised `session.view` schema defines layer blocks in layout order; that
order supplies swipe navigation and the pane-position bars. Non-terminal blocks
remain excluded from this terminal-only UI.

Creation progress disables the UI's creation action and pane/tab changes. The
actor never queues a creation for replay during reconnection. If a reply is lost,
the result may be uncertain; view refresh reveals existing resources without
starting the shell again. Stale session/anchor commands are rejected locally
before any server mutation, as verified by the native integration test.

## Read-only carousel previews and session details

The actor polls cancellable preview futures on its existing owner thread. A
preview uses a separately labelled authenticated control connection, attaches
the current session, obtains the normal data ticket and decodes the real Ghostty
snapshot through FINISH. It then closes the data/control connections. Preview
clients never take ownership or send input. At most two neighbors are requested
per update, with a bounded timeout. One-shot preview frames remain queued until
JNI polling consumes them; ordinary live-frame publication cannot overwrite them.

Session counts come from attached `session.view` responses. Attachment state
comes from `client.list` with kinds `["app"]` and its session_ids, connection
state and authenticated device node. The current client and labelled internal
preview/summary clients are identified separately. The UI shows known values
and omits unavailable remote usage dates. New Session calls verified
`session.create` with one `com.superlogical.terminal.shell` initial window.

Local navigationId values correlate UI requests with completed native selection;
they are not Rex wire fields. Native cell flags retain default-color and palette
roles so Android can render System/Dark/Light while preserving explicit RGB.
Session and pane snapshots are not persisted to disk.

## Terminal appearance and Neovim

Verified on the owned revision-7 server: `com.superlogical.terminal.set_theme`
uses `{session_id, block_id, args}`. `args` contains `scheme: "dark" | "light"`,
`background`, `foreground`, `cursor` as `#RRGGBB`, and `palette` as strings such
as `"0=#202432"` (Android sends entries 0–15). The return value is `{}`.
Rex applies these colors immediately if the caller owns the block or no client
owns it, and otherwise queues them until that client becomes owner. This is the
same ownership policy documented for terminal resize.

Compose sends its resolved System/Dark/Light palette to the native actor. The
actor retains it across disconnect/pause, registers it before opening/resuming
the selected data stream, and reapplies it on takeover and appearance changes.
It sends no theme requests for neighbor previews or unselected panes. The
canonical server terminal answers program color queries and notifies programs
of scheme changes. Android's Ghostty replica still has no WRITE_PTY callback;
it never injects its own OSC replies into the remote PTY.

The live actor regression runs a disposable `nvim --clean -n -i NONE` with a
statusline showing `&background`. On installed Neovim 0.12.6, changing appearance
updates that value light → dark → light, including pause/resume and takeover
from another client. A non-owner's pending light theme leaves the current
owner's dark theme in effect. The test never sets Neovim's background option.
All disposable sessions are destroyed. Neovim configurations that force their
own background/colorscheme can override automatic detection.

Reproduce the native test (requires an owned server with `nvim` on PATH):

```sh
REX_TEST_SERVER=https://rex.taildaac1.ts.net \
GHOSTTY_PREFIX="$PWD/.build/ghostty-host" \
CARGO_TARGET_DIR="$PWD/.build/rust" \
cargo test --locked --manifest-path native/Cargo.toml \
  live_neovim_theme_follows_ownership_and_survives_pause -- --ignored
```

Neovim's automatic color response behavior is documented in its
[defaults implementation](https://github.com/neovim/neovim/blob/master/runtime/lua/vim/_core/defaults.lua)
and [`background` option](https://neovim.io/doc/user/options/#'background').


## Program icons and working directories

Verified read-only terminal methods: `com.superlogical.terminal.process` and
`com.superlogical.terminal.program_status`, each with
`{session_id, block_id, args:{}}`. `process.foreground` supplies executable name,
argv0, resolved/invoked executable paths, optional script and cwd. `process.home`
provides the actual home path; abbreviation checks a complete path boundary.
Missing foreground cwd stays blank rather than displaying the shell's old cwd.
When foreground itself is unavailable, child metadata provides the fallback.

`program_status.records` may expose app/effective_app identifiers. Android uses
those only when record.owner matches the current foreground **pid and name**;
a record from an exited program cannot incorrectly label the next program.
Reports remain program-supplied hints, not trusted identity. The app selects
bundled vector assets by known identifiers. No server-supplied image is fetched
or executed. Unknown programs fall back to a generic terminal symbol.

A separate authenticated, labelled `rex-android-programs` helper reads metadata
for the selected session's panes. It never owns blocks, writes input, resizes,
or sets themes. Its future runs alongside live input/output on the actor's
existing runtime. Refresh is approximately every four seconds and is brought
forward by selected-pane process/status APCs. Queries are bounded to 64 panes
and eight seconds; metadata is cancelled on session change, pause and disconnect.
Closed/replaced pane lists discard stale metadata. Helper clients are excluded
from user-facing attachment summaries.

JNI state carries `programs` (block ID → executable identifier candidates) and
`directories` (block ID → display cwd). It omits process arguments, other
process/account details and persists neither collection. Header/tab stacks show
up to three icons, with the selected or remembered pane in front. All panes stay
available in the picker. Inner programs behind SSH/tmux are only identifiable
when the server supplies suitable process/report information.

The native regression `live_program_icons_follow_reports_in_selected_and_background_tabs`
uses disposable OSC 7501 fixtures for Claude/Codex app reports, changes and clears
them, and verifies that a background tab refreshes without selecting or owning
it. These are protocol fixtures, not launched coding agents. The existing live
Neovim test also verifies actual foreground recognition. Identifier mappings,
stale owners, home-path boundaries and missing/invisible directory text have
native and Kotlin tests.

SVG paths are from Simple Icons 15.0.0 (CC0), converted to Android vector assets.
Pinned source URLs/checksums and the license ship in APK assets under `licenses/`.
The Android label and control client name are Rexlet/rexlet. The package remains
`dev.shulhi.rex`, preserving the existing installation, preferences and JNI ABI.


## Local scrollback and edge rendering

The pinned libghostty-vt exposes `ghostty_terminal_scroll_viewport` and
`GHOSTTY_TERMINAL_DATA_SCROLLBAR`. Android now uses these on its existing
replica. Scrollbar total/offset/visible counts are exported with each frame.
Local `scroll` JNI commands carry the expected block ID; stale targets are
ignored. Scrolling sends no Rex request or PTY bytes and requires no ownership.
The native snapshot retains history, not only the live screen. New VT remains
applied while the history viewport is anchored. Input and paste first return
the local viewport to the active area; reconnect with a new replica starts live.
The alternate screen has no terminal history; application-level scrolling is
not emulated by these local history gestures.

A native round-trip test encodes/imports a 40-line history, scrolls to the top,
adds live output without moving the viewport, returns to the bottom and verifies
alternate-screen behavior. The owned-server actor test imports 80 history lines,
scrolls without ownership, receives new output while anchored, then takes control
and types to verify return to live output. Android dragging distinguishes vertical
history scrolling from horizontal pane navigation. Pixel movement accumulates
into whole terminal rows; flings use Android OverScroller. The Latest control and
Shift+PageUp/PageDown/mouse wheel also use the local viewport API.

Padding moved into TerminalView so background spans can extend the first/last
visible rows and columns to the rounded card edges. Each adjacent color is
preserved separately, including inverse and wide-cell styles. Glyphs/cursor stay
clipped to the content rectangle. Resizes exclude the insets and footer space.


## Multiple direct servers and shared session navigation

Each saved host owns an independent native Session actor, control/data sockets,
resume state and Ghostty replica. There is no multiplexing proxy or new server
API. The coordinator saves host profiles and routes server-qualified local UI
keys to the correct connection. Identical session IDs on different hosts cannot
collide. Only the original Rex session ID is sent to that host. The header and
search picker flatten connected hosts in saved order; disconnected hosts are
omitted. Long-press history can return to a session on another host.

Switching the visible host releases the old block claim, keeps its sockets and
terminal state, and blocks stale hidden-view input/resize callbacks. Hidden
hosts continue polling at a lower cadence. Foreground/background lifecycle
applies to every connected actor. Releasing without a selected block is ignored.
Disconnect/Forget only affect the selected local connection/profile; they never
delete remote sessions. New sessions are created on the currently viewed host.

Integration used two distinct existing owned endpoints:
`https://rex.taildaac1.ts.net` (Linux, server API 7) and
`https://shulhi-mbp-rex.taildaac1.ts.net` (Mac, server API 9, accepted the existing
7–8 client negotiation). Both authenticated directly over the existing Tailscale
HTTPS listeners. No server configuration or authentication settings changed.

Reproduce the native two-host test with explicitly owned servers:

```sh
REX_TEST_SERVER=https://rex.taildaac1.ts.net \
REX_TEST_SECOND_SERVER=https://shulhi-mbp-rex.taildaac1.ts.net \
GHOSTTY_PREFIX="$PWD/.build/ghostty-host" CARGO_TARGET_DIR="$PWD/.build/rust" \
cargo test --locked --manifest-path native/Cargo.toml \
  live_two_servers_isolate_input_selection_and_lifecycle -- --ignored
```

The native test creates and destroys its own sessions. Android MultiServerTest
requires two pre-created disposable shell fixtures passed as instrumentation
arguments `server_a`, `server_b`, `session_a`, `session_b`; it intentionally skips
without explicit fixture arguments. It writes only to those fixtures and checks
host-isolated input, hidden-view rejection, shared session routing/jump-last,
independent disconnect and lifecycle recovery. Destroy fixtures after running.


## Notified local display updates (RXT1)

This is a private JNI transport inside Rexlet, separate from Rex's control/data
wire formats. Rex authentication, snapshots and VT streams are unchanged.
`awaitUpdate(handle, afterRevision, timeoutMs)` waits on a condition variable
without holding the native handle registry. Publish and destroy wake readers.
`readUpdate` coalesces to the newest state, with one decoder per handle. JNI
commands and the legacy diagnostic `poll` API remain JSON; production live
screen delivery uses UTF-8 binary changed rows.

The little-endian packet begins `RXT1`, revision u64, metadata length u32 and
metadata UTF-8 bytes. Unchanged metadata has length zero. The frame flag is:
0 unchanged, 1 no frame, 2 row delta, 3 full replacement. Flags 2/3 carry columns
and rows u16, cursor x/y i32 (-1 hidden), foreground/background ARGB u32, then
scroll total/offset/visible u64. A changed-row count u16 follows. Each row has
its y and cell count u16; cells contain x u16, width u8, attributes/foreground/
background u32, and length-prefixed UTF-8 text (u32 length). Unchanged rows retain
their Kotlin list identity. Missing rows are empty in full replacements.

The encoder compares against the last delivered frame, so skipped native
revisions are safe. Unknown bases, dimensions or pane identity changes force a
full frame. The decoder validates bounds, row uniqueness, lengths, flags and
revisions before committing its new base. Preview snapshots remain one-shot
bounded JSON metadata and are not repeatedly attached to live frame updates.
No reconnect tokens, data tickets or terminal process arguments are added.

A visible native actor coalesces extraction at 16 ms while dirty; clean actors
only perform maintenance. A hidden actor keeps applying incoming VT and updating
session metadata but does not extract screen frames. Selecting it requests a
fresh frame. Android waits for revisions on IO, decodes away from the UI thread,
and publishes visible frames on the display clock. Metadata flows omit screen
and revision churn. Pane movement reads translation in the graphics layer.

## Closing sessions from the picker

Installed server API discovery confirms `session.destroy {session_id}` closes
all windows/blocks and hangs up their programs. The caller must be attached;
missing sessions fail with `session_not_found`. A short-lived authenticated
`rex-android-close` control client attaches to the explicitly confirmed target,
sends destroy once and closes. It takes no block ownership and does not move
the main client's viewed session when closing an inactive session. Errors or
uncertain replies are reported, never automatically replayed. The normal
session list/events synchronize all connected clients afterward.

The UI binds confirmations to both server endpoint and server-qualified session
ID, rejects stale targets, and prevents duplicate closes while one is pending.
Both swipe directions reveal Close; the row menu and accessibility action offer
the same operation. Cancel sends no mutation. The previous-session marker is
plain text and the old footer gesture hints are removed. Closing the current
session opens another available session through the existing selection logic.
Disconnect and Forget remain local connection/profile actions in Servers.
