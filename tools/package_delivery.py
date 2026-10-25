#!/usr/bin/env python3
"""Package an already-built APK and a portable source archive, excluding local credentials."""
from pathlib import Path
import hashlib
import shutil
import zipfile
import re

root = Path(__file__).resolve().parents[1]
apk = root / "app/build/outputs/apk/debug/app-debug.apk"
if not apk.is_file():
    raise SystemExit("APK not found. Run ./gradlew testDebugUnitTest lintDebug assembleDebug first.")
output = root / "artifacts"
output.mkdir(exist_ok=True)
version = re.search(r'versionName\s*=\s*"([0-9.]+)"', (root / "app/build.gradle.kts").read_text()).group(1)
delivery_apk = output / f"smslistener-{version}-debug.apk"
shutil.copy2(apk, delivery_apk)
archive = output / f"smslistener-{version}-source.zip"
files = ["settings.gradle.kts", "build.gradle.kts", "gradle.properties", ".gitignore", ".vscode/tasks.json",
         "gradlew", "gradlew.bat", "README.md", "LICENSE", "app/build.gradle.kts"]
for directory in ["app/src", "gradle", "docs", "tools"]:
    files.extend(str(path.relative_to(root)) for path in (root / directory).rglob("*")
                 if path.is_file() and "__pycache__" not in path.parts)
with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as bundle:
    for relative in sorted(files):
        bundle.write(root / relative, "smslistener/" + relative)
checksums = []
for path in [delivery_apk, archive]:
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    checksums.append(f"{digest}  {path.name}\n")
    print(f"{path.name}: {path.stat().st_size:,} bytes")
(output / "SHA256SUMS").write_text("".join(checksums), encoding="utf-8")
