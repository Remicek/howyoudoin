# Premium and call minutes: what's left

Built 2026-11-10 (uncommitted). Plan: free 60 min video / 10 h audio a month, calls up to 60 min, 360p; Premium
$2.99 a month (no yearly plan) with 50 h video / 100 h audio, calls up to 4 h, 540p, and the 2nd and 3rd account of
each network. Details and setup: call-link/README.md, "Plans".

2026-11-11 (evening): the 5-hour video top-up (`video_5h`, $1.99) was removed to keep Premium simple: the app, the
Worker (`POST /api/topups` is gone) and these docs; the Play product is deactivated. Bought time saved by the Worker
for earlier test purchases is dropped.

## Done

- Call server: minutes counted per call on the server and charged to whoever started it; video turned off when its
  minutes run out, the call ended at the plan's length or when nothing's left, a 5-minute warning on the call page
  (12 languages); Premium checked with the Google Play Developer API. Tests: `plans.test.ts`,
  `room.test.ts` (metering), `play.test.ts` (a pretend Google).
- App: Settings → Premium (minutes left, what Premium includes, subscribe with the trial when Play offers it,
  manage, restore), in 13 languages; Google Play Billing 9.1.0 (`PlayPremium`); "No call minutes left" in the chat, and an audio call when only the
  video ran out; adding a 2nd/3rd account asks for Premium (accounts already added keep working).
- Checked against the Worker running locally: a new install gets 60 min / 10 h, an old app is told to update, a fake
  Premium token gets the free plan.

## Needs the user

- [x] Play Console (2026-11-11): `premium_monthly` (base plan `monthly`, $2.99, 7-day grace) with offer
      `free-trial-7d` (7 days free, only for people who never had this subscription). Active. (`video_5h` was
      created too and deactivated on 2026-11-11 when the top-up was dropped.)
- [x] Service account with Play access; `npx wrangler secret put PLAY_SERVICE_ACCOUNT`.
- [x] Deploy the Worker.
- [x] License testers and internal testers: list "Rodzina i najomi" (remicek, remiwaw, arkwaw @gmail.com), 2026-11-11.
      Internal test opt-in: https://play.google.com/apps/internaltest/4701300544930208456
- [ ] Buy and cancel with a license tester from an internal-testing build (1.1.3, versionCode 9, is on internal since 2026-11-11).

## Tested on a phone (Galaxy A16 + Chrome on the emulator as the guest, 2026-11-10)

Against `npm run dev` with the free plan shortened for the test only (2 min video, 6 min calls):

- [x] Premium screen: minutes left, the reset day, the benefits; "only from Google Play" on a debug build.
- [x] "Add another account" on a network with one account asks for Premium; "See Premium" opens it.
- [x] A video call: "The call ends in 5 min" on both sides; at 2 min the video stopped on both ("Audio call", camera
      button disabled, the guest's camera tap refused); at 6 min it ended, the guest saw "This call reached its time
      limit."
- [x] Afterwards the screen showed "Video: 0 min left", "Audio: 9 h 56 min left" (2 min video + 4 min audio counted).
- [x] A video call with no video left starts as audio: "No video minutes left: the call goes on with audio."
- Fixed on the way: the notice hid under the self-view (now beside it); after returning to the call from the
  notification the guest's video showed a play icon (WebView pauses media in the background; the page now restarts
  it). Both fixes are not yet seen on a phone.
- `npm run dev` now passes `--local-upstream localhost:8787`, otherwise invite links pointed at the live server.

## Live (2026-11-11)

- Worker deployed to call.howyoudoin.app (migration v2, `CallAccount`); secret `PLAY_SERVICE_ACCOUNT` = Google Cloud
  service account `play-purchases@howyoudoin-513908` (Play Console: view financial data + manage orders/subscriptions
  on HowYouDoin only; separate from `play-publisher`).
- Found live and fixed (089a532): every Premium check threw "Illegal invocation" (fetch called as a method).
- The 401s from Google Play were because `play-purchases` had never been invited in Play Console (Users and
  permissions); invited 2026-11-11 08:08; Play accepted it by 08:19. Check: POST `/api/topups` with a made-up
  token answers 402 once Play answers (502 + "Play purchase check: 401" in `npx wrangler tail` before). Until then
  everyone is free (fails closed).
- A16 against the live server: Premium shows 1 h / 10 h; a 2-minute video call was charged ("58 min left").
- [ ] The guest in a headless Chrome on the Mac stayed on "Connecting…" (it sent video, the phone showed it, but it
      never received the phone's). The same set-up worked against `wrangler dev`. Check with a real browser.
- Release 1.1.1 (versionCode 7) is on GitHub; not yet on Play.

## Not checked yet

- [ ] Buying, the trial and Restore: need the Play product and a build installed from Play.

## Not built

- Themes and scheduled messages (listed as possible Premium extras), priority support (no support channel exists).
- Play Integrity: reinstalling gives a new install ID and so a new free month of minutes (costs cents).
