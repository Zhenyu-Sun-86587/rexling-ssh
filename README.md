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

## Native Ghostty terminal proof

The optional `ghostty` feature restores Rex's binary snapshot into native
`libghostty-vt`, applies live VT and nonce-matched Rex APCs, and exposes styled
cells, cursor position, and screen text for a future Android renderer.

```sh
# Downloads checksum-verified source/toolchain into target/, then builds the library.
python3 scripts/build_ghostty.py
cargo build --locked --features ghostty --bins
cargo test --locked --features ghostty

# Disposable real-server test, including alternate screens, theme, and resume:
./target/debug/rex-view -S https://rex.taildaac1.ts.net self-test
python3 scripts/smoke_view.py --server https://rex.taildaac1.ts.net

# Interactive diagnostic renderer; Ctrl-] detaches.
# Copy session/block IDs from rex-probe sessions/inspect.
./target/debug/rex-view -S https://rex.taildaac1.ts.net view 'session:...' 'block:...'
# Add --take to claim the selected block and follow the local terminal's size.
```

The build helper was verified on this Apple Silicon Mac with Zig 0.17.0 and
Ghostty revision `f54340ad77814fe319d136e5e915d6a48de8dd23`. Downloads, source,
and libraries stay under ignored `target/`. An existing installation can be
selected with `GHOSTTY_PREFIX=/absolute/install/prefix`.

Real test streams are archived for offline regression tests. Replay one with:

```sh
./target/debug/rex-view decode evidence/ghostty-remote.bin \
  --nonce 11418691820941138289
```

This is a native terminal-core proof with an ANSI diagnostic renderer. JNI,
Android compilation, Compose rendering, mobile input, and lifecycle recovery
remain the next phase. The default `rex-probe` build still needs no Ghostty.
