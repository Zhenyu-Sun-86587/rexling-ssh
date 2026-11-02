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


## Application wheel input, takeover focus and viewport fitting

Private JNI packets now use RXT2. An input-flags u8 follows the three scrollbar
u64 values: mouse reporting=1, alternate screen=2, alternate scroll=4, application
cursor keys=8. The decoder also accepts historical RXT1 packets with flags zero.
These flags come from the pinned Ghostty public C API, including imported
snapshots; no program-name guessing decides whether a gesture sends input.

Local history scrolling remains independent of ownership. A live, visible pane
that negotiates mouse input receives Ghostty-encoded wheel press events with
coordinates and cell geometry. Without mouse reporting, alternate-scroll mode
uses cursor-key sequences according to DECCKM. The selected server/session/block
must match. The first application scroll takes ownership, fits the requested
phone grid, and writes the wheel batch once; uncertain writes are not replayed.
The keyboard opens only for an explicit content tap or keyboard/takeover action.
Horizontal pane swipes and local history drags do not request keyboard activation.
The existing UTF-8 terminal.write interface remains the transport; non-UTF-8
legacy mouse reports are rejected instead of corrupting input.

Takeover reapplies the resolved theme, then sends the verified block.event focus
payload `{type:focus,payload:{action:in}}`. This lets Neovim re-query the canonical
terminal background even when set_theme deduplicates an unchanged theme. The
replica still installs no WRITE_PTY callback and never fabricates OSC replies.
The approved Mac Neovim config resolves the owning client before choosing
appearance: native Mac Rex uses macOS appearance, while Rexlink control follows
the canonical terminal. Other non-Rex Mac terminals keep OS-based behavior.
Existing editors must restart to load it.
Clean Linux and configured Mac theme assertions pass through takeover/resume.
The tested Mac command-session process metadata reports /usr/bin/login rather
than nvim; exact foreground recognition is asserted on the Linux fixture only.

The user's font size stays fixed. An explicit content tap takes control and sends
the requested phone columns/rows with the takeover command, resizing the canonical
PTY so full-screen programs redraw/reflow. Opening a tab does not take ownership;
a wider terminal owned elsewhere can remain cropped until the user takes control.
When a neighbor preview becomes active, dimensions are replayed even if its View
size did not change. Regressions verify the fixed font and requested grid. The
pane indicator reserves 14 dp, down from 22 dp, reducing the gap above its dots.


## Task titles, read-only appearance and stale session closure

`com.superlogical.terminal.title` returns `{title,owner:{pid,name}}`, and
`program_status.records` can supply a root task title. The client prefers the live terminal title, then an owned root task record;
both must match the current foreground process by PID and name. Old shell/agent titles cannot label a new program.
Titles are bounded plain text; executable metadata independently selects icons.
The existing helper refreshes title metadata on `title_changed` and program
status/process events, with the periodic refresh as fallback. Header/default tab
labels follow the remembered focused pane. Explicit custom tab names remain.
OSC 7501 `title` values in fixtures are base64 UTF-8, as specified in the
[official protocol](https://www.superlogical.com/rex/docs/build/program-status).

Appearance is now sent only while Rexlink owns the pane. Merely viewing an
unowned pane must not overwrite its canonical colors. An observed Mac pane was
owned by native Rex Beta and rendered in dark macOS appearance, but retained
Rexlink's prior light palette in the server; its Neovim startup selected light.
The local Neovim helper reads ownership using the installed Rex CLI and applies
macOS appearance for native Mac Rex. Other owners use OSC 11. Async results are
generation-checked so a late OS lookup cannot override a later Android focus.
This optional local editor workaround is separate from the Android client's
independent direct connection; no bridge or server configuration is added.

Closing a session that disappeared is idempotent for the typed server error
`session_not_found` from attach/destroy. The catalog refreshes and stale selection
clears. Other errors remain errors; no uncertain destructive request is retried.

The extra-keys chevron beside Esc expands F1-F12 plus Home/PgUp/PgDn/End/Ins/Del.
F1-F4 use SS3 P/Q/R/S; F5-F12 use CSI 15/17/18/19/20/21/23/24~. Collapse or hiding
the system keyboard resets the horizontal strip to Esc. Input still requires
ownership. The pane overlay no longer arms an untimed toast when a swipe settles
back to the same pane.


## Shell identity, working state and keyboard reveal

Known shell executables (fish/zsh/bash/etc.) keep their executable name as the
header/default tab title. Their OSC title often repeats cwd, which already has
a dedicated directory caption. Non-shell applications retain their reported task
titles, and explicitly named tabs remain unchanged.

Program status records with state=working and a matching foreground PID/name
supply the `working` block-ID list in local metadata. Idle/blocked/done/error,
cleared records and stale owners do not animate. No title-text heuristic is used.
The active tab's existing blue underline gets a moving highlight. Program
icons stay static; their earlier pulse was removed. No indicator adds width. Animation values are consumed in drawing/layer updates, and idle
programs have no animation clock. Captions use explicit line heights without
font padding so the session chevron aligns with the small text.

The keyboard's leading chevron starts offscreen before Esc. A rightward swipe
reveals it; collapse or hiding the IME restores the offset that hides it again.
Normal keys share the available width, with a slightly wider Ctrl key and 3 dp
gaps. The normal row has a minimum width for narrow displays and remains
scrollable. The existing key palette and disabled-state opacity are preserved.


## Streamed titles, selection and catalog assets

The verified control envelope is a session_event whose payload.event is
`{type:block_event,payload:{block_id,creator_name,name,payload}}`.
Terminal title_changed payloads contain the title and owning process PID/name.
They update presentation directly for all panes in the attached session. The
selected pane's nonce-verified data APCs feed the same handler. Events queued
while awaiting RPC replies are processed rather than discarded. Title frames
need no per-frame metadata RPC. Latest streamed titles are merged over older
poll results, and process changes invalidate titles from the previous owner.
Unicode animation characters remain part of the title; no spinner is invented
or parsed out of it. An empty title is respected as a clear.

Long press freezes the displayed terminal screen for selection, selects a word,
and offers native Copy / Select all actions. Selection handles can extend or
reverse the range. Copy uses plain cell text and includes wide glyphs once,
retaining spaces and physical line breaks. Selection never acquires ownership,
sends terminal input or opens the keyboard. Dismissal/pane departure releases
the frozen screen. The existing Copy viewport menu remains available.

The pane picker now uses the current program tile, live title (or executable
name), directory caption, and selected-pane marker. The supplied Superlogical
catalog SVGs are retained under artwork/program-icons/superlogical with hashes
and attribution. A local WebKit converter renders their SVG masks, gradients
and Display-P3 colors into packaged PNG tiles. Runtime Android rendering needs
no browser or network request. Supported kinds use those tiles in the header,
tabs, pane picker and swipe preview; unsupported kinds retain their fallback.
The user's final transparent launcher PNG is copied exactly, preserving alpha.


## Closing tabs and panes

Verified `block.close` with explicit `session_id` and `block_id`, and `session.close_window` with explicit `session_id` and `window_id`. A tab close ends every program in the window; closing its final pane also removes that window. The session can remain empty, and `session.view` may omit `windows` then.

Commands bind the selected session and explicit target. Tab confirmation also binds the pane-ID list and rejects changed membership before sending. Typed `not_found` / `session_not_found` are treated as already gone; uncertain network failures are not retried. Background closes preserve the active stream and control. Closing the selected target opens a neighbor read-only, without resizing or taking ownership.


## Shift and navigation keys

Shift+arrows, Home/End, editing and function keys use xterm modifier parameters (1 + Shift + 2*Alt + 4*Ctrl); Shift+Tab sends CSI Z. Printable Shift applies ordinary ASCII case/symbol mapping. Reference: [xterm PC-style function keys](https://invisible-island.net/xterm/ctlseqs/ctlseqs.html#h2-PC-Style-Function-Keys).

Shift can latch for one key or remain held for multiple keys. Cancellation, pane/session departure, hiding a server, loss of control and pause clear it. A late finger-up cannot relatch cleared Shift. The shared navigation strip scrolls independently; scrolling and cancellation send no key. Hardware named keys also carry modifier parameters.


## Visible web links in the Android renderer

Web URL detection is local presentation logic; no new Rex method or server configuration is involved. Visible HTTP(S) and www-prefixed URLs are mapped from UTF-16 text back to terminal cell ranges, excluding wide-character spacers, hidden text and surrounding punctuation. Row-record caches retain underline/link ranges and track adjacent-row continuation changes only for boundary links.

A deliberate tap opens an HTTP(S) ACTION_VIEW/BROWSABLE intent; pointer-down and pointer-up must still identify the same link. Scrolling, swipes, cancellation and selection never launch it. Browser launch does not acquire terminal ownership or write input, and missing handlers are handled locally. [Android intent reference](https://developer.android.com/training/basics/intents/sending).

The Android cell snapshot has no soft-wrap flags. Printed-URL detection remains limited to one physical row and conservatively rejects possible wrapped prefixes. OSC 8 destinations are now exported explicitly as described below; their labels can span rows. Localhost is not rewritten.


## OSC 8 metadata and RXT3

The pinned public Ghostty C API exposes GHOSTTY_CELL_DATA_HAS_HYPERLINK and ghostty_grid_ref_hyperlink_uri. Only linked leading cells perform viewport grid-reference lookup; copied destinations are bounded to 64KiB. No extra server methods or configuration are required. Native cells carry an optional URI, so changing only the destination invalidates the row.

Local JNI packets now use RXT3. Its header, input flags and cell records match RXT2. Each changed row appends u16 link-range count followed by sorted, non-overlapping ranges: u16 start column, u16 exclusive end, u32 UTF-8 length and URI bytes. Adjacent cells sharing one URI coalesce. Plain rows cost two extra bytes. Android still accepts RXT1/RXT2. Full frames and row deltas both carry ranges; zero ranges removes old links. JSON preview cells append an optional eighth URI element.

Explicit OSC 8 destinations take precedence over visible URL-shaped labels; automatic matching is masked inside explicit links. Only HTTP(S) targets are opened. Known OSC 8 targets remain usable when labels wrap across rows, unlike guessed printed-URL continuations. Long-press continues selecting the label text. Snapshot, streaming, destination-only replacement/removal and resume were verified on both owned servers.
