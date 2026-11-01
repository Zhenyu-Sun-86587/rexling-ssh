#!/usr/bin/env python3
"""Inspect APK packaging/signature/ELF alignment without installing or running it."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    sdk = Path(os.environ.get("ANDROID_HOME") or Path.home() / "Library/Android/sdk")
    build_tools = sdk / "build-tools/36.0.0"
    ndk = sdk / "ndk/30.0.16248370"
    host = "darwin-x86_64" if os.uname().sysname == "Darwin" else "linux-x86_64"
    tools = ndk / "toolchains/llvm/prebuilt" / host / "bin"
    parser=argparse.ArgumentParser();parser.add_argument("--apk",default="app/build/outputs/apk/debug/app-debug.apk")
    apk=ROOT / parser.parse_args().apk
    env = os.environ.copy()
    studio = Path("/Applications/Android Studio.app/Contents/jbr/Contents/Home")
    if studio.exists(): env["JAVA_HOME"] = str(studio)

    def run(args):
        return subprocess.run([str(a) for a in args], check=True, capture_output=True, text=True, env=env).stdout

    run([build_tools / "apksigner", "verify", "--verbose", apk])
    run([build_tools / "zipalign", "-c", "-P", "16", "-v", "4", apk])
    badging = run([build_tools / "aapt", "dump", "badging", apk])
    label = re.search(r"(?m)^application-label:'([^']*)'$", badging).group(1)
    assert label == "Rexlet", label
    expected = {f"Java_dev_shulhi_rex_NativeBridge_{name}" for name in ["create", "command", "poll", "awaitUpdate", "readUpdate", "destroy"]}
    libraries = []
    with zipfile.ZipFile(apk) as archive, tempfile.TemporaryDirectory(prefix="rex-apk-") as temporary:
        for name in archive.namelist():
            if not name.startswith("lib/") or not name.endswith(".so"): continue
            assert name.startswith("lib/arm64-v8a/"), name
            path = Path(temporary) / Path(name).name
            path.write_bytes(archive.read(name))
            assert "AArch64" in run([tools / "llvm-readelf", "-h", path]), name
            headers = run([tools / "llvm-readelf", "-lW", path])
            alignments = [int(line.split()[-1], 16) for line in headers.splitlines() if line.strip().startswith("LOAD ")]
            assert alignments and all(value >= 16384 for value in alignments), (name, alignments)
            if name.endswith("librex_android.so"):
                symbols = run([tools / "llvm-nm", "-D", "--defined-only", path])
                assert all(symbol in symbols for symbol in expected), "missing JNI export"
            assert archive.getinfo(name).compress_type == zipfile.ZIP_STORED, name
            libraries.append({"name": name, "bytes": path.stat().st_size, "load_alignment": min(alignments)})
    assert any(item["name"].endswith("librex_android.so") for item in libraries)
    assert any(item["name"].endswith("libc++_shared.so") for item in libraries)
    manifest = run([sdk / "cmdline-tools/latest/bin/apkanalyzer", "manifest", "print", apk])
    assert 'package="dev.shulhi.rex"' in manifest
    permissions = re.findall(r'<uses-permission[^>]*android:name="([^"]+)"', manifest)
    assert "android.permission.INTERNET" in permissions
    assert "android.permission.ACCESS_FINE_LOCATION" not in permissions
    print(json.dumps({"apk": str(apk.relative_to(ROOT)), "application_label": label, "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(), "bytes": apk.stat().st_size, "signature_verified": True, "zip_alignment_16k": True, "permissions": permissions, "native_libraries": libraries, "runtime_execution": "not performed by this static APK check"}, indent=2))


if __name__ == "__main__":
    main()
