# HowYouDoin — Release & Follow-ups TODO

_Last updated: 2026-11-03_

This is the running checklist of things left to do, so it can be picked up quickly
in any session. Google Play marketing assets live outside this repo at
`/Users/remik/StudioProjects/GooglePlay/HowYouDoin/` (see its `README.md`).

> **iOS is not shipped** — ignore iOS-only findings and tasks.

---

## Worth doing before release — priority order

1. **Host the privacy policy** and paste the URL into the Play listing (blocks submission).
2. **Complete the Data safety form + content rating** in the Play Console (blocks submission).
3. **Upload the promo video to YouTube**, paste the link (Play needs a URL, not a file).
4. **Green CI on `main`.** Fix or quarantine the flaky live-Bluesky E2E test so the
   release build is signed off a green run.
5. **Clear the detekt gate on `main`.** 7 pre-existing findings fail the zero-baseline
   gate (see Code follow-ups). Either fix them or add a baseline before tagging a build.
6. **Security: two cheap wins** from the review (see `SECURITY_REVIEW.md`):
   - Keystore-wrap the Matrix access token (currently plaintext DataStore; self-contained).
   - Add the `HttpRedirect` policy to strip `Authorization` on cross-host redirects
     (after confirming Ktor 3.2.3's default).
7. **Security: decide on the WebView host-allowlist** (Medium-on-paper / Low-in-practice —
   needs a malicious homeserver to trigger). Not a release blocker; schedule it.
8. **Final release build + signing**: verify the release keystore, versionCode/versionName
   bump, R8/shrink config, and a clean `assembleRelease` / bundle.

---

## Google Play — before publishing

- [ ] **Privacy policy URL.** The app already has a privacy screen; its text needs to
      be hosted at a public URL and pasted into the Play Console listing.
- [ ] **Data safety form.** Declare: no analytics, processing on-device, crash reports
      stay local, login backup = the user's own end-to-end-encrypted Google backup.
- [ ] **Content rating questionnaire.** Complete in the Play Console.
- [ ] **Promo video.** Play accepts a **YouTube URL, not a file**. Upload
      `video/howyoudoin_promo.mp4` to YouTube and paste the link.

### Already done (assets ready in GooglePlay/HowYouDoin)
- 7 framed screenshots (1242×2208), redacted — no real contacts/messages/accounts.
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
- [ ] **detekt gate red on `main`** (zero-baseline, so every finding fails). 7
      pre-existing findings, none from the review: `OnDeviceMessengerStore.kt`
      (TooManyFunctions), `NativeOnDeviceMessenger.kt` (ReturnCount),
      `BackgroundKeepAlive.kt` (MaxLineLength, MagicNumber), `BackgroundOffer.kt` /
      `InboxScreen.kt` / `CaptchaChallengeTest.kt` (MaxLineLength). Fix or baseline.
- [ ] **GitHub CI red X.** A flaky E2E test `MoreNetworksTest >
      blueskySignInReportsAWrongAppPassword` (live Bluesky sign-in, timed out) fails
      the run. Build/bridge/unit jobs pass. Rerun `--failed`, or mark it non-blocking.

## Notes / decisions
- **GitHub is the only remote used** (`git@github.com:Remicek/howyoudoin.git`).
  Bitbucket (`origin`) is left untouched — unrelated ~80-commit history, no merge-base.
