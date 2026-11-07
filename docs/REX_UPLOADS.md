# Native Rex terminal uploads

Investigated 2026-11-07 (Asia/Kuching). The protocol was investigated first, then implemented in build 79. Build 80
removes the former Portside integration; native Rex is now the only upload route.

## Sources and availability

The running Mac server advertises API revision 11. Its bundled server binary is
`0.0.1793981048-g01d6c4`, commit
`01d6c4fe68668db3521570d7fbc9b6b112b1e572`. Both the Mac and configured Ubuntu
host expose `com.superlogical.terminal.upload_begin` through CLI API discovery.
The Mac app's bundled API revision notes identify uploads as revision 11.
The public API reference did not yet contain upload documentation during this
investigation; use the server's own help rather than inventing an HTTP endpoint.

References:

- `rex api describe com.superlogical.terminal.upload_begin --json`
- `rex api list --json` / `help.all`: block methods and capability discovery
- Running server `/llms.txt`: common control and data handshake
- Bundled Rex Beta API revision 11 notes: upload batching/completion/errors
- Captured schema: `rexling-android/validation/rex-upload-schema-r11.json`
- Public general reference: https://www.superlogical.com/rex/docs/reference/api

## Control request

Attach the upload's control connection to the exact target session. Call:

```json
{
  "method": "com.superlogical.terminal.upload_begin",
  "payload": {
    "session_id": "session:<uuid>",
    "block_id": "block:<uuid>",
    "args": {
      "ref": "upload:<fresh lowercase canonical UUID>",
      "name": "image.png",
      "size": 12345
    }
  }
}
```

The result, in the response's `payload`, contains `ticket` and `expires_in_ms`.
Do not log tickets. The server uses only the final path component of `name` and
removes control characters. `size` is the exact byte count, not an estimate.

Optional `batch`, `index` (zero based), and `count` group files. Leave all three
out for a single file. The server pastes batch paths in index order.

## Byte stream

The following single-file wire sequence was verified against the running Mac
server in isolated temporary sessions:

1. Open a separate, uncompressed WebSocket at `/api/data`, with subprotocol
   **`rex.data.bytes.v1`**, using the same authenticated principal as control.
2. Send binary HELLO: `0x01` followed by the 16 ticket bytes decoded from base64url
   without padding. The observed WELCOME was 32 bytes, starting with `0x81`, as
   for the common data handshake. Never log its reconnect token.
3. Send raw file bytes as binary messages. Do not prefix file chunks with a tag
   or length. The server already knows the exact size from `upload_begin`.
4. Send an **empty binary message** to half-close the upload direction. This is
   different from closing the WebSocket. Wait for the server's final close.
5. The observed successful server close was WebSocket `1000` (normal/ok).

Use bounded chunks and streaming file IO. The maximum accepted upload message
size and upload resumption behavior have not been established; do not assume
terminal stream resumption applies to upload streams. The probe used five bytes,
not a throughput or large-file test.

## Completion, cancellation and failures

The server inserts the uploaded paths itself. Rexling must not paste a path
again or send Enter. The server manages upload storage and eviction; the user's
changelog describes eviction of the terminal's oldest uploads.

Bundled revision notes say the last file of a batch gets `ok` only after all
paths were inserted. Require every stream's successful completion for a batch.
Do not interpret sent-byte progress alone as server confirmation.

A partial two-byte transfer declared as five bytes was canceled by closing its
WebSocket normally without half-closing. No path was inserted and no partial
file remained. A normal close can therefore also acknowledge cancellation:
classify success only after this client sent the exact bytes and half-close,
then received successful server completion. A dropped connection after finishing
can leave an uncertain outcome; never retry automatically and risk duplicate
path insertion. Cancellation races after completion need a defined UI outcome.

Observed malformed/oversized byte payloads closed with `4006`, reason
`data stream broke a rule`. Treat unknown/non-success closes as failures.
The revision notes list control errors: `bad_request` for invalid ref/name/batch,
`too_large` for file/batch limits, `conflict` for too many reservations, and
`method_not_found` on an older server. Exact quotas were not determined.

## Verification performed

- Both configured servers advertise the method (schema discovery only on Ubuntu).
- Mac: exact `hello` file content read back from the uploaded file.
- Mac: path appeared automatically in the disposable terminal after half-close.
- Mac: partial cancellation produced no pasted path or retained partial file.
- All temporary sessions were removed; existing user panes/configuration untouched.
- Batch ordering, zero-byte files, large images, network failure races, remote
  transport transfer, quotas and permission/ownership behavior still need tests.

## Rexling implementation

1. A dedicated upload connection negotiates revisions 7–11, leaving the live
   terminal connection on its existing revisions 7–8. An older negotiated revision
   returns unsupported before reserving a file.
2. Servers without revision 11 / `upload_begin` produce an upgrade message.
   There is no secondary upload backend or upload-server setting.
3. Reuse clipboard/photo-picker staging and the existing target identity checks.
   Add a Rust upload task with separate data WebSocket, progress and cancellation;
   keep terminal output and input responsive while uploading.
4. Native completion requires no client-side path insertion.
   Uploads belong to the originally chosen server/session/pane, even if navigation
   changes; never insert into whichever pane happens to be current later.
5. Show Sending / Finishing, briefly show green on confirmed success then clear automatically, and distinguish canceled, failed, and
   uncertain outcomes. Never automatically replay an uncertain upload.
6. Verify against an isolated server before building/installing a release. Keep
   the server's API error authoritative for size/capacity restrictions.


## Native implementation and additional checks

`rexling-core/src/upload.rs` streams 16 KiB chunks on a separate control/data
connection. JNI owns a cancellable upload worker; Kotlin polls metadata/progress
without blocking the UI or terminal actor. The existing image picker, clipboard
and IME image entry points use this route. Unsupported revision/method results
ask for a Rex server update; there is no upload backend fallback. Quota, network
and transfer failures never change destination.
Uncertain completion disables Retry; no native path is pasted from Kotlin.

The implementation uploaded the 1,813,958-byte PNG fixture to both MBP and Ubuntu,
verified server-side SHA-256 after automatic path insertion in temporary panes,
and removed both test sessions. Evidence is in
`rexling-android/validation/native-uploads-2026-11-07.json`.


## Progress refinement (build 86)

Connection setup remains Preparing. Upload progress uses pipelined WebSocket
Ping/Pong receipt checkpoints at 64 KiB intervals (4 MiB in-flight bound), with
40ms Kotlin polling. An indeterminate ring is used before the first checkpoint.
Finishing is entered after the final transport checkpoint; successful server
close still confirms insertion separately. See PROTOCOL.md for framing and
validation/upload-progress-live-2026-11-07.json for both-host verification.
