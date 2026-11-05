#!/usr/bin/env python3
"""Exercise rex-view through a real local PTY and an isolated Rex session."""
import argparse
import fcntl
import json
import os
from pathlib import Path
import pty
import re
import select
import struct
import subprocess
import termios
import time
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server", default=os.environ.get("REX_SERVER"), required="REX_SERVER" not in os.environ)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    probe = [str(root.parent / "target/debug/rex-probe"), "-S", args.server]
    viewer = [str(root.parent / "target/debug/rex-view"), "-S", args.server]

    def call(method, payload, session=None):
        command = probe + ["call", method, json.dumps(payload)]
        if session:
            command += ["--session", session]
        result = subprocess.run(command, check=True, capture_output=True, timeout=15)
        return json.loads(result.stdout)

    def receive_until(master, captured, needle, process):
        deadline = time.monotonic() + 12
        while time.monotonic() < deadline:
            # The diagnostic renderer positions and styles every cell separately.
            text = re.sub(r"\x1b\[[0-9;?]*[ -/]*[@-~]", "", captured.decode(errors="replace"))
            if needle in text:
                return captured
            assert process.poll() is None, f"viewer exited early ({process.returncode})"
            readable, _, _ = select.select([master], [], [], 0.1)
            if readable:
                captured += os.read(master, 131072)
        raise AssertionError(f"timeout waiting for rendered {needle!r}")

    session = None
    children = []
    terminals = []
    try:
        script = 'stty -echo; printf "VIEW_READY λ🦀\\n"; while IFS= read -r line; do printf "INPUT:%s\\n" "$line"; done'
        result = call("session.create", {"label": "rex-view-smoke-" + uuid.uuid4().hex[:8], "initial_windows": [{"layout": {"block": {"flavor": "com.superlogical.terminal.shell", "options": {"shell": "none", "command": ["/bin/sh", "-c", script]}}}}]})
        session = result["session_id"]
        block = result["initial_windows"][0]["block_ids"][0]
        master, slave = pty.openpty()
        terminals += [master, slave]
        fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack("HHHH", 25, 84, 0, 0))
        process = subprocess.Popen(viewer + ["view", session, block, "--take"], stdin=slave, stdout=slave, stderr=subprocess.PIPE)
        children.append(process)
        captured = receive_until(master, b"", "VIEW_READY λ🦀", process)
        text = "hello-λ🦀\n".encode()
        split = text.index("🦀".encode()) + 1
        os.write(master, text[:split])
        time.sleep(0.05)
        os.write(master, text[split:])
        captured = receive_until(master, captured, "INPUT:hello-λ🦀", process)
        fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack("HHHH", 27, 91, 0, 0))
        deadline = time.monotonic() + 10
        while True:
            size = call("com.superlogical.terminal.size", {"block_id": block}, session)
            if (size["columns"], size["rows"]) == (91, 27):
                break
            assert time.monotonic() < deadline, size
            # Drain the PTY so the diagnostic renderer cannot block on its output.
            if select.select([master], [], [], 0.05)[0]:
                captured += os.read(master, 131072)
        os.write(master, b"\x1d")
        deadline = time.monotonic() + 10
        while process.poll() is None:
            assert time.monotonic() < deadline, "Ctrl-] did not detach"
            if select.select([master], [], [], 0.05)[0]:
                captured += os.read(master, 131072)
        assert process.returncode == 0, process.stderr.read().decode()
        while select.select([master], [], [], 0.05)[0]:
            captured += os.read(master, 131072)
        # The guard restores local terminal flags and the screen after detaching.
        assert termios.tcgetattr(slave)[3] & termios.ICANON
        assert b"\x1b[?1049l" in captured
        print(json.dumps({"passed": True, "session_id": session, "checks": ["native viewport rendered through a PTY", "Unicode snapshot visible", "split UTF-8 keyboard input reaches child", "local PTY resize reaches Rex", "Ctrl-] detaches", "local terminal modes restored"], "cleanup": "destroyed in finally"}, indent=2))
    finally:
        for child in children:
            if child.poll() is None:
                child.kill()
                child.wait()
        for fd in terminals:
            os.close(fd)
        if session:
            call("session.destroy", {}, session)


if __name__ == "__main__":
    main()
