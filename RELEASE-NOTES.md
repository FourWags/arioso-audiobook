# Arioso 0.1.0-preview.39 — repair Preview

This Preview prevents failed or malformed library reads from authorizing whole-library replacement. Saved metadata is preserved while reads fail, and writing resumes only after a complete successful reload. Store-level validation also protects direct replacement paths.

Validation: 60 fresh host tests passed; release lint reported no errors and 25 warnings. Six synthetic metadata-recovery scenarios passed in the separate .qa variant built from the same source. The exact dedicated-key signed APK passed six update/smoke checks on an isolated API 37 emulator: preview38 update accepted, appearance preference retained, settings survived restart, single launcher remained usable, code39 installed. Malformed-row fault injection was on the QA variant, not the signed release package.

Personal Preview; not production, Google Play, or sale-readiness qualification. No physical phone installation or foreground interaction occurred. Real Obtainium phone upgrades, long-duration behavior, live assistive technology and power/thermal behavior remain unqualified for these artifacts.

Use the existing Obtainium repository: https://github.com/FourWags/arioso-audiobook. Stable APK asset: `arioso-preview.apk`. Required corresponding source and notices are included in `arioso-source.zip`. See CHECKS.txt and SHA256SUMS for exact identities.
