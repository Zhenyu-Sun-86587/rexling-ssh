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
