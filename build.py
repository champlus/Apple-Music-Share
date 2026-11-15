#!/usr/bin/env python3
"""Build and verify the signed LeShi Android package."""
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parent
VERSION = "0.3.2"
SDK = Path(os.environ.get("ANDROID_SDK_ROOT", str(Path.home() / "Library/Android/sdk")))
if not (SDK / "build-tools/36.1.0/apksigner").exists():
    SDK = Path.home() / "Library/Android/sdk"
TOOLS = SDK / "build-tools/36.1.0"
DIST = ROOT / "dist"

def run(*args, capture=False):
    return subprocess.run([str(x) for x in args], cwd=ROOT, check=True,
        capture_output=capture, text=capture)

def main():
    DIST.mkdir(exist_ok=True)
    local = ROOT / "local.properties"
    if not local.exists():
        local.write_text("sdk.dir=" + str(SDK) + "\n")
    key = ROOT / ".signing/local-release.p12"
    if not key.exists():
        key.parent.mkdir(exist_ok=True)
        run("keytool", "-genkeypair", "-keystore", key, "-storetype", "PKCS12",
            "-storepass", "lyrics-local-build", "-keypass", "lyrics-local-build",
            "-alias", "lyrics", "-keyalg", "RSA", "-keysize", "3072", "-validity", "10000",
            "-dname", "CN=Local Lyrics Receiver, OU=Personal Tools")
    run("sh", "gradlew", "--console=plain", ":app:assembleRelease")
    apk = DIST / ("乐拾-" + VERSION + ".apk")
    shutil.copy2(ROOT / "app/build/outputs/apk/release/app-release.apk", apk)
    verification = run(TOOLS / "apksigner", "verify", "--verbose", "--print-certs", apk, capture=True)
    (DIST / ("signature-verification-" + VERSION + ".txt")).write_text(verification.stdout + verification.stderr)
    alignment = run(TOOLS / "zipalign", "-c", "-v", "4", apk, capture=True)
    (DIST / ("alignment-" + VERSION + ".txt")).write_text(alignment.stdout)
    for name, args in [("package-info", ["dump", "badging"]), ("permissions", ["dump", "permissions"])]:
        result = run(TOOLS / "aapt2", *args, apk, capture=True)
        (DIST / (name + "-" + VERSION + ".txt")).write_text(result.stdout)
    with zipfile.ZipFile(apk) as z:
        if z.testzip() is not None:
            raise RuntimeError("APK archive integrity check failed")
    source = DIST / ("LeShi-" + VERSION + "-source.zip")
    files = [ROOT / x for x in ["README.md", "build.py", "settings.gradle", "build.gradle",
        "gradle.properties", "gradlew", ".gitignore", "app/build.gradle", "app/proguard-rules.pro"]]
    files += list((ROOT / "gradle").rglob("*")) + list((ROOT / "app/src").rglob("*"))
    with zipfile.ZipFile(source, "w", zipfile.ZIP_DEFLATED) as z:
        for file in sorted(set(files)):
            if file.is_file(): z.write(file, Path("Apple-Music-Share") / file.relative_to(ROOT))
    (DIST / ("SHA256SUMS-" + VERSION + ".txt")).write_text("".join(
        hashlib.sha256(p.read_bytes()).hexdigest() + "  " + p.name + "\n" for p in [apk, source]))
    print("APK:", apk)

if __name__ == "__main__":
    main()
