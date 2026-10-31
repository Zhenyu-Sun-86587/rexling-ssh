# rex-probe

An independent Rust client for the Superlogical Rex server. It connects directly
over WebSockets on a Unix socket or over HTTPS/Tailscale. It never starts or calls
the `rex` executable and does not use a bridge or proxy.

Verified against API revisions **7 and 8** on Linux and macOS Rex servers on
2026-10-31. Findings, framing, authentication, evidence, and limitations are in
[PROTOCOL.md](PROTOCOL.md).

```sh
cargo build --locked
./target/debug/rex-probe -S https://rex.taildaac1.ts.net connect
./target/debug/rex-probe -S https://rex.taildaac1.ts.net sessions

# In a Rex terminal, --server defaults to the existing REX_SERVER environment.
./target/debug/rex-probe discover > discovery.json
./target/debug/rex-probe inspect 'session:...'
./target/debug/rex-probe snapshot 'session:...' 'block:...'
./target/debug/rex-probe watch 'session:...' 'block:...' --seconds 10 --output terminal.stream
```

`attach` forwards UTF-8 stdin and captures the binary Ghostty snapshot plus live
VT stream. A Ghostty decoder/renderer is not integrated. Use Ctrl-] to detach:

```sh
./target/debug/rex-probe attach 'session:...' 'block:...' --output terminal.stream
# Or pipe raw stdout into a file or a future decoder:
./target/debug/rex-probe attach 'session:...' 'block:...' --output - > terminal.stream
```

Tests that use a real Rex server create a disposable session and remove only it:

```sh
cargo test --locked
cargo clippy --locked --all-targets -- -D warnings
./target/debug/rex-probe -S https://rex.taildaac1.ts.net self-test
python3 scripts/smoke.py --server https://rex.taildaac1.ts.net
```

The smoke script tests the CLI; the Rust self-test also tests control reconnection
and recovery of state changed during a disconnect. Both require `/bin/sh` and
`stty` on the server. No server settings are changed.
