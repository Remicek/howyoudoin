# HowYouDoin — Release & Follow-ups TODO

_Last updated: 2026-11-03_

This is the running checklist of things left to do, so it can be picked up quickly
in any session. Google Play marketing assets live outside this repo at
`/Users/remik/StudioProjects/GooglePlay/HowYouDoin/` (see its `README.md`).

> **iOS is not shipped** — ignore iOS-only findings and tasks.

---

## Release status (2026-11-03)

**Done**
- [x] Play Console app created (Devvalor org account, `com.devvalor.howyoudoing`, free).
      Play App Signing terms accepted — Google will hold the app signing key; our
      `keys/howyoudoin-release.jks` is the **upload key**.
- [x] Signed release bundle: `GooglePlay/HowYouDoin/release/howyoudoin-1.0.3-vc4.aab` (older ones beside it)
      (built with `./gradlew :composeApp:bundleRelease`; `local.properties` now has the
      `release.*` signing entries; upload cert SHA-256 `A0:DB:92:11:…:CE:DD`).
- [x] Store listing (en-US) saved as draft: title, short + full description, icon,
      feature graphic, 7 screenshots in order, YouTube promo video.
- [x] Privacy policy URL (https://howyoudoin.app/privacy.html) and Ads = "no ads".
- [x] Promo video on YouTube (unlisted): https://www.youtube.com/watch?v=H2h2FWsCjb8

**Left for you (Play Console)** — answers prepared in `GooglePlay/HowYouDoin/play-console-answers.md`
1. Upload the AAB (52 MB) to a release (Internal testing first is fine). On first upload
   choose "Use Google-generated key" for Play App Signing.
2. App access: form is filled (demo mode instructions) — tick the "full access" box, Add, Save.
3. Content rating, Target audience, Data safety, Advertising ID (No), Government / Financial /
   Health (No).
4. After the AAB upload: Foreground service declaration (needs a short demo video) and
   Full-screen intent (Calling).
5. Store settings: category Communication + contact email.
6. Optional: pl-PL listing translation (`listing/listing-pl.md`).

### Done since last update (branch `security-hardening-ci`)
- [x] **WebView host-allowlist (option A).** Per-network `cookieLoginAllowedHosts` pins
      the cookie-login web view to each network's known hosts; Slack stays unrestricted
      (enterprise SSO). Verified with 10 unit tests + an on-device instrumented test
      (LinkedIn/X sign-in pages still load). See `SECURITY_REVIEW.md`.
- [x] **detekt gate green.** Fixed all 7 findings (line wraps, a magic-number constant,
      a return-count refactor, one scoped `@Suppress` with rationale). `./gradlew detekt`
      is now BUILD SUCCESSFUL.
- [x] **Flaky CI test quarantined.** `blueskySignInReportsAWrongAppPassword` (live Bluesky
      sign-in) marked `@Ignore` with a reason; still runnable locally.
- [x] **Matrix access token encrypted at rest.** New `SecretCipher` (Android Keystore
      AES-256-GCM) wraps the stored session, with transparent migration of old plaintext
      sessions (no forced re-login). See `SECURITY_REVIEW.md`.
- [x] **HttpRedirect auth-replay — verified safe, no change.** Ktor 3.2.3 already strips
      `Authorization` on cross-authority redirects (confirmed in its bytecode).

---

## Google Play — before publishing

- [x] **Privacy policy URL:** https://howyoudoin.app/privacy.html.
- [x] **Data safety form:** see "Store listing upload (2026-11-11)" below and `docs/data-safety.md`.
- [x] **Content rating questionnaire:** done (App content shows no declarations left, 2026-11-11).
- [x] **Promo video:** https://www.youtube.com/watch?v=hZRZBLLFlgA (unlisted, uploaded 2026-11-11; replaces
      H2h2FWsCjb8), set in `listings/*/video-url.txt` and published.
- [ ] **15% service fee program:** Play Console asks to enroll (otherwise Play keeps 30% of Premium sales). The
      account owner has to accept it.

### Already done (assets ready in GooglePlay/HowYouDoin)
- 8 framed screenshots (1242×2208, 2026-11-11; also in `composeApp/src/main/play/listings/en-US/graphics/`), redacted — no real contacts/messages/accounts.
- Feature graphic (1024×500), app icon (512), logos.
- Promo MP4 + GIF.
- EN + PL listings (title / short / full descriptions, within char limits).
- Review-risk trimmed: no third-party brand names in title, short desc, feature
  graphic, or screenshot captions; brand names remain only in the full description
  (nominative use + non-affiliation disclaimer) and inside real app-UI screenshots.

---

## Code / repo follow-ups

- [x] **Over-escaped apostrophes fixed.** 22 composeResources strings used `\'`, which
      Compose Multiplatform renders literally; changed to bare `'` (commit `ee2f936`).
- [x] **Deep security & correctness review done** — see `SECURITY_REVIEW.md`. 8 fixes
      landed on `main` (commit `e21a40e`); remaining items tracked above (#6, #7).
- [x] **detekt gate** — all 7 findings fixed; gate is green (branch `security-hardening-ci`).
- [x] **Flaky Bluesky E2E test** — quarantined with `@Ignore` (branch `security-hardening-ci`).

## Notes / decisions
- **GitHub is the only remote used** (`git@github.com:Remicek/howyoudoin.git`).
  Bitbucket (`origin`) is left untouched — unrelated ~80-commit history, no merge-base.

## Publishing from the command line (2026-11-06)

`./gradlew :composeApp:publishReleaseBundle` builds the signed bundle and sends it to the closed test (Alpha) track
(Gradle Play Publisher; config at the end of `composeApp/build.gradle.kts`). Release notes:
`composeApp/src/main/play/release-notes/{en-US,pl-PL}/default.txt`. Raise `projectVersionCode` first.

One-time setup (done 2026-11-06; project howyoudoin-513908, play-publisher@howyoudoin-513908.iam.gserviceaccount.com, testing tracks only):
- [x] Google Cloud → IAM → Service accounts: create one (e.g. `play-publisher`) and download a JSON key to
      `~/StudioProjects/keys/howyoudoin-play-publisher.json`; enable the "Google Play Android Developer API".
- [x] Play Console → Users and permissions → Invite new users: the service account's e-mail, app HowYouDoin,
      permission "Release to testing tracks" (add "Release to production…" later if wanted).
- [x] `local.properties`: `play.serviceAccountJson=/Users/remik/StudioProjects/keys/howyoudoin-play-publisher.json`

## Store listing upload (2026-11-11)

- [x] `play-publisher@howyoudoin-513908.iam.gserviceaccount.com` got "Manage store presence", so
  `./gradlew :composeApp:publishListing` works; the en/pl texts, 8 phone screenshots and the feature graphic
  (en-US only) are published.
- [x] Data safety form switched to "collects data" with the 7 types in `docs/data-safety.md` (app functionality,
  not shared; deletion info at https://howyoudoin.app/privacy.html); sent for review 2026-11-11.
- [ ] 1.1.3 (versionCode 9) is on internal only; production waits for the user's go-ahead.
