#!/usr/bin/env python3
"""Check that an Arioso APK or AAB carries notices and no unreviewed media/assets.

This is a packaging check, not proof of copyright ownership or legal clearance.
"""

import sys
import zipfile
import re
from pathlib import Path


PROJECT = Path(__file__).resolve().parents[1]
NOTICES = {
    "assets/licenses/arioso-mit.txt": PROJECT / "LICENSE",
    "assets/licenses/components.txt": PROJECT / "app/src/main/assets/licenses/components.txt",
    "assets/licenses/privacy.txt": PROJECT / "app/src/main/assets/licenses/privacy.txt",
}
MEDIA_SUFFIXES = (".mp3", ".m4a", ".m4b", ".wav", ".flac", ".ogg",
                  ".opus", ".aac", ".mp4", ".jpg", ".jpeg", ".png",
                  ".webp", ".gif", ".ttf", ".otf")


def privacy_dialog_text() -> bytes:
    """Render the small public Markdown policy as plain text for the app dialog."""
    policy = (PROJECT / "PRIVACY.md").read_text(encoding="utf-8")
    policy = re.sub(r"^#+ ", "", policy, flags=re.MULTILINE)
    policy = policy.replace("**", "").replace("`", "").replace("  \n", "\n")
    policy = policy.replace("<https://github.com/FourWags/arioso-audiobook/issues>",
                            "https://github.com/FourWags/arioso-audiobook/issues")
    return policy.encode("utf-8")


def main() -> int:
    if len(sys.argv) != 2:
        print("Usage: audit_commercial_inputs.py <apk-or-aab>", file=sys.stderr)
        return 2
    try:
        package = Path(sys.argv[1])
        if package.suffix not in (".apk", ".aab"):
            print("Expected an APK or AAB", file=sys.stderr)
            return 2
        prefix = "base/" if package.suffix == ".aab" else ""
        with zipfile.ZipFile(package) as archive:
            names = archive.namelist()
            failures = []
            corrupt = archive.testzip()
            if corrupt:
                failures.append(f"Package entry failed CRC: {corrupt}")
            assets = {name for name in names if name.startswith(prefix + "assets/") and not name.endswith("/")}
            expected = {prefix + name: source for name, source in NOTICES.items()}
            unexpected = sorted(assets - expected.keys())
            if unexpected:
                failures.append(f"Unreviewed packaged assets: {unexpected}")
            for name, source in expected.items():
                if name not in assets:
                    failures.append(f"Missing packaged notice: {name}")
                elif archive.read(name) != source.read_bytes():
                    failures.append(f"Packaged notice differs from source: {name}")
            privacy_asset = prefix + "assets/licenses/privacy.txt"
            if privacy_asset in assets and archive.read(privacy_asset) != privacy_dialog_text():
                failures.append("Packaged privacy policy differs from public Markdown policy")
            native = [name for name in names if name.startswith(prefix + "lib/") and not name.endswith("/")]
            if native:
                failures.append(f"Unreviewed native libraries: {native}")
            media = [name for name in names if name.lower().endswith(MEDIA_SUFFIXES)]
            if media:
                failures.append(f"Unreviewed media or font files: {media}")
            if failures:
                for failure in failures:
                    print(failure, file=sys.stderr)
                return 1
            print("Commercial input packaging check passed: notices match; no unreviewed assets, media, fonts, or native libraries")
            return 0
    except (OSError, zipfile.BadZipFile) as error:
        print(f"Could not inspect package: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
