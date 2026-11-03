#!/usr/bin/env python3
"""Run Android's image flow against an owned, loopback-only Portside Rust fixture.

The fake Tailscale CLI supplies test identities only to this temporary server.
No running Portside service, Rex session or saved configuration is changed.
"""
import argparse
import base64
import json
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
PNG = base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aP2sAAAAASUVORK5CYII=")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server-binary", type=Path, default=ROOT.parent.parent / "portside/server/target/release/portside-server")
    parser.add_argument("--serial", default="emulator-5554")
    args = parser.parse_args()
    assert args.serial.startswith("emulator-"), "Use a disposable emulator, never your phone"
    binary = args.server_binary.resolve()
    assert binary.is_file(), "Build the Portside Rust server first; see BUILDING.md"
    sdk = Path.home() / "Library/Android/sdk"
    adb = str(sdk / "platform-tools/adb") if (sdk / "platform-tools/adb").exists() else shutil.which("adb")
    assert adb, "ADB is required"

    def device(*command, **kwargs):
        return subprocess.run([adb, "-s", args.serial, *command], check=True, **kwargs)

    with tempfile.TemporaryDirectory(prefix="rexlink-portside-") as temporary:
        folder = Path(temporary).resolve()
        uploads = folder / "uploads"
        denied = folder / "deny"
        cli = folder / "tailscale-fixture"
        cli.write_text("#!/usr/bin/env python3\n" + "import sys,json\nfrom pathlib import Path\n" +
            "if sys.argv[1]=='status': print(json.dumps({'BackendState':'Running','Self':{'UserID':7,'Tags':[]}}))\n" +
            f"else: print(json.dumps({{'Node':{{'StableID':'owned-test-device','User':8 if Path({str(denied)!r}).exists() else 7,'Tags':[]}},'UserProfile':{{'ID':8 if Path({str(denied)!r}).exists() else 7}}}}))\n")
        cli.chmod(0o700)
        with socket.socket() as listener:
            listener.bind(("127.0.0.1", 0))
            port = listener.getsockname()[1]
        url = f"http://127.0.0.1:{port}"
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        mapped = False
        with (folder / "server.log").open("w") as log:
            process = subprocess.Popen([str(binary), "serve", "--listen", f"127.0.0.1:{port}", "--tailscale-bin", str(cli), "--upload-dir", str(uploads), "--max-upload-bytes", "2097152", "--shutdown-on-stdin-eof"], stdin=subprocess.PIPE, stdout=log, stderr=log)
            try:
                deadline = time.monotonic() + 10
                while True:
                    try:
                        with opener.open(url + "/v1/info", timeout=1) as response:
                            info = json.load(response)
                            assert info["authentication"] == "tailscale-owner"
                            break
                    except (urllib.error.URLError, TimeoutError):
                        if process.poll() is not None or time.monotonic() >= deadline:
                            raise RuntimeError((folder / "server.log").read_text())
                        time.sleep(.1)
                denied.touch()
                try:
                    opener.open(url + "/v1/info", timeout=2)
                    raise AssertionError("Denied identity was accepted")
                except urllib.error.HTTPError as error:
                    assert error.code == 403
                denied.unlink()
                device("reverse", "--no-rebind", f"tcp:{port}", f"tcp:{port}", capture_output=True)
                mapped = True
                result = device("shell", "am", "instrument", "-w", "-r", "-e", "class", "dev.shulhi.rex.PortsideLiveUploadTest", "-e", "portsideUrl", url, "-e", "portsideDirectory", str(uploads), "dev.shulhi.rex.test/androidx.test.runner.AndroidJUnitRunner", capture_output=True, text=True)
                print(result.stdout)
                assert "OK (1 test)" in result.stdout, "Real Portside image test failed"
                files = list(uploads.rglob("*.png"))
                assert len(files) == 1 and files[0].read_bytes() == PNG
                assert not list(uploads.rglob("*.part")), "Partial upload was left behind"
                print("Real Portside fixture passed: identity, image bytes, server path, insertion handoff, cleanup")
            finally:
                if mapped:
                    subprocess.run([adb, "-s", args.serial, "reverse", "--remove", f"tcp:{port}"], check=False, capture_output=True)
                process.stdin.close()
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.terminate()
                    process.wait(timeout=5)


if __name__ == "__main__":
    main()
