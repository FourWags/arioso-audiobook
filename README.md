# Arioso

Arioso is an offline Android audiobook player for audio files you select on your device. It does not include audiobooks, request Internet access, or copy your original audio. Its Android package is `app.arioso.audiobook`.

## Library and listening

- Browse by Authors, Series, and Books. Series follow numeric reading order; the library offers cover stacks, an A–Z author index, search, favorites, and a persistent mini-player.
- Choose Split Shelf, Cover Grid, or Compact Rows. Black & Tan is the first-launch theme; other Shiba, classic, light, and dark palettes are available. Four system-font choices require no bundled fonts.
- Resume saved places, adjust speed and pitch, use bookmarks and chapter navigation, set a sleep timer, and control playback from the media notification or optional home-screen widget. The app pauses when Android reports an audio-output disconnect.
- Link a local companion PDF or cover, organize a listen-next queue, use private listening history, and optionally enable local statistics. These features store data on the device.
- Import individual audio files or scan a local folder with Android's document picker. Folder scans are bounded; unsupported or missing files can be reviewed and relinked without deleting the original audio.

## Moving from an earlier install

The earlier Android package and Arioso are separate apps. Export a **full metadata backup** from the earlier install, then import it through Arioso's Library backup menu. The backup contains book details and saved places, not audio. Android does not transfer file grants between packages, so use **More > Organize library > Missing files and saved access > Relink missing** to select each original file. Keep the earlier install until you have checked the new library.

Arioso accepts full or password-encrypted backups made by the earlier package. New exports use Arioso's identifier. The six-book personal migration and a synthetic restore/relink test are recorded in [CHECKS.txt](CHECKS.txt).

## Install and updates

The [GitHub releases](https://github.com/FourWags/arioso-audiobook/releases) page carries the personal preview. Its stable APK asset is `arioso-preview.apk`. Obtainium can follow the repository's Latest release; Arioso itself remains offline. Check the release's SHA256SUMS and signer certificate before installing a downloaded APK. An APK for the earlier Android package cannot update Arioso.

This preview has bounded tests and is not yet qualified for sale or a Google Play production launch. Long playback, live accessibility, battery and thermal behavior, Android Auto usability, and the optional companion-reader handoff need more verification. See [CHECKS.txt](CHECKS.txt) and [COMMERCIAL-READINESS.md](COMMERCIAL-READINESS.md).

## Privacy, rights, and source

Arioso requests only Android's two media-playback foreground-service permissions. It requests no Internet, microphone, broad storage, or media-library permission. Book metadata and progress stay in app-private storage; Android automatic backup is disabled. User-selected backups and CSV exports are written to locations you choose in Android Files and may contain titles and notes.

The code and current vector icon are MIT licensed, copyright © 2026 Four Wags Software. The app includes the MIT notice, component notice, and privacy policy under About & licenses. No audiobook, cover, external font, or third-party native library is packaged. Audio you select remains subject to its own rights. See [LICENSE](LICENSE), [CREDITS.txt](CREDITS.txt), and [PRIVACY.md](PRIVACY.md).

Build with a full JDK 21 and the Android SDK using `bash gradlew :app:assembleRelease`. Run fresh unit tests with `bash gradlew --offline --rerun-tasks :app:testDebugUnitTest`. Before distribution, check the exact APK with `python3 tools/audit_commercial_inputs.py <apk>` and `AAPT=/path/to/aapt python3 tools/verify_offline_apk.py <apk>`.
