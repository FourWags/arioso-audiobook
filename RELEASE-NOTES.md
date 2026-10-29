# Arioso Preview 0.1.0-preview.38

Appearance is now visible in the library, with grouped layout, color and app-icon previews and clear selected states. Split Shelf, Cover Grid and Compact Rows receive consistent control/progress polish, large-text support and landscape adjustments. An appearance-only reset keeps books, listening places and playback settings.

Three original charcoal, warm-cream and sesame-orange vector icons join the unchanged default: Page & sound, Sound arcs and Shiba headphones. Android adaptive resources include themed-icon monochrome layers where supported. The retained reference assets were not copied.

This preview fixes icon changes dismissing the app's active task, and prevents screen creation from replacing per-book speed with the global default.

Validation: 60 unit tests pass; final QA runs pass 26 checks on Android 29 and 27 on Android 36; the speed-retention instrumentation passes on both; the exact signed release APK passes 13 smoke checks on each. Signing identity is unchanged, offline/packaging checks pass, and lint has no new warnings. See CHECKS.txt and SHA256SUMS for identities and limits.

This is a personal Preview, not production or Google Play qualification. Phone Obtainium upgrades, long playback, live accessibility, battery/thermals, Android Auto and companion-reader handoff remain unqualified for this APK. No audiobook is bundled. Publication is pending separate approval; any phone update must use Obtainium.
