#!/usr/bin/env python3
"""Fail if Arioso's packaged APK gains any permission outside offline playback."""

import os
import shutil
import subprocess
import sys
from pathlib import Path


ALLOWED_PACKAGES = {
    "package: app.arioso.audiobook",
    "package: app.arioso.audiobook.qa",
}
EXPECTED_PERMISSIONS = {
    "uses-permission: name='android.permission.FOREGROUND_SERVICE'",
    "uses-permission: name='android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK'",
}


def find_aapt() -> str:
    override = os.environ.get("AAPT")
    if override:
        return override
    installed = shutil.which("aapt")
    if installed:
        return installed
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if sdk:
        candidates = sorted(Path(sdk).glob("build-tools/*/aapt"), reverse=True)
        if candidates:
            return str(candidates[0])
    raise RuntimeError("Set AAPT or ANDROID_HOME to an Android SDK containing aapt")


def main() -> int:
    if len(sys.argv) != 2:
        print("Usage: verify_offline_apk.py <apk>", file=sys.stderr)
        return 2
    try:
        output = subprocess.check_output(
            [find_aapt(), "dump", "permissions", sys.argv[1]], text=True
        )
    except (OSError, RuntimeError, subprocess.CalledProcessError) as exc:
        print(f"Could not inspect APK permissions: {exc}", file=sys.stderr)
        return 2
    found = output.splitlines()
    if (len(found) != len(EXPECTED_PERMISSIONS) + 1
            or found[0] not in ALLOWED_PACKAGES
            or set(found[1:]) != EXPECTED_PERMISSIONS):
        print("Offline permission gate failed:", file=sys.stderr)
        print(output, file=sys.stderr)
        return 1
    print("Offline permission gate passed: only media foreground service permissions")
    return 0


if __name__ == "__main__":
    sys.exit(main())
