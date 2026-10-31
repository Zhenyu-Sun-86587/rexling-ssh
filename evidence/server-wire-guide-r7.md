# Rex API

> How to connect to a Rex server, call its methods, receive its events, and stream a block's
> output, followed by a reference for every method and every server and session event.

A Rex server runs your terminals and keeps them running whether or not anything is looking at them.
Clients, such as Rex.app and the `rex` CLI, connect to the server to show and control them.

This page documents the API that every client uses, so that you can write your own client, script,
or tool. The server you fetched it from builds it from its own API, so it always matches that
server's version. It describes API revision 7.

## How Rex Is Organized

Rex organizes work into four kinds of things. Most methods act on one or more of them.

- A **session** is a group of related work, such as one project. The server keeps a session running
  whether or not anything is looking at it.
- A **window** is a tab within a session. It holds a layout of blocks.
- A **block** is one application in a window. Today that means a terminal. Rex.app shows a block as
  a pane.
- A **client** is a program connected to the server, such as Rex.app, the `rex` CLI, or a script.

A window's **layout** divides its space between blocks with **splits**, side by side or one above
the other. A window can also have **floating layers**, which hold blocks above the layout. A block
that is still running but isn't placed in any window is **detached**. A session's **active
window** is the one it shows, and each window has one **focused** block, the one that receives
typing.

Every block is made by a **creator**, which has a name such as `com.superlogical.terminal`. A
**flavor**, such as `com.superlogical.terminal.shell`, is one way a creator can make a block.

Every session, window, block, and client has an **ID** that never changes and is never reused, such
as `block:01a10d99-0c06-7795-bf67-798ce4c36a33`. Each can also have a **label**, which is a name a
person chooses and can change.

A client can claim a block with `session.own_block` and become its **owner**. While a terminal has
an owner, it takes its size and colors from that client. An **action** is a named thing a client
can do, such as opening Rex.app's command palette or clearing a terminal's screen.

## Endpoints

The server answers HTTP requests on the same address that clients connect to. A local server
listens on a Unix socket, or on a named pipe on Windows. `rex server status` prints the address on
its `endpoint` line. A server that a `rex` command started also puts it in the `REX_SERVER`
environment variable of each of its terminals. Over a Unix socket or a named pipe, any host name
works in a URL, such as `ws://localhost/api/control`.

If the server doesn't let a caller in, such as a Tailscale user that its access rules don't allow,
it answers every request with status 403 and a JSON body such as `{"error": "..."}`.

| Endpoint | What it serves |
| --- | --- |
| `GET /api/control` | The control connection, a WebSocket that carries every method call and event. |
| `GET /api/data` | Data connections, WebSockets that each stream one block's output, such as everything a terminal's program prints. |
| `GET /api/help` | Every method as JSON, with the exact JSON Schema of its arguments and its result. |
| `GET /llms.txt` | This page, as Markdown. |
| `GET /llms.html` | This page, as HTML. |

## Methods

The API is a set of methods, such as `session.create` and `session.view`. To call a method, a client
sends the method's name and its arguments, a JSON object, on the control connection. The server
answers with the method's result, also a JSON object, or with an error.

A method's name starts with what it acts on, such as `session.` for sessions and everything in them,
`client.` for clients, and `server.` for the server itself. Blocks add methods of their own, named
after their creator, such as `com.superlogical.terminal.write`.

The `rex` CLI makes the connection for you, so it is the quickest way to try a method:

```sh
rex api list                         # list every method with a short description
rex api describe session.view        # show one method's arguments, result, and examples
rex api call -s work session.view    # call a method and print its result
```

The server ignores fields it doesn't know in a method's arguments. A client written for a newer
server can still call an older one, as long as it sends the fields the older server needs.

These are the methods this server provides. The method reference at the end of this page describes
each one in full.

- `block.close`: Close a block and end the program running in it.
- `block.event`: Send an input event, such as a key press or a paste, to a block.
- `block_creator.list`: List the creators the server can make blocks with, and their flavors.
- `client.action`: Perform an action from the configuration file or a block, as this client.
- `client.inspect`: Get one connected client's details, including the actions it offers.
- `client.key`: Send a key to a block, giving Lua key bindings the first chance at it.
- `client.keymap`: Get what each key does for this client. The result combines the client's own bindings with the configuration file and the client's Lua scripts.
- `client.list`: List the clients connected to the server, in the order they connected.
- `client.perform`: Ask another client to perform one of its actions and wait for its answer.
- `client.reply`: Answer a client_call_requested or client_perform_requested event.
- `client.set_label`: Set or remove this client's label.
- `client.set_script`: Load Lua scripts for this client, replacing any it loaded before.
- `data.connect`: Get a one-time ticket for opening a data connection to a block. A data connection streams a block's content as it changes, such as a terminal's output.
- `help.all`: Describe every method the server provides, including block methods.
- `hosts.list`: List the server's hosts: the other Rex servers you connect to by label.
- `hosts.put`: Add a host to the server's host list, or replace the host with the same ID.
- `hosts.remove`: Remove a host from the server's host list.
- `server.apple.grant_file_access`: Let the server, and every program it runs, read files a Mac app was given.
- `server.apple.privacy_status`: Report the macOS privacy permissions the server and its programs have.
- `server.config_reload`: Reload the configuration file and tell every client to fetch its keymap again.
- `server.status`: Get the server's identity, session count, configuration, and Tailscale status.
- `server.stop`: Shut the server down, or with if_idle, only if nothing is using it.
- `server.tailscale.disable`: Turn off built-in Tailscale, keeping its sign-in for the next enable.
- `server.tailscale.enable`: Turn on built-in Tailscale so devices on your tailnet can reach the server.
- `server.tailscale.login`: Ask Tailscale for a sign-in link for the server's Tailscale device.
- `server.tailscale.logout`: Sign the server out of Tailscale and turn off built-in Tailscale.
- `server.tailscale.rename`: Change the server's Tailscale device name without signing in again.
- `server.version`: Get the version of Rex that the server is running and how it was built.
- `server.whoami`: Get how the server identified you, and the account the server runs as.
- `session.attach`: Attach to a session: start receiving its events and get its current view.
- `session.attach_all`: Start receiving events from the whole server and get the list of sessions.
- `session.bootstrap`: Set up a new connection in one call: receive events, list sessions, and attach.
- `session.close_layer`: Close a floating layer and every block in it.
- `session.close_window`: Close a window and every block in it.
- `session.create`: Create a session, optionally with its first windows already in it.
- `session.describe_block`: Get a block's details and the methods, events, and actions it provides.
- `session.destroy`: Destroy a session and close every block in it.
- `session.detach`: Detach the connection from a session. The connection stays open and the session keeps running.
- `session.detach_block`: Take a block out of its window without closing it. The block keeps running.
- `session.disown_block`: Give up the calling client's claim on blocks. The result lists each block's owner.
- `session.float_block`: Move a block out of its window's layout into a new floating layer.
- `session.focus_block`: Focus a block, and make its window the active window if it isn't.
- `session.focus_direction`: Move focus to the nearest block to the left, right, above, or below.
- `session.focus_next_pane`: Move focus to the next block in the active window.
- `session.focus_next_window`: Make the next window the active window.
- `session.focus_previous_pane`: Move focus to the previous block in the active window.
- `session.focus_previous_window`: Make the previous window the active window.
- `session.focus_window`: Make a window the active window. Focus goes to the block it last had focused.
- `session.help`: Describe the methods this session accepts and the methods its blocks provide.
- `session.list`: List the sessions on the server.
- `session.list_blocks`: List every block in the session, including detached blocks.
- `session.list_clients`: List the clients attached to the session.
- `session.lower_layer`: Move a floating layer to the back, or just below another floating layer.
- `session.move`: Move a session to another place in the order that session.list returns.
- `session.move_block`: Move a block, or place a detached one, in a new split beside another block.
- `session.move_window`: Move a window to another place in the session's window order.
- `session.new_block`: Create a block without placing it in a window. Place it later with session.move_block.
- `session.new_layer`: Create blocks in a new floating layer at the front of a window.
- `session.new_split`: Create blocks in a new split beside an existing block or split.
- `session.new_window`: Create a window and the blocks in its layout. It becomes the active window by default.
- `session.own_block`: Claim blocks for the calling client. The result lists each block's owner.
- `session.raise_layer`: Move a floating layer to the front, or just above another floating layer.
- `session.resize_layer`: Move or resize a floating layer.
- `session.resize_pane`: Move the border between two blocks to change their sizes.
- `session.set_block_label`: Set a block's label.
- `session.set_label`: Set the session's label.
- `session.set_window_label`: Set a window's label.
- `session.swap_blocks`: Swap the places of two blocks.
- `session.view`: Get the session's windows, their layouts, and their blocks in one snapshot.
- `session.zoom_block`: Zoom a block to fill its window, or undo the zoom.
- `target.complete`: List the labels, window positions, and short IDs that start with a prefix. Use it to complete a name as someone types it.
- `target.resolve`: Look up sessions, windows, blocks, or clients by ID, label, or position. Each query gets its own result, which says whether it matched one thing, nothing, or several.

Blocks made by `com.superlogical.onboarding` provide these methods:

- `com.superlogical.onboarding.set_step`: Move the walkthrough to another step. Emits step_changed when the step changes.
- `com.superlogical.onboarding.state`: Return the step the walkthrough is on.

Blocks made by `com.superlogical.terminal` provide these methods:

- `com.superlogical.terminal.blob_read`: Read a result that was too large for one reply or event, such as the output of format with max_bytes set, or a selection_copied event without text. The server keeps each result for about five minutes.
- `com.superlogical.terminal.clear`: Clear the screen and scrollback, keeping the current line at the top. It changes nothing while a full-screen program, such as an editor, is running.
- `com.superlogical.terminal.clipboard_read`: Read text that a program in the terminal copied to the clipboard. The server keeps each copy for about a minute, so a client can read text too large to send in an event.
- `com.superlogical.terminal.format`: Get the screen and scrollback as plain text, HTML, or escape sequences.
- `com.superlogical.terminal.input_mode`: Get how the terminal handles typed input, such as whether it is echoed. On Windows the call fails with not_found.
- `com.superlogical.terminal.list_dir`: List the subdirectories of a directory, and optionally its files. A relative path or ~ is resolved the way a program in the terminal would resolve it.
- `com.superlogical.terminal.modes`: List the terminal modes that programs can turn on, such as the alternate screen, bracketed paste, and mouse reporting, with each mode's name, number, and whether it is on. It doesn't wake an idle terminal.
- `com.superlogical.terminal.process`: Get the processes running in the terminal and how its program exited.
- `com.superlogical.terminal.program_status`: List the status that programs in the terminal have reported. Programs report a status, such as working or blocked, with OSC 7501 escape sequences.
- `com.superlogical.terminal.pwd`: Get the working directory that a program in the terminal last reported. To find where each process actually is, call process instead.
- `com.superlogical.terminal.reset`: Reset the terminal to its starting state and clear the screen and scrollback. The program keeps running, and the size and default colors stay the same.
- `com.superlogical.terminal.resize`: Request a terminal size in columns and rows. It applies at once if the caller owns the block or no client does, and otherwise when the caller becomes the owner.
- `com.superlogical.terminal.set_theme`: Replace the terminal's default colors. As with resize, they apply at once if the caller owns the block or no client does, and otherwise when the caller becomes the owner.
- `com.superlogical.terminal.size`: Get the terminal's size, its owner, and the size each client last requested.
- `com.superlogical.terminal.title`: Get the terminal's title and the process that set it.
- `com.superlogical.terminal.write`: Type text into the terminal as if the user had typed it. The call returns once the text is queued, without waiting for the program to read it.

## The Control Connection

A client opens one control connection to the server and keeps it open for as long as it runs. The
control connection is a WebSocket at `GET /api/control`. Ask for the subprotocol `rex.control.v1`,
which WebSocket libraries send in the `Sec-WebSocket-Protocol` header.

Every message, in both directions, is one JSON object in one WebSocket text message. The server
offers `permessage-deflate` compression without context takeover, and works with or without it.

The server sends three kinds of message, each named by its `type`: one `welcome` when the
connection opens, a `response` to each request, and `event` messages, which it sends without being
asked. Skip a message whose `type` you don't know, because a newer server may add one. A client
sends one `hello`, then only `request` messages. The server skips a message whose `type` it doesn't
know. Older servers close the connection instead, so send a new type only when the welcome's
`api_revision` includes it.

### Opening the Connection

Within 5 seconds of opening the WebSocket, send a hello:

```json
{
  "type": "hello",
  "api_revision": 7,
  "client": {"kind": "cli", "name": "my-tool", "version": "0.1.0"}
}
```

A hello has these fields:

- `type` (string, required): Always hello.
- `reconnect_token` (string): The reconnect_token from the last welcome, to continue that client. Leave it out to start a new client. Never log it.
- `client` (object or null): What the connecting program is, for showing to people. Keep the whole hello under 512 bytes.
  - `kind` (string): What sort of program the client is: app, cli, agent, or other. Left out if the client didn't say. Treat a kind you don't know as other.
  - `platform` (string): What the client runs on: macos, ios, linux, windows, freebsd, web, or other. Left out if the client didn't say. Treat a platform you don't know as other.
  - `name` (string): The program's name, such as Rex.app or rex.
  - `version` (string): The program's version, as the program prints it, such as 1.6.0.
  - `label` (string): The client's label, such as studio. Two clients can share a label.
- `api_revision` (integer): The newest revision of the control API the client speaks. Left out means 0.
- `api_min_revision` (integer): The oldest revision of the control API the client can use. Left out means 0.

Keep the whole hello under 512 bytes, and don't compress it. A hello
can carry a reconnect token, and compressing a secret can let someone who watches the connection
guess it. If your WebSocket library compresses messages, set it to leave messages under
512 bytes uncompressed, as Rex's own clients do.

The server answers with a welcome, which is always its first message:

```json
{
  "type": "welcome",
  "protocol": "rex.control.v1",
  "limits": {"max_message_bytes": 1048576},
  "client": {"id": "client:<uuid>", "reconnect_token": "<token>"}
}
```

A welcome has these fields:

- `type` (string, required): Always welcome.
- `protocol` (string, required): The protocol, rex.control.v1.
- `limits` (object, required): The limits of this connection.
  - `max_message_bytes` (integer, required): The largest message the server sends or accepts, in bytes. Never below 65536. Send no more than the smaller of this and your own limit.
- `carriers` (array of objects or null): Other ways to reach the same server, called carriers. Left out when there are none.
  - `carrier` (string, required): The carrier's name, such as quic. Ignore a carrier you don't know.
  - `port` (integer, required): The port the carrier accepts connections on, on the host you connected to.
  - `alpn` (array of strings or null, required): The TLS application protocols the carrier accepts, most preferred first. Offer them in the TLS handshake.
- `client` (object or null): The client this connection serves. Left out by servers that can't resume clients.
  - `id` (string, required): The client's ID. It stays the same when the client reconnects.
  - `reconnect_token` (string, required): Send this in a later hello to continue this client. Each welcome has a new token, and the old one stops working. Never log it.
  - `resumed` (boolean): True if this connection continued an existing client. False for a new one, including when the server didn't accept the token.
- `features` (array of strings or null): Optional behaviors the server supports. Send a newer field or value only if its feature is listed. Ignore names you don't know.
- `api_revision` (integer): The revision of the control API every later message on this connection uses. Left out means 0.
- `api_server_revision` (integer): The newest revision of the control API the server speaks. Left out means 0.
- `api_min_revision` (integer): The oldest revision of the control API the server speaks. Left out means 0.

Check `type` and `protocol` before you send anything else. Ignore fields you don't know, because
newer servers add them.

If the server can't serve the client, it closes the connection instead of sending a welcome. The
section "When the Server Closes the Connection" lists the reasons.

### API Revisions

Each change to what clients and servers send each other, such as a new field, method, or event,
gets a new API revision, one higher than the last. This page describes API revision 7.
This server speaks API revisions 0 through 7. When a client
uses an older revision, the server translates every message to and from that revision's shapes, so
an older client keeps working with a newer server.

Send the API revision your client was written for in `api_revision`. A client that leaves it out
uses revision 0. To receive messages
in the shapes this page shows, send 7.

Send the oldest revision your client can use in `api_min_revision`. Leaving it out means 0, which
tells the server that your client can use every revision from 0 through `api_revision`. The server
picks the newest revision that both sides speak and names it in the welcome's `api_revision`. Every
later message on the connection uses that revision's shapes.

If the client and the server share no revision, the server closes the connection with status 1008.
The reason says which side to update.

### Calling a Method

To call a method, send a request:

```json
{"type": "request", "id": "1", "method": "session.create", "payload": {"label": "work"}}
```

A request has these fields:

- `type` (string, required): Always request.
- `id` (string, required): Any non-empty string you choose. The response carries the same id, so you can match it to this request.
- `method` (string, required): The method to call, such as session.view.
- `payload` (any): The method's arguments, as a JSON object. Leave it out for a method that takes none.

An `id` can be any string that isn't empty, but it must differ from the `id` of every request that
is still waiting for its response. If it doesn't, the server closes the connection.

The server answers with a response that carries the same `id`:

```json
{"type": "response", "id": "1", "seq": 0, "payload": {"session_id": "session:<uuid>"}}
```

If the call fails, the response has an `error` instead of a `payload`, such as for a request that
names a block that doesn't exist:

```json
{
  "type": "response",
  "id": "2",
  "seq": 0,
  "error": {"code": "not_found", "message": "block not found", "status": 404}
}
```

A response has these fields:

- `type` (string, required): Always response.
- `id` (string, required): The id of the request this answers.
- `seq` (integer, required): The seq of the last event queued for this connection before the server started the request, or 0 if there was none. Apply events with a higher seq on top of the result.
- `payload` (any): The method's result. Left out when the request failed.
- `error` (object or null): Why the request failed. Left out when it succeeded.
  - `code` (string, required): What kind of failure it was, such as bad_request or not_found. Branch on this, and go by status for a code you don't know.
  - `message` (string, required): What went wrong, written for people. Its wording can change between releases.
  - `status` (integer, required): A number with the same meaning as an HTTP status code, such as 404.

### Order of Requests

The server can run several requests at the same time, so responses can arrive in a different order
than their requests. Wait for a response before you send a request that depends on it.

Some methods are different. The server runs them one at a time, in the order they arrive on the
connection, so that keys and the changes bound to them happen in the order they were typed. Each
one waits for the one before it to finish. These methods run in order: `block.close`, `block.event`, `client.action`, `client.key`, `session.close_layer`, `session.close_window`, `session.detach_block`, `session.disown_block`, `session.float_block`, `session.focus_block`, `session.focus_direction`, `session.focus_next_pane`, `session.focus_next_window`, `session.focus_previous_pane`, `session.focus_previous_window`, `session.focus_window`, `session.move_block`, `session.move_window`, `session.new_layer`, `session.new_split`, `session.new_window`, `session.own_block`, `session.swap_blocks`, `session.zoom_block`.

Every other method can run at any time, alongside the ordered ones. That includes every block
method, such as `com.superlogical.terminal.write`. To type text in two calls, wait for the first
call's response before you send the second, or send all the text in one call.

`client.action` can send its client a `client_call_requested` event, which asks the client to make a
call on another server. The action waits, up to a time limit, for the client to report the outcome
with `client.reply`. `client.reply` doesn't run in order, so it never waits behind the action it
answers.

### Errors

Branch on an error's `code`, not on its `message`. The message is written for people, and its
wording can change in any release.

| `code` | `status` | Meaning |
| --- | --- | --- |
| `bad_request` | 400 | The arguments are invalid, such as a field that is missing or has the wrong type. |
| `not_found` | 404 | Something the arguments name doesn't exist, such as a block, window, or layer. |
| `method_not_found` | 404 | The server, or the block the arguments name, has no method by that name. This happens when the server is older than the client. |
| `session_not_found` | 404 | No session has the `session_id` the arguments name. The server doesn't remember destroyed sessions, so a destroyed session and an ID that never existed both get this code. |
| `conflict` | 409 | The request can't run in the current state, such as when the connection isn't attached to the session, the session is shutting down, the session has no windows, or a limit has been reached. A block that closes or moves while a request is changing the layout around it also causes this error. The request can succeed once the state changes. |
| `not_supported` | 400 | The client the request names has no such action or can't perform it now, or the server doesn't keep the data the request needs. |
| `refused` | 403 | The client the request names chose not to perform the action. The message says why. |
| `client_unavailable` | 409 | The client the request names can't take the request, such as when its connection has dropped. |
| `too_large` | 413 | The result is larger than the message limit, or the change would make stored data, such as the host list, larger than the server allows. |
| `revision_too_low` | 400 | The method needs a newer API revision than the connection uses. Use an older method, or update the client. |
| `timeout` | 504 | The request didn't finish in the time the server allows, such as an action from the configuration file that ran too long. Some of its work may already have happened. |
| `internal` | 500 | The request failed for any other reason. A failure with another status and no more specific code also uses `internal`, such as status 403 for a method that the server accepts only over its local socket. Some mistakes in a request, which this server doesn't yet tell apart, also get this code, so read the message before you blame the server. |

Later releases may add codes. Handle a code you don't know by its `status`. Servers released before
`method_not_found` existed send `not_found` for a missing method.

### Attaching to Sessions

A connection must attach to a session before it can use it. Until it does, a request whose arguments
name the session in `session_id` fails with `conflict`, or with `session_not_found` if the session
doesn't exist. Methods that don't name a session, such as `session.list` and `server.status`, work
right away. So do `client.list` and `client.perform`, because their `session_id` doesn't name a
session for the connection to act on.

Three methods attach a connection:

- `session.attach` attaches to one session and returns its current view. The connection then
  receives every event from that session.
- `session.attach_all` lets the connection use every session and starts events for the whole server,
  without attaching to any one session. Its `event_scope` chooses which events. Use it to watch the
  server, such as to keep a list of sessions up to date.
- `session.bootstrap` does what `session.attach_all` does, attaches to the sessions you name or to
  one it picks, and returns the server's status, all in one request. An app that shows sessions
  sends it as the first request on a new connection.

Only an attachment made by `session.attach` or `session.bootstrap` lets a connection open data
connections to the session's blocks, or claim and release them with `session.own_block` and
`session.disown_block`. With only `session.attach_all`, those calls fail. `session.detach` ends an
attachment.

### Events

The server sends events on the control connection without being asked, such as when a session is
created:

```json
{
  "type": "event",
  "seq": 1,
  "payload": {"type": "session_created", "payload": {"session": {"session_id": "session:<uuid>", "label": "work"}}}
}
```

An event message has these fields:

- `type` (string, required): Always event.
- `seq` (integer, required): The event's place among the events sent on this connection. The first is 1, and each one after it goes up by one.
- `payload` (any, required): The event, with its name in type and its fields in payload.

The event inside `payload` has two fields of its own: `type` is the event's name, and `payload` holds
the event's fields.

An event that happens in a session arrives inside a `session_event`, which says which session it
came from:

```json
{
  "type": "event",
  "seq": 2,
  "payload": {
    "type": "session_event",
    "payload": {
      "session_id": "session:<uuid>",
      "event": {"type": "block_created", "payload": {"block_id": "block:<uuid>"}}
    }
  }
}
```

Blocks send events of their own, such as when a terminal changes directory. Each one arrives as a
`block_event` inside a `session_event`, with the block's ID, its creator, the event's name, and the
event's fields. `block_creator.list` describes the events each creator sends.

Skip any event whose type you don't know. Newer servers add events.

Which events a connection receives depends on how it is attached:

| Attached with | Receives |
| --- | --- |
| Nothing | `keymap_changed` and `hosts_changed`, which concern the whole account, and the events that ask this client to do something, `client_call_requested` and `client_perform_requested`. |
| `session.attach` | Also every event from that session, and its `session_destroyed`. |
| `session.attach_all` with `event_scope` set to `session` | Also `session_created`, `session_destroyed`, `session_moved`, `tailscale_status_changed`, and every event from every session except `block_event`. A session the connection attached to with `session.attach` still sends its `block_event`s. |
| `session.attach_all` with `event_scope` set to `all` | Also every other event from the server and every session, including `block_event`, `client_joined`, `client_left`, `client_changed`, and `client_performed`. An event that asks a client to do something still goes only to that client. |

`session.bootstrap` counts as `session.attach_all` with the `event_scope` you send, plus
`session.attach` for each session it attaches. The event reference near the end of this page lists
every event and its fields.

### Combining Results With Events

Each event's `seq` counts the events sent on the connection, starting at 1 and going up by one with
no gaps. Each response's `seq` is the `seq` of the last event the server had queued for the
connection when it started the request, or 0 if there was none. Together they tell a client which
events a result already includes.

To combine a result, such as the view that `session.attach` returns, with events:

1. Apply the result. If events arrive before the response, hold them until it does.
2. Skip events whose `seq` is at most the response's `seq`. The result already includes them.
3. Apply the events with a higher `seq`.

An event with a higher `seq` happened after the request started, and its change may already be in
the result as well. Apply events so that seeing a change twice does no harm. For a session's view,
compare revisions. The revision goes up by one with every change to what `session.view` reports. A
`session_view_changed` whose revision is at or below the one in your view describes a change you
already have.

While a connection is open, the server never skips an event it has queued for it. If a client falls
so far behind that its queued events pass 8 MiB, the server closes the connection with status 1008
and the reason `event backlog exceeded`. Connect again and read the state again.

### Message Size

A message can be at most `limits.max_message_bytes` from the welcome, measured after decompression.
This server allows 1,048,576 bytes (1 MiB), and every server allows at least
65,536 bytes (64 KiB).

- A result that would be larger, such as the text of a very large terminal screen, fails with
  `too_large`. The connection stays open.
- A request that is larger closes the connection with status 1009.
- An event that would be larger closes the connection with status 1009 and the reason
  `event exceeds message limit`, because skipping it would leave a gap in `seq`.

### Staying Connected

The server pings the client every 120 seconds and waits up to 60 seconds for
each answer. After 3 unanswered pings in a row, it closes the connection
with status 1001 and the reason `keepalive timeout`. Browsers and most WebSocket libraries answer
pings for you. If your library can send pings, ping the server on the same schedule, so that your
client notices when the server is gone too.

### Reconnecting

If a connection drops without a WebSocket close frame, such as when a laptop sleeps or a phone loses
its network, or the client stops answering pings, the server keeps the client for
60 seconds. The client stays attached to its sessions and keeps its ID and its
script.

To continue the client, open a new connection and send the `reconnect_token` from the last welcome
in the hello. The new welcome has `resumed` set to true and carries a new token, and the old token
stops working. If the token is unknown, has expired, or was issued to someone else, the server
starts a new client instead, and `resumed` is false.

Events that happen while the client has no connection are lost, and `seq` starts again at 1 on the
new connection. Read the state again, such as with `session.view`, and combine it with events as
described above.

Responses to requests that were still running when the connection dropped are lost too, but the
server may have run those requests anyway. Check the state before you send one again, so that you
don't create a second window or type the same text twice.

A client that closes its connection with a close frame ends at once. So does a client that isn't
attached to any session, hasn't called `session.attach_all` or `session.bootstrap`, and has no
script, because the server has nothing to keep for it.

### When the Server Closes the Connection

| Status | Reason | Why |
| --- | --- | --- |
| 1001 | `keepalive timeout` | The client stopped answering pings. |
| 1002 | `protocol error` | The client broke the protocol in one of these ways: its WebSocket didn't use the `rex.control.v1` subprotocol, its first message wasn't a hello, a message wasn't JSON or had no `type` string, a request's `id` or `method` was missing or wasn't a string, such as a number for `id`, or a request reused the `id` of a request that is still waiting for its response. |
| 1003 | `protocol error` | The client sent a binary message. |
| 1007 | `protocol error` | The client sent text that isn't valid UTF-8. |
| 1008 | `no common API revision: ...` | The client and the server share no API revision. The reason says which side to update. |
| 1008 | `event backlog exceeded` | The client fell more than 8 MiB of events behind. |
| 1009 | `read limited at ...` | The client sent a message larger than the message limit. |
| 1009 | `event exceeds message limit` | An event was larger than the message limit. |

If no hello arrives within 5 seconds, the server drops the connection without a close frame. Most
WebSocket libraries report that as an abnormal closure, status 1006.

## Data Connections

A data connection carries one **stream**: the bytes that one block produces, such as everything a
terminal's program prints. A client opens one for each block whose output it shows. A stream can
outlive its connection. If the connection drops, the client can resume the stream on a new one.

Bytes flow only from the server to the client. To type into a terminal, call `client.key` or
`com.superlogical.terminal.write` on the control connection.

To open a data connection:

1. Attach the control connection to the block's session with `session.attach` or
   `session.bootstrap`. `session.attach_all` isn't enough.
2. Call `data.connect` for a ticket. For a terminal, set `data_ref_id` to `terminal` and
   `subprotocol` to `rex.data.terminal.v1`:
   ```json
   {
     "session_id": "session:<uuid>",
     "block_id": "block:<uuid>",
     "data_ref_id": "terminal",
     "subprotocol": "rex.data.terminal.v1"
   }
   ```
   The result's `ticket` is 16 bytes written as 22 base64url characters. The ticket works once,
   within 60 seconds, and only on a connection from the same user as the control
   connection, such as the same local account or the same Tailscale login.
3. Open a WebSocket at `GET /api/data` with the same subprotocol. Data connections are
   never compressed.
4. Within 5 seconds, send a HELLO as one binary message. Its 16 ticket
   bytes are the ticket from step 2, decoded from base64url without padding.
5. The server answers with a WELCOME, or with a REJECT and then closes the connection with the
   reason `data handshake rejected`. Either answer arrives as one binary message.
6. After the WELCOME, every binary message from the server is part of the stream. Each holds 1 to
   65,536 bytes (64 KiB). Join them in order. Where one message ends and the next begins
   has no meaning.

The server ignores any message the client sends after the WELCOME.

### Handshake Messages

Each handshake message is a few bytes in a fixed layout. Integers are unsigned and little-endian.

| Message | Size | Layout |
| --- | --- | --- |
| HELLO | 17 bytes | `0x01`, then the 16 bytes of the ticket. |
| RESUME | 25 bytes | `0x02`, the 16 bytes of the latest reconnect token, then the number of stream bytes received so far, as a 64-bit integer. |
| WELCOME | 32 bytes | `0x81`, a 16-byte reconnect token, 3 zero bytes, how long the server keeps the stream for a resume in milliseconds as a 32-bit integer, then a 64-bit channel nonce that is never zero. |
| REJECT | 2 bytes | `0x82`, then a reason. |

The server ignores any bytes after the fixed part of a HELLO or a RESUME, so that later versions can
extend them. A WELCOME is always exactly 32 bytes.

The terminal stream marks its own control sequences with the channel nonce, so that a client can
tell them apart from look-alikes that a program printed.

A REJECT gives one of these reasons:

| Reason | Meaning |
| --- | --- |
| `0x01` | The ticket or token is unknown, has expired, has already been used, or was issued to someone else, or its session no longer exists. |
| `0x02` | This client already has a connection to the same data source of that block, and a client can have only one at a time. If the old connection dropped, send a RESUME, which replaces it. |
| `0x03` | The stream isn't available, such as when its block has closed, or a RESUME counted fewer bytes than the server sent. |
| `0x04` | Opening the connection would pass a limit. Try again later. This server doesn't send it yet. |
| `0x05` | The handshake was malformed or didn't arrive in time, or a RESUME counted more bytes than the server sent. |
| `0x06` | The server failed. |
| `0x07` | The ticket or token was issued for a different subprotocol. |

Treat a reason you don't know as a failure. A HELLO uses up its ticket even when the server
refuses it, so get a new ticket from `data.connect` before you try again. After a refused RESUME,
start over with a new ticket and a HELLO.

### The Terminal Stream

A terminal's stream starts with a snapshot of the terminal in Ghostty's snapshot format. Then comes
everything the terminal's program writes, with Rex's own messages placed where they are safe to
apply. Each Rex message is an APC escape sequence of the form `ESC _ rex1;<nonce>;<json> ESC \`,
where `<nonce>` is the WELCOME's channel nonce in decimal. Skip one whose nonce doesn't match,
because a program printed it. The JSON's `type` says what the message is:

- `resize`: the terminal's size, in `cols` and `rows`. `cell_width_px` and `cell_height_px` give a
  cell's size in pixels, or 0 when it's unknown. `owner` is the block's owner when the size was
  set, or null. One comes right after the snapshot, and another each time the size changes. An
  owner change alone sends none.
- `theme`: the terminal's default colors. `foreground`, `background`, and `cursor` are written as
  `RRGGBB` or `#RRGGBB`, and `palette` lists changes to the 256-color palette, each written as
  `index=RRGGBB`. A color that is left out is the built-in one. `scheme` is `light` or `dark`, or
  left out when the terminal decides from `background`. Each `theme` replaces the one before it.
  One follows the first `resize` unless every color is built in, and another comes each time the
  colors change.
- `child_exited`: the terminal's program exited with `exit_code`. No more program output follows,
  but the stream stays open.

Skip a `type` you don't know.

The snapshot format is meant for terminal emulators built on Ghostty. To read a terminal's screen as
text instead, call `com.superlogical.terminal.format`.

### Resuming a Data Connection

If a data connection drops, the server keeps the stream running for 60 seconds and
holds on to its output. To resume:

1. Stop reading the old connection.
2. Open a new WebSocket at `GET /api/data` with the same subprotocol.
3. Send a RESUME with the reconnect token from the latest WELCOME and the number of stream bytes you
   have received.

The server answers with a WELCOME that carries a new reconnect token and the same channel nonce, and
continues the stream from the first byte you haven't received. A resume needs no ticket and no
control connection.

The count must be exact, or the server refuses the resume and ends the stream. Count every stream
byte that your code has used, across every connection of the stream. Don't count the WELCOME, and
don't count bytes that a WebSocket library has read ahead but your code hasn't used yet.

To start over instead of resuming, get a new ticket from `data.connect` and send a HELLO. The server
ends the waiting stream and starts a new one.

### When a Data Connection Closes

| Status | Reason | What happened |
| --- | --- | --- |
| 1000 | | The stream ended. |
| 1001 | `keepalive timeout` | The client stopped answering pings. Resume the stream. |
| 1001 | `data provider canceled` | The server stopped the stream, such as when it shuts down or the client that opened the stream leaves the server. |
| 1008 | `data provider unavailable` | The block has no data source with that `data_ref_id`, or doesn't support the subprotocol. |
| 1011 | `data provider failed` | The block failed. |
| 1013 | `data reconnect required` | Start a new stream with a new ticket. This also happens when more output piled up while the client was away than the server could keep. |
| 4000 | `block closed` | The block closed. Don't reconnect. |
| 1002, 1008, 1011, or 1013 | `data handshake rejected` | The server sent a REJECT. Its reason says why. |

When your client closes a data connection with status 1000, its stream ends. Any other close, or a
connection that drops, leaves the stream waiting for a resume. The server pings data connections on
the same schedule as the control connection.

## Calling Block Methods

Blocks provide methods of their own, named after their creator and the method, such as
`com.superlogical.terminal.write`. Call one like any other method. Its arguments always hold
`session_id` and `block_id`, which name the block, and the method's own arguments under `args`:

```json
{
  "type": "request",
  "id": "7",
  "method": "com.superlogical.terminal.write",
  "payload": {
    "session_id": "session:<uuid>",
    "block_id": "block:<uuid>",
    "args": {"data": "echo hello\n"}
  }
}
```

The result is the block method's own result, such as `{"bytes_written": 11}`. If the block has no
method by that name, the call fails with `method_not_found`.

Some block methods can also be called through actions, such as `com.superlogical.terminal.clear`.
Rex.app shows actions in its command palette, a key binding can name one, and `client.action`
performs one.

## A First Session

These calls create a session with one terminal, run a command in it, and read its screen. Make each
call after the one before it has answered, because each uses an ID from an earlier result.

1. Call `session.create` to make a session with one window that holds a shell:
   ```json
   {
     "label": "work",
     "initial_windows": [
       {"layout": {"block": {"flavor": "com.superlogical.terminal.shell"}}}
     ]
   }
   ```
   The result's `session_id` names the session, and `initial_windows[0].block_ids[0]` names the
   terminal's block.
2. Call `session.attach` with `{"session_id": "session:<uuid>"}`. The connection can now use the
   session, and it receives the session's events.
3. Call `com.superlogical.terminal.write` to type a command:
   ```json
   {"session_id": "session:<uuid>", "block_id": "block:<uuid>", "args": {"data": "ls\n"}}
   ```
4. Call `com.superlogical.terminal.format` to read the screen:
   ```json
   {"session_id": "session:<uuid>", "block_id": "block:<uuid>"}
   ```
   The result's `content` is the screen as plain text. The command's output can take a moment to
   appear, so if it isn't there yet, call it again.

The session keeps running after your client disconnects. Call `session.destroy` to end it and the
terminal in it.
