#!/usr/bin/env python3
"""Check that an Arioso APK or AAB carries notices and no unreviewed media/assets.

This is a packaging check, not proof of copyright ownership or legal clearance.
"""

import sys
import zipfile
import re
import hashlib
from pathlib import Path


PROJECT = Path(__file__).resolve().parents[1]
NOTICES = {
    "assets/licenses/arioso-mit.txt": PROJECT / "LICENSE",
    "assets/licenses/components.txt": PROJECT / "app/src/main/assets/licenses/components.txt",
    "assets/licenses/privacy.txt": PROJECT / "app/src/main/assets/licenses/privacy.txt",
    "assets/licenses/androidx-apache.txt": PROJECT / "app/src/main/assets/licenses/androidx-apache.txt",
}
MEDIA_SUFFIXES = (".mp3", ".m4a", ".m4b", ".wav", ".flac", ".ogg",
                  ".opus", ".aac", ".mp4", ".jpg", ".jpeg", ".png",
                  ".webp", ".gif", ".ttf", ".otf")
# Compiled AndroidX Core 1.9.0 notification PNGs. Release resource shrinking
# renames their paths, so their reviewed bytes are pinned as well as debug names.
ANDROIDX_NOTIFICATION_SHA256 = {
    "21f0b55280be10b187ceff8edef4a5a7047683e6e9fcf47de410aa3839f41094",
    "42aee0b03883e94df5e543348a397d1302c1b8221895871a83b81899f5a9647b",
    "0c6328c7b2420570a7ec38b3beb3bac9a29d895ac1ab7239315aba7266418593",
    "18b328d8017e677d5aad3dbd66bae356b5cac978c6f0670938ea648e51d0eb66",
    "8430ec003e003f3f2ba2f78d48fd150e25407667e530e82e87a7d4841c0fd676",
    "2f7fbda0ba6e53e9a47e2fc0e24e32c447c613a5808c2d6285dc651e83a07ad7",
    "e17761ed280edd93d7141bd504b70f9e9e15a8652288d9e6333bf4ce964ef844",
    "a8676a1793ac8310ffb12986232df66df092d29c242aeedc2b73556c010d38dc",
    "99c14d6855b8be296d58017d3751b8dc849c47b6d1d80ab614ff9075080e461b",
    "0d773e62c756422313262846c787e58f67e0ee87e45e1ae9942002e816c4cb9e",
    "3e09afa21c45372c035598c017c8fb405a6d678e6cb84dc857581cb3b40e482d",
    "20343222f9f79dd565c3a2248494d452e70820ac1c437173c38badd45f962afd",
    "559bf783d765c02338e97952bdc9c6689c7ff99090b8c9813369118da8b080f0",
    "d27af435101ca4cbec0c840bd30687918cd39eb83aab1487420855623a45998b",
    "b44eeda34d7a529c2fe55719eb098fa29707caa91d18de06a7a775d6e6bcf973",
}


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
            media = [name for name in names if name.lower().endswith(MEDIA_SUFFIXES)
                     and not (name.endswith(".png")
                              and hashlib.sha256(archive.read(name)).hexdigest()
                              in ANDROIDX_NOTIFICATION_SHA256)]
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
