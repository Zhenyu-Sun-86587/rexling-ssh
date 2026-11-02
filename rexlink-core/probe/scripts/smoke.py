#!/usr/bin/env python3
"""Exercise the CLI on a disposable session. Uses rex-probe only, never rex."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--probe", default=str(Path(__file__).resolve().parents[2] / "target/debug/rex-probe"))
    parser.add_argument("--server", default=os.environ.get("REX_SERVER"), required="REX_SERVER" not in os.environ)
    args = parser.parse_args()
    command = [str(Path(args.probe).resolve()), "-S", args.server]

    def run(*argv, data=None, decode=True):
        result = subprocess.run(command + list(argv), input=data, capture_output=True, check=True, timeout=15)
        return json.loads(result.stdout) if decode else result.stdout

    def until(predicate):
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            if predicate():
                return
            time.sleep(0.05)
        raise AssertionError("timed out waiting for CLI output")

    session = None
    children = []
    try:
        label = "rex-probe-cli-" + uuid.uuid4().hex[:8]
        script = 'stty -echo; printf "CLI_READY\\n"; while IFS= read -r line; do printf "CLI_REPLY:%s\\n" "$line"; done'
        created = run("call", "session.create", json.dumps({"label": label, "initial_windows": [{"layout": {"block": {"flavor": "com.superlogical.terminal.shell", "options": {"shell": "none", "command": ["/bin/sh", "-c", script]}}}}]}))
        session = created["session_id"]
        block = created["initial_windows"][0]["block_ids"][0]
        assert any(s["session_id"] == session for s in run("sessions")["sessions"])
        assert run("inspect", session)["attached"]["view"]["windows"]
        until(lambda: b"CLI_READY" in run("snapshot", session, block, decode=False))
        with tempfile.TemporaryDirectory(prefix="rex-probe-smoke-") as temporary:
            root = Path(temporary)
            raw = root / "watch.stream"
            watch = subprocess.Popen(command + ["watch", session, block, "--seconds", "2", "--resume", "--output", str(raw)], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            children.append(watch)
            until(lambda: raw.exists() and raw.stat().st_size > 0)
            run("write", session, block, "--stdin", data=b"watch-live\n")
            stdout, stderr = watch.communicate(timeout=10)
            assert watch.returncode == 0, stderr.decode()
            assert b'"resumed": true' in stdout
            assert raw.read_bytes().startswith(b"GHOSTSNP")
            assert b"CLI_REPLY:watch-live" in raw.read_bytes()
            resized = run("resize", session, block, "101", "29", "--take")
            assert (resized["columns"], resized["rows"]) == (101, 29)

            raw = root / "attach.stream"
            with raw.open("wb") as output:
                attach = subprocess.Popen(command + ["attach", session, block, "--output", "-"], stdin=subprocess.PIPE, stdout=output, stderr=subprocess.PIPE)
                children.append(attach)
                until(lambda: raw.stat().st_size > 0)
                # Deliberately split a UTF-8 code point across stdin writes.
                text = "cli-λ-🦀\n".encode()
                index = text.index("🦀".encode()) + 1
                attach.stdin.write(text[:index])
                attach.stdin.flush()
                time.sleep(0.05)
                attach.stdin.write(text[index:])
                attach.stdin.flush()
                expected = "CLI_REPLY:cli-λ-🦀".encode()
                until(lambda: expected in raw.read_bytes())
                attach.stdin.write(b"\x1d")
                attach.stdin.flush()
                _, stderr = attach.communicate(timeout=10)
                assert attach.returncode == 0, stderr.decode()
            assert raw.read_bytes().startswith(b"GHOSTSNP")
            snapshot = root / "snapshot.txt"
            run("snapshot", session, block, "--output", str(snapshot), decode=False)
            assert expected in snapshot.read_bytes()
            for format_name in ("html", "vt"):
                assert run("snapshot", session, block, "--format", format_name, decode=False)
            raw = root / "closed-block.stream"
            attach = subprocess.Popen(command + ["attach", session, block, "--output", str(raw)], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            children.append(attach)
            until(lambda: raw.exists() and raw.stat().st_size > 0)
            run("call", "block.close", json.dumps({"block_id": block}), "--session", session)
            # Keep stdin open and idle: remote closure must still terminate attach.
            attach.wait(timeout=5)
            _, stderr = attach.communicate(timeout=5)
            assert attach.returncode != 0 and b"block closed" in stderr, stderr.decode()
        print(json.dumps({"passed": True, "checks": ["session list and inspect", "watch and data resume", "stdin write", "resize", "attach raw stdout with split UTF-8 input", "text/html/vt snapshots", "snapshot file", "remote block closure exits with idle stdin"], "session_id": session, "cleanup": "destroyed in finally"}, indent=2))
    finally:
        for child in children:
            if child.poll() is None:
                child.kill()
                child.wait()
        if session:
            run("call", "session.destroy", "--session", session)


if __name__ == "__main__":
    main()
