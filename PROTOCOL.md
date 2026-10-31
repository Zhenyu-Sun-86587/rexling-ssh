# Rex protocol investigation

Verified on **2026-10-31**, using the user's installed Rex and existing saved
Tailscale host. The Rust proof of concept connects directly to both servers,
lists sessions, inspects windows/blocks, captures snapshots and live terminal
bytes, writes input, resizes the PTY, and resumes both control and data connections.
No Android UI, JNI layer, or Ghostty dependency was added. No server configuration
or authentication settings were changed.

## Tested installations and evidence

| Target | Rex version | Platform | Negotiated API | Authentication observed |
| --- | --- | --- | --- | --- |
| Existing local socket from `REX_SERVER` | `0.0.1793368937-g8697a6` | `darwin_arm64` | 8 | Unix peer UID 501 |
| Saved host `shulhi-x13`, `https://rex.taildaac1.ts.net` | `0.0.1793316310-g0b1328` | `linux_amd64` | 7 | `kind=tailscale`, `transport=tailscale`, node `shulhi-mbp` |

Local socket: `unix:///Users/shulhi/Library/Application%20Support/rex/server.sock`.
Its built-in Tailscale state was `disabled`; the remote host's was `running`.
The local server command line had no TCP listener. The remote host already had
working HTTPS over Tailscale. No listener was created for this investigation.

The following files preserve version-specific evidence:

- [Local self-test](evidence/local-self-test.json) and [remote self-test](evidence/remote-self-test.json).
- [Local CLI smoke test](evidence/local-cli-smoke.json) and [remote CLI smoke test](evidence/remote-cli-smoke.json).
- [Local packet trace](evidence/local-self-test.jsonl) and [remote packet trace](evidence/remote-self-test.jsonl).
- Server-generated wire guides: [revision 8](evidence/server-wire-guide-r8.md), [revision 7](evidence/server-wire-guide-r7.md).
- Selected exact schemas: [revision 8](evidence/selected-schemas-r8.json), [revision 7](evidence/selected-schemas-r7.json).

The traces contain only traffic from disposable test sessions. Ticket and reconnect
token values are redacted. Stream frames are represented by lengths/offsets, not
their content. IDs and nonces in these evidence files are historical examples.

## Discovery and sources

Started with `rex __internal skill`, `rex --help`, `rex version`, `rex whoami
--json`, `rex server status --json`, `rex hosts list --json`, `rex api list
--json`, and `rex block types --json`. `rex --version` and `rex hosts ls` were
not valid on this installation; use `rex version` and `rex hosts list`.

The public [Server API documentation](https://www.superlogical.com/rex/docs/automate/server-api)
points to self-describing schemas. The installed server's **`/llms.txt` documents
the wire protocol itself**, including data streaming and reconnection. Binary
inspection was unnecessary once that endpoint was found. Public documentation
can lag a particular installation; the server's guide and live tests take priority.

These endpoints were independently fetched on both existing servers:

| Endpoint | Protocol/content |
| --- | --- |
| `GET /api/control` | WebSocket, `rex.control.v1` |
| `GET /api/data` | WebSocket, `rex.data.terminal.v1` for terminal data |
| `GET /api/help` | JSON method/block schemas, plus advertised endpoint paths |
| `GET /llms.txt` | Server-generated Markdown wire guide and API reference |
| `GET /llms.html` | HTML guide; advertised, not separately tested |

Reproduce read-only discovery with the official CLI and HTTP client:

```sh
rex version
rex whoami --json
rex hosts list --json
rex api list --json
rex api describe data.connect --json
rex api describe com.superlogical.terminal.format --json
curl --unix-socket '/Users/shulhi/Library/Application Support/rex/server.sock' \
  http://localhost/llms.txt
curl https://rex.taildaac1.ts.net/api/help
curl https://rex.taildaac1.ts.net/llms.txt
```

These are investigation commands only. The Rust client contains no subprocess
calls and does not use `rex` at runtime. `discover` obtains schemas through
`help.all` and `block_creator.list` on its own control connection.

## Transport and authentication

**Observed:** an ordinary TLS-verified WebSocket connection from this Mac through
its existing Tailscale network successfully authenticated to the remote server.
The Rust client sends no Authorization header, identity header, cookie, password,
or Tailscale key. `server.whoami` reports the authenticated Tailscale principal.
Both control and data WebSockets work this way. Local connections instead use
Unix peer credentials; percent-encoded socket paths must be decoded before dialing.

**Documented:** Rex currently derives identity from its listener/transport.
Its [Remote Access documentation](https://www.superlogical.com/rex/docs/linux/remote-access)
describes built-in Tailscale, Tailscale Serve, and tailnet-address listeners.
The default personal-server policy admits the Tailscale owner; ACLs further
restrict network access. A Serve integration requires the special
`tailnet-serve://` listener. Identity headers from a client are not an authentication
strategy. This probe neither supplies nor spoofs them.

The server guide documents HTTP 403 before upgrade for a denied caller. Denial
from an unrelated identity/off-tailnet device was not tested. Login-server account
selection, tagged-device behavior, and Serve-specific identity forwarding were
not tested. URL userinfo is rejected by the probe rather than silently ignored.

**Android implication (inference):** the existing remote path supports an independent
native client using the device's authenticated Tailscale VPN plus normal TLS and
WebSocket libraries. No application-level Tailscale identity header is needed for
the tested built-in Tailscale listener. This has not yet been tested on the S22+.
Reaching the Mac's Unix-only server from Android still needs an authorized remote
listener setup; this investigation did not change it.

## Control protocol: verified shapes

Request `Sec-WebSocket-Protocol: rex.control.v1`. An initial trial without this
subprotocol upgraded, then closed with WebSocket 1002, `protocol error`.
The implemented client requires the server to echo the selected subprotocol.

Each application message is one JSON object in one WebSocket **text** message.
The probe does not negotiate compression. The server guide allows optional
`permessage-deflate` without context takeover; HELLO must stay uncompressed and
under 512 bytes because it can contain a secret.

Send HELLO within five seconds:

```json
{"type":"hello","api_revision":8,"api_min_revision":7,"client":{"kind":"cli","name":"rex-probe","platform":"macos","version":"0.1.0"}}
```

The range 7–8 is intentional: these are the revisions actually investigated.
The local server selected 8; the remote server selected 7. WELCOME is first:

```json
{"type":"welcome","protocol":"rex.control.v1","api_revision":8,"api_server_revision":8,"limits":{"max_message_bytes":1048576},"client":{"id":"client:<uuid>","reconnect_token":"<secret>"}}
```

Correlate requests/responses using a unique nonempty **string** ID:

```json
{"type":"request","id":"1","method":"session.list","payload":{}}
{"type":"response","id":"1","seq":0,"payload":{"sessions":[{"session_id":"session:<uuid>","label":"work"}]}}
```

An error replaces `payload` with
`{"error":{"code":"not_found","message":"...","status":404}}`.
The probe surfaces code, status, and message. Server methods can execute
concurrently, so dependent calls must await replies. The probe serializes calls;
it does not automatically retry writes or other mutations after uncertain failure.

Attach before operating on a session:

```json
{"type":"request","id":"2","method":"session.attach","payload":{"session_id":"session:<uuid>"}}
```

The result has `client_id` and `view`. A view contains `revision`, `session_id`,
`active_window_id`, `windows`, layers/layout nodes, focused blocks, and block
descriptors including data references. Use `session.list_blocks` to include
detached blocks and `session.describe_block` for a block's methods/events/data.
`session.attach_all` alone does not authorize data tickets or ownership claims.

Events interleave with responses:

```json
{"type":"event","seq":1,"payload":{"type":"session_event","payload":{"session_id":"session:<uuid>","event":{"type":"session_view_changed","payload":{"revision":42}}}}}
```

The probe validates consecutive event sequence numbers and queues events received
while awaiting a response. It ignores unknown message types/fields. It does not
maintain an Android-style live layout model. Per the server guide, a full client
must apply a response's state, skip queued events with `seq <= response.seq`, then
apply later events idempotently, comparing session view revisions. After reconnect,
reload state because events during the outage are not replayed.

## Snapshots, input, and resize

All terminal methods use this verified wrapper:

```json
{"type":"request","id":"3","method":"com.superlogical.terminal.format","payload":{"session_id":"session:<uuid>","block_id":"block:<uuid>","args":{"format":"text","trim":true,"max_bytes":65536}}}
```

`format` returns `content` representing current screen **and scrollback**, not
incremental PTY output. `text`, `html`, and `vt` were tested. `vt` is a formatted
redraw, distinct from the binary snapshot that starts a data stream.

If `format` returns an `id`, call `com.superlogical.terminal.blob_read` with
`{"id":123,"offset":<UTF-8 bytes already received>,"max_bytes":65536}`.
Its reply uses **`data`, not `content`**, plus `id`, `total`, and `eof`.
Never count Unicode characters as byte offsets. The self-test forces 32-byte
pagination and compares the result with a normal snapshot.

Terminal input:

```json
{"type":"request","id":"4","method":"com.superlogical.terminal.write","payload":{"session_id":"session:<uuid>","block_id":"block:<uuid>","args":{"data":"hello\n"}}}
```

The reply's `bytes_written` acknowledges UTF-8 bytes queued, not consumption by
the child or resulting output. No newline is automatically added. The probe
waits for controlled output to prove execution. `attach` preserves UTF-8 across
stdin read boundaries and reserves Ctrl-] for local detach. Arbitrary non-UTF-8
input is not supported by this method/probe. Structured key/paste/mouse events
also exist through `client.key`/`block.event`; only the raw text write path was
exercised end to end.

Resize:

```json
{"type":"request","id":"5","method":"com.superlogical.terminal.resize","payload":{"session_id":"session:<uuid>","block_id":"block:<uuid>","args":{"columns":93,"rows":31}}}
```

`size` reports actual dimensions, current owner, and requested dimensions by
client. A non-owner's request may be deferred. `session.own_block` with
`{"session_id":"session:<uuid>","block_ids":["block:<uuid>"],"mode":"take"}`
claims ownership. Ownership controls size/theme selection, not access rights.
The self-test claims only its isolated block and verifies both `size` and child
`stty size` output (`31 93`). `resize --take` is explicit; a short-lived probe
does not retain ownership after a normal control disconnect. Use a long-lived
client for persistent size arbitration. Automatic local-window resize forwarding
is not implemented.

## Binary data connection

On the attached control connection, call:

```json
{"type":"request","id":"6","method":"data.connect","payload":{"session_id":"session:<uuid>","block_id":"block:<uuid>","data_ref_id":"terminal","subprotocol":"rex.data.terminal.v1"}}
```

The result is `{"ticket":"<22 base64url characters>","expires_in_ms":60000}`.
Decode without padding into 16 bytes. Open `/api/data` on the same server with
`Sec-WebSocket-Protocol: rex.data.terminal.v1`. Do not send JSON or use the
ticket as a URL parameter. This WebSocket is uncompressed.

All handshake fields are unsigned, **little-endian**:

| Message | Bytes | Layout |
| --- | --- | --- |
| HELLO | 17 | `01` + 16 decoded ticket bytes |
| RESUME | 25 | `02` + 16 reconnect-token bytes + u64 consumed stream byte count |
| WELCOME | 32 | `81` + 16 new reconnect-token bytes + 3 zero bytes + u32 resume retention milliseconds + u64 nonzero channel nonce |
| REJECT | 2 | `82` + reason byte |

The local and remote WELCOMEs both reported **60000 ms**. Tokens remain in memory
and are redacted in logs. The server guide says tickets are single-use, expire in
60 seconds, and are bound to the principal and requested subprotocol. Cross-user
ticket rejection and expiration were documented but not experimentally tested.

After WELCOME, join binary messages in order. Documented chunk size is 1–65536
bytes; frame/message boundaries are not terminal record boundaries. The stream
is server-to-client only. Input goes through control methods.

**Observed stream contents:** the initial bytes were `47 48 4f 53 54 53 4e 50`
(`GHOSTSNP`), followed by Ghostty snapshot data. An initial nonce-marked resize
message follows the snapshot. Later chunks contained actual child output such
as `PROBE_REPLY:live-one\r\n`, proving incremental output rather than snapshot
polling. The probe verifies the signature and APC marker but does not parse the
snapshot's internal layout or determine its boundary for rendering.

Rex inserts APCs with this documented syntax:

```text
ESC _ rex1;<decimal channel nonce>;<JSON> ESC \
```

Known types are `resize` (`cols`, `rows`, cell size, owner), `theme` (colors), and
`child_exited` (`exit_code`). Match the current connection's u64 nonce before
treating an APC as Rex control data. These values can exceed JavaScript's exact
integer range; keep them as u64 or decimal strings. A future decoder must handle
records split across WebSocket messages and unknown message types. This probe
captures exact bytes and logs metadata; it does not interpret/apply APCs.

Documented REJECT reasons: `01` unknown/expired/used/wrong-principal token,
`02` duplicate source, `03` stream unavailable or offset too old, `04` limit,
`05` malformed handshake or offset ahead, `06` server failure, `07` subprotocol
mismatch. The probe reports the byte and stops. A full client should obtain a
fresh ticket and snapshot after a rejected resume; that fallback is not automated.

## Reconnection and state synchronization

**Tested control recovery:** drop the socket without a close frame; use the latest
WELCOME `reconnect_token` in a new HELLO. Both servers retained the same client ID,
returned `resumed=true`, and rotated the token. During the gap a separate direct
probe connection changed the disposable window label and generated terminal
output. Fetching a fresh `session.view` and snapshot recovered those changes.
No credentials are persisted to disk.

**Tested data recovery:** record exactly the bytes delivered to the application,
drop the data socket, generate output during the gap, reconnect with RESUME.
Both servers returned the same nonce and continued at the saved offset, delivering
the missing output without another initial snapshot. The count excludes WELCOME
and includes the initial snapshot, VT bytes, and Rex APCs. Do not include library
read-ahead bytes not yet used by the application.

**Documented lifecycle:** control retention is 60 seconds after an abnormal
disconnect; a normal close ends the client immediately. Event sequences restart
at 1 after reconnection. In-flight requests can have run even if their replies
were lost. Data streams also retain for 60 seconds and rotate tokens on resume;
normal data close 1000 ends the stream. Overflow can require a new ticket and
snapshot (1013). A closed block (4000) must not be reattached. These expiry,
overflow, long keepalive, server-restart, and ownership-contention cases were not
stress-tested. The probe responds to WebSocket pings and its watch/attach loops
send pings every 120 seconds; it has no complete mobile network recovery loop.

## Reproducing the independent proof

Built and tested with Rust **1.93.0** on the Mac. Cargo.lock records exact
dependencies; `tokio-tungstenite` was added only after WebSocket transport was
verified. `rustls` verifies certificates with WebPKI roots; there is no insecure
TLS option, identity injection, or custom proxy.

```sh
cargo build --locked
cargo test --locked
cargo clippy --locked --all-targets -- -D warnings

# Independent local discovery; defaults to REX_SERVER when inside Rex:
./target/debug/rex-probe connect
./target/debug/rex-probe discover > local-discovery.json
./target/debug/rex-probe sessions

# Direct remote connection (explicit URL, not a rex host label):
./target/debug/rex-probe -S https://rex.taildaac1.ts.net connect
./target/debug/rex-probe -S https://rex.taildaac1.ts.net sessions
./target/debug/rex-probe -S https://rex.taildaac1.ts.net self-test
python3 scripts/smoke.py --server https://rex.taildaac1.ts.net

# Read a chosen terminal; copy actual IDs from discovery:
./target/debug/rex-probe inspect 'session:...'
./target/debug/rex-probe snapshot 'session:...' 'block:...' --format text
./target/debug/rex-probe watch 'session:...' 'block:...' --seconds 10 \
  --resume --output terminal.stream

# These send input/change size in the selected block:
printf 'hello\n' | ./target/debug/rex-probe write 'session:...' 'block:...' --stdin
./target/debug/rex-probe resize 'session:...' 'block:...' 93 31 --take
./target/debug/rex-probe attach 'session:...' 'block:...' --output - > attach.stream

# Trace files must be new paths. Ticket/reconnect values are always redacted:
./target/debug/rex-probe --trace /tmp/new-rex-probe.trace.jsonl self-test
```

Only run input/resize commands on the intended block. `self-test` and the smoke
script create their own session, use a controlled `/bin/sh` child, and destroy
only that session in cleanup. Forced termination or connection failure can still
prevent cleanup; the self-test prints its disposable session ID to stderr.
Unit/integration tests include a localhost-only mock peer for interleaved
events/pings, errors, and sequence gaps; restricted sandboxes must permit those
local sockets. Six automated Rust tests passed, plus both real-server self-tests
and both CLI smoke tests.

## Implementation boundaries and remaining work

- `src/transport.rs`: direct Unix/TLS WebSockets and subprotocol negotiation.
- `src/control.rs`: HELLO/WELCOME, serial request correlation, event queue/sequence
  checks, trace redaction. It is a probe library, not a fully concurrent client SDK.
- `src/data.rs`: binary handshake, byte counting, data resume. It has no VT parser.
- `src/main.rs`: CLI, snapshots/input/resize, raw stdin/capture, isolated self-test.
- `scripts/smoke.py`: CLI verification only; it invokes this binary, never `rex`.

`attach --output -` supports raw stdout when piped. It refuses to dump the binary
snapshot directly to a terminal screen. Captures use new files with mode 0600 on
Unix. Traces redact protocol secrets, but can contain terminal input, snapshots,
paths, and identity information; only the isolated test traces are archived here.

The immediate direct-connect protocol proof has no known blocker on the tested
remote host. The next substantive task is Ghostty snapshot import and subsequent
VT/APC handling using a compatible `libghostty-vt` API, followed by Android build,
JNI/UniFFI, rendering, input, and VPN/lifecycle testing. Snapshot ABI/version
compatibility, Android support, production backpressure/event reconciliation,
automatic reconnect/fresh-snapshot fallback, and secure credential storage remain
unverified. This binary does not claim to be a working terminal renderer.
