# Premium and call minutes: what's left

Built 2026-11-10 (uncommitted). Plan: free 60 min video / 10 h audio a month, calls up to 60 min, 360p; Premium
$2.99 a month (no yearly plan) with 50 h video / 100 h audio, calls up to 4 h, 540p, and the 2nd and 3rd account of
each network; a 5-hour video top-up for $1.99. Details and setup: call-link/README.md, "Plans".

## Done

- Call server: minutes counted per call on the server and charged to whoever started it; video turned off when its
  minutes run out, the call ended at the plan's length or when nothing's left, a 5-minute warning on the call page
  (12 languages); Premium and top-ups checked with the Google Play Developer API. Tests: `plans.test.ts`,
  `room.test.ts` (metering), `play.test.ts` (a pretend Google).
- App: Settings → Premium (minutes left, what Premium includes, subscribe with the trial when Play offers it, top-up,
  manage, restore), in 13 languages; Google Play Billing 9.1.0 (`PlayPremium`); a top-up is handed to the server and
  retried until it's counted (`TopUpClaimer`); "No call minutes left" in the chat, and an audio call when only the
  video ran out; adding a 2nd/3rd account asks for Premium (accounts already added keep working).
- Checked against the Worker running locally: a new install gets 60 min / 10 h, an old app is told to update, a fake
  Premium token gets the free plan.

## Needs the user

- [ ] Play Console: create `premium_monthly` (monthly base plan, $2.99, 7-day trial offer) and `video_5h` ($1.99).
- [ ] Service account with Play access; `npx wrangler secret put PLAY_SERVICE_ACCOUNT`.
- [ ] Deploy the Worker (adds the `CallAccount` Durable Object). Builds from before this get "update the app" when
      starting a call; the released v1.1.0 has no calls through a link, so only debug builds are affected.
- [ ] Buy and cancel with a license tester from an internal-testing build.

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

## Not checked yet

- [ ] Buying, the trial, the top-up and Restore: need the Play products and a build installed from Play.

## Not built

- Themes and scheduled messages (listed as possible Premium extras), priority support (no support channel exists).
- Play Integrity: reinstalling gives a new install ID and so a new free month of minutes (costs cents).
- The install ID isn't in the login backup, so bought top-ups don't follow a reinstall.
