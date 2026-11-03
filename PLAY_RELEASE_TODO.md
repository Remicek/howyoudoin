# HowYouDoin — Release & Follow-ups TODO

_Last updated: 2026-11-02_

This is the running checklist of things left to do, so it can be picked up quickly
in any session. Google Play marketing assets live outside this repo at
`/Users/remik/StudioProjects/GooglePlay/HowYouDoin/` (see its `README.md`).

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

- [ ] **Fix over-escaped apostrophe in a settings string.** It renders literally as
      `Using HowYouDoin\'s keys` — the `\'` in the string resource should be a plain
      apostrophe. (Patched in the screenshot only; the app resource still has it.)
- [ ] **GitHub CI red X.** Actions run `41298831404` failed on one flaky E2E test,
      `MoreNetworksTest > blueskySignInReportsAWrongAppPassword` (does a live Bluesky
      sign-in, timed out). Build/bridge/unit jobs passed; not gitleaks, not the
      Telegram-key commit. Rerun with `gh run rerun 41298831404 --failed` if desired.

## Notes / decisions
- **Bitbucket (`origin`) left untouched** on purpose — it has an unrelated history
  (~80 commits of different work) with no merge-base against GitHub `main`. Not
  force-pushed. GitHub `github/main` is the tracked upstream and is up to date.
