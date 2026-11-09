# Arioso Google Play listing draft

Prepared 2026-11-08 from Preview.45. Keep this as a draft until the exact Play candidate, screenshots, support contact, pricing, name clearance, and production test gates are approved. Arioso includes no books. The locally signed AAB contains the Preview.45 large-text fix; it has not been submitted to Play.

## App details

**Name:** Arioso — Offline Audiobooks

**Short description:** Listen to your own audiobooks offline, organized by author and series.

**Full description:**

Bring your own audiobook files and make a listening library that feels like yours. Arioso plays audio you select from your device, keeps your place in each book, and groups titles by author and series in reading order. Standalone books open directly from an author's page.

Pick up where you left off with the mini-player, chapter navigation, bookmarks, a sleep timer, and adjustable playback speed. Search your library, choose a shelf layout and color theme, and add a local cover or companion PDF when your book includes one. Optional listening history and statistics stay on your device.

Choose individual audio files or an audiobook folder through Android Files. Arioso previews folder changes before adding listings and leaves your originals in place. You can export a local metadata backup, with optional password protection, to a location you choose.

Arioso has no account, ads, analytics, or Internet permission. It does not sell, stream, download, or include audiobooks. You supply files that you have the right to use.

## Links and declarations to verify in Play Console

- Privacy policy: https://github.com/FourWags/arioso-audiobook/blob/main/PRIVACY.md (public HTTP 200 checked 2026-11-08). The in-app policy and this URL must match the candidate.
- Developer: Four Wags Software. A real support email address is required before submission; do not use a commit address without confirming that it is the intended support channel.
- Data safety draft: the app has no Internet permission and does not send data to Four Wags Software. User-selected titles, progress, notes and backups remain on device or at a user-chosen local document provider. Complete Play's current questions against the final artifact and any Play SDK behavior; do not submit this draft as a verified form.
- Content rating and target audience: complete the Play questionnaire from the final content and listing. No included books or user-generated public content.
- Pricing: decide paid versus free before first Play publication. The MIT source and GitHub preview APK are public; Google Play's paid/free setting and buyer value proposition need an explicit decision.
- Signing: the Play upload key is separate from the existing Arioso app-signing key. Enroll the existing app-signing certificate in Play App Signing if cross-channel updates are required. Verify a Play-generated APK and a Play-to-GitHub/GitHub-to-Play update before promising compatibility.
- Name/icon: current vector icon is documented as original Arioso work. Arioso name clearance across relevant territories and related software remains open.

## Visual assets and test gates

- Original 512×512 Play icon and 1024×500 feature graphic have been prepared in this folder, with editable generation code in `build_play_art.py`. Both are RGB PNGs with no alpha and were visually reviewed. They remain drafts pending name/icon clearance and final listing review. Prepare phone screenshots from the exact candidate using only synthetic books and original artwork. Confirm current Play asset rules before upload.
- Screenshots should show the library, an author with standalone and series books, the player, and the folder import preview. Avoid personal book titles and third-party covers.
- Preview.44 passed its published checks, but the Pixel folder-picker report, long playback, live accessibility, battery/performance, Android Auto, and Play delivery are still unqualified. Do not use “production ready” or equivalent claims until the applicable checks pass.
