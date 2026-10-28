# Arioso Preview 0.1.0-preview.37

Arioso is an offline audiobook player for audio files selected from the device. This preview uses the `app.arioso.audiobook` Android package and the new dedicated Arioso signing key. It cannot update the earlier Android package in place. Export a full metadata backup from an earlier install, import it in Arioso, and relink each audio file; keep the earlier install until the new library is checked.

The starting interface uses Split Shelf, with cascading series covers, an A–Z author index, a persistent mini-player, Black & Tan by default, other color palettes, and four system-font choices. Playback includes chapters, bookmarks, saved places, sleep controls, and pause on audio-output disconnect. The app has no Internet permission and bundles no audiobook.

The exact signed APK passed 60 fresh unit tests, privacy and commercial-input package checks, and an in-place Pixel install. All six migrated library entries retained their core metadata and saved positions; file access was 6/6 after update. See `CHECKS.txt` and `SHA256SUMS` for exact evidence and limits.

This is a personal preview, not a Google Play production or sale-ready build. Long playback, live accessibility, battery and thermal behavior, Android Auto usability, and the optional companion-reader handoff still need testing. The MIT source license permits redistribution; audio files retain their own rights.
