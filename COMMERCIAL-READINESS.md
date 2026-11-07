# Arioso commercial readiness

Status: **not cleared for sale or Google Play production**. The current candidate is `0.1.0-preview.44` (version code 44), intended as a GitHub personal preview for phone testing. This document is a release checklist, not a legal opinion. Its evidence is version-specific; a later source edit needs a new build and checks before distribution.

## Product and rights

- Arioso is an offline player for audio that the user supplies. It bundles no audiobook, cover, external font, or personal media. User audio and artwork remain subject to their own rights.
- Application source and the current original vector icon are MIT licensed, copyright © 2026 Four Wags Software. The app bundles its MIT text, component notices, and privacy policy. MIT allows purchasers and others to redistribute the code and APK with the required notice. A paid Play listing must account for the free public GitHub source and preview APK.
- The retained icon alternatives in `Work/retained-arioso-icons/` are not packaged. Confirm authorship and redistribution rights before using one commercially.
- Name and icon clearance for the intended sales territories and categories remains open.

## Exact current evidence

- Preview.44 candidate signer, APK hash, fresh unit tests, lint, privacy/rights audits, and the signed Preview.43 to .44 emulator upgrade are recorded in `CHECKS.txt`. Publication and public-download verification are separate release gates.
- Preview.44 passed 60 freshly executed unit tests and release lint with zero errors and 25 warnings. The warning count matched Preview.43; individual warning lineage was not re-proven.
- The phone was **not** installed or tested with Preview.44. Older Preview.34 to .37 personal checks do not qualify this APK. The current phone version must be read before any phone-upgrade claim.
- On a disposable emulator, the signed candidate opened the all-locations picker, reached Audiobooks outside Downloads, previewed one synthetic M4A, and imported it after approval. The reported Pixel Downloads-only behavior remains a phone test.

## Before a sale-ready candidate

1. **Import and first listen:** Validate folder selection on real phone storage and SD-card providers, including the observed Downloads-only case, cancel, permission denial, empty folder, unreadable provider, nested/large libraries, duplicate/changed files, and individual-file fallback. Confirm originals and existing listings survive every failed scan.
2. **Playback and recovery:** On the exact non-debuggable signed candidate, check long playback, lock/background behavior, chapters, sleep timer, notification and Bluetooth controls, interruptions, process death, low storage, backup/restore/undo, and relinking after lost file access.
3. **Accessibility and performance:** Run live TalkBack and large-text checks across library, import, player, and settings. Establish physical-device startup, interaction, memory, battery, idle and thermal baselines with a repeatable workload. Android Auto and optional Reader Sync handoff need separate physical qualification if advertised.
4. **Privacy and maintenance:** Recheck the packaged permission allowlist, dependencies, exported components, logs, data lifetime, secret scan, component licenses, and exact release notes. Review and classify the 25 inherited lint warnings rather than relying on their count. Keep current plus one previous verified local version, with unique evidence and signing recovery separate.
5. **Identity:** Clear Arioso name/icon rights. Preserve the dedicated signing certificate and its private recovery copy. Prepare a Play App Signing plan and durable upload key; verify Play-generated APK compatibility with the public GitHub channel before promising cross-channel updates.

## Google Play preparation

- Target API 36 is set in the source. Recheck Play requirements at submission time.
- Produce and verify a signed Android App Bundle and its generated APKs. No Play submission or production qualification has been recorded.
- Prepare listing text and screenshots from the exact candidate, a public privacy-policy URL, Data safety answers, support contact, content rating, pricing, tax/payment and refund setup, and required testing-track evidence.
- Decide the Play paid/free model before first publication. The existing MIT source and freely downloadable GitHub APK can be redistributed; a buyer-facing value proposition and support plan are needed.

Preview.44 candidate evidence: `CHECKS.txt` and `Work/qa/folder44/`. Preview.43 publication evidence remains in `Work/qa/standalone43/`. Historical phone evidence remains in its original `Work/qa/` folders and must retain its original version labels.
