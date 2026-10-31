#!/usr/bin/env python3
"""Test the real JNI bridge on the host JVM; never uses an Android device."""
import os
from pathlib import Path
import platform
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]


def run(command, **kwargs):
    subprocess.run([str(v) for v in command], check=True, cwd=ROOT, **kwargs)


def main():
    run([sys.executable, ROOT / "scripts/build_native.py", "--host-test"])
    env = os.environ.copy()
    env["GHOSTTY_PREFIX"] = str(ROOT / ".build/ghostty-host")
    env["CARGO_TARGET_DIR"] = str(ROOT / ".build/rust")
    run([shutil.which("cargo") or str(Path.home() / ".cargo/bin/cargo"), "build", "--locked", "--manifest-path", "native/Cargo.toml", "-p", "rex-android"], env=env)
    studio = Path("/Applications/Android Studio.app/Contents/jbr/Contents/Home")
    jdk = studio if studio.exists() else Path(env["JAVA_HOME"])
    output = ROOT / ".build/jni-smoke"
    output.mkdir(exist_ok=True)
    run([jdk / "bin/javac", "-encoding", "UTF-8", "-d", output, ROOT / "tools/jni-smoke/dev/shulhi/rex/NativeBridge.java"])
    extension = "dylib" if platform.system() == "Darwin" else "so"
    library = ROOT / f".build/rust/debug/librex_android.{extension}"
    run([jdk / "bin/java", "--enable-native-access=ALL-UNNAMED", f"-Drex.native.library={library}", "-cp", output, "dev.shulhi.rex.NativeBridge"])


if __name__ == "__main__":
    main()
