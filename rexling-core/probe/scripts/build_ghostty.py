#!/usr/bin/env python3
"""Build the pinned native libghostty-vt; downloads and outputs stay under target/."""
import argparse
import hashlib
import json
from pathlib import Path
import platform
import subprocess
import tarfile

ROOT = Path(__file__).resolve().parents[1]


def download(url, sha, destination):
    if destination.exists() and hashlib.sha256(destination.read_bytes()).hexdigest() == sha:
        return
    partial = destination.with_suffix(destination.suffix + ".part")
    subprocess.run(["curl", "-fL", "--retry", "2", url, "-o", str(partial)], check=True)
    if hashlib.sha256(partial.read_bytes()).hexdigest() != sha:
        raise RuntimeError(f"checksum mismatch: {url}")
    partial.replace(destination)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--prefix", type=Path, default=ROOT.parent / ".build/ghostty-host")
    args = parser.parse_args()
    manifest = json.loads((ROOT.parent / "native/ghostty-source.json").read_text())
    architecture = {"arm64": "aarch64", "aarch64": "aarch64", "x86_64": "x86_64", "AMD64": "x86_64"}.get(platform.machine())
    system = {"Darwin": "macos", "Linux": "linux"}.get(platform.system())
    key = f"{architecture}-{system}"
    if key not in manifest["zig"]:
        raise RuntimeError(f"unsupported build host {key}; build Ghostty manually and set GHOSTTY_PREFIX")
    cache = ROOT.parent / ".build/cache"
    cache.mkdir(parents=True, exist_ok=True)
    toolchain = manifest["zig"][key]
    zig_archive = cache / Path(toolchain["tarball"]).name
    source_archive = cache / "ghostty-source.tar.gz"
    download(toolchain["tarball"], toolchain["shasum"], zig_archive)
    download(manifest["source_url"], manifest["source_sha256"], source_archive)
    zig_root = cache / zig_archive.name.removesuffix(".tar.xz")
    source_root = cache / ("ghostty-" + manifest["revision"])
    for archive, extracted in [(zig_archive, zig_root), (source_archive, source_root)]:
        if not extracted.exists():
            with tarfile.open(archive) as tar:
                tar.extractall(cache, filter="data")
    command = [str(zig_root / "zig"), "build", "-Demit-lib-vt", "-Demit-macos-app=false", "-Demit-xcframework=false", "-Doptimize=ReleaseFast", "--prefix", str(args.prefix.resolve())]
    subprocess.run(command, cwd=source_root, check=True)
    print(f"Built Ghostty {manifest['revision']} at {args.prefix.resolve()}")


if __name__ == "__main__":
    main()
