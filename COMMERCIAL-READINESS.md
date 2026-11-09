# Arioso commercial readiness

Status: **not cleared for sale or Google Play production**. The current candidate is `0.1.0-preview.45` (version code 45), intended as a GitHub personal preview for phone testing. This document is a release checklist, not a legal opinion. Its evidence is version-specific; a later source edit needs a new build and checks before distribution.

Preview.44 was published as GitHub Latest and its public assets were hash verified. Preview.45 adds the large-text empty-library fix; release and phone evidence for Preview.45 must be recorded separately. The existing local Play bundle is based on Preview.44 and does not contain that fix.

## Product and rights

- Arioso is an offline player for audio that the user supplies. It bundles no audiobook, cover, external font, or personal media. User audio and artwork remain subject to their own rights.
- Application source and the current original vector icon are MIT licensed, copyright © 2026 Four Wags Software. The app bundles its MIT text, component notices, and privacy policy. MIT allows purchasers and others to redistribute the code and APK with the required notice. A paid Play listing must account for the free public GitHub source and preview APK.
- The retained icon alternatives in `Work/retained-arioso-icons/` are not packaged. Confirm authorship and redistribution rights before using one commercially.
- Name and icon clearance for the intended sales territories and categories remains open.

## Exact current evidence

- Preview.44 candidate signer, APK hash, fresh unit tests, lint, privacy/rights audits, and the signed Preview.43 to .44 emulator upgrade are recorded in `CHECKS.txt`. Publication and public-download verification are recorded separately in `Work/qa/folder44/PUBLICATION.txt`.
- Preview.44 passed 60 freshly executed unit tests and release lint with zero errors and 25 warnings. The warning count matched Preview.43; individual warning lineage was not re-proven.
- On 2026-11-08 the Pixel was read-only checked: it has the unchanged dedicated signer and Preview.43. The user was actively using ChatGPT, so Obtainium, Preview.44 installation and the reported folder-picker issue remain pending. Older Preview.34 to .37 personal checks do not qualify Preview.44.
- On a disposable emulator, the signed candidate opened the all-locations picker, reached Audiobooks outside Downloads, previewed one synthetic M4A, and imported it after approval. The reported Pixel Downloads-only behavior remains a phone test.
- At 200% system text on a disposable Android 36 emulator, the signed Preview.44 empty shelf clipped its individual-file action. The Preview.45 source fix made that action reachable after scrolling. A pre-version-bump run passed fresh 60 unit tests, release lint (0 errors, 25 warnings), and the synthetic startup/recreation and rail navigation instrumentation checks; see `Work/qa/play-prep/LARGE-TEXT.txt` and `Work/qa/play-prep/INSTRUMENTATION.txt`. The exact Preview.45 build needs its own release checks. Live TalkBack remains pending.

## Before a sale-ready candidate

1. **Import and first listen:** Validate folder selection on real phone storage and SD-card providers, including the observed Downloads-only case, cancel, permission denial, empty folder, unreadable provider, nested/large libraries, duplicate/changed files, and individual-file fallback. Confirm originals and existing listings survive every failed scan.
2. **Playback and recovery:** On the exact non-debuggable signed candidate, check long playback, lock/background behavior, chapters, sleep timer, notification and Bluetooth controls, interruptions, process death, low storage, backup/restore/undo, and relinking after lost file access.
3. **Accessibility and performance:** Run live TalkBack and large-text checks across library, import, player, and settings. Establish physical-device startup, interaction, memory, battery, idle and thermal baselines with a repeatable workload. Android Auto and optional Reader Sync handoff need separate physical qualification if advertised.
4. **Privacy and maintenance:** Recheck the packaged permission allowlist, dependencies, exported components, logs, data lifetime, secret scan, component licenses, and exact release notes. Review and classify the 25 inherited lint warnings rather than relying on their count. Keep current plus one previous verified local version, with unique evidence and signing recovery separate.
5. **Identity:** Clear Arioso name/icon rights. Preserve the dedicated signing certificate and its private recovery copy. Prepare a Play App Signing plan and durable upload key; verify Play-generated APK compatibility with the public GitHub channel before promising cross-channel updates.

## Google Play preparation

- Target API 36 is set in the source. Recheck Play requirements at submission time.
- A **Preview.44-source** Android App Bundle was locally signed with a separate, privately backed-up upload key. Bundle validation, local universal APK generation with the existing app signer, signer/package/permission checks and bytecode comparison with the GitHub APK passed; see `Work/qa/play-prep/CHECKS.txt`. This bundle predates the unreleased large-text fix. No Play submission or production qualification has been recorded.
- Draft listing text, original feature graphic and Play icon, and a verified public privacy-policy URL are under `Store/`. Prepare screenshots from the final candidate using synthetic books and original artwork. Complete Data safety answers, a confirmed support contact, content rating, pricing, tax/payment and refund setup, and required testing-track evidence before submission.
- Decide the Play paid/free model before first publication. The existing MIT source and freely downloadable GitHub APK can be redistributed; a buyer-facing value proposition and support plan are needed.

Preview.44 candidate evidence: `CHECKS.txt` and `Work/qa/folder44/`. Preview.43 publication evidence remains in `Work/qa/standalone43/`. Historical phone evidence remains in its original `Work/qa/` folders and must retain its original version labels.
