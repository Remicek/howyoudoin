# Calls through a link: what's left

The feature is built on branch `feature/call-link` (not merged, not pushed). Calls go through Cloudflare Realtime
SFU (it replaced LiveKit on 2026-11-10), end-to-end encrypted by the call page. How it works, and the setup
commands, are in [call-link/README.md](call-link/README.md).

## Done and tested

On the Pixel_10 emulator with LiveKit (2026-11-10), still valid because the app side didn't change:

- Header call buttons in one-to-one non-Matrix chats; the explanation dialog; "Don't show this again" skips it
- The invite link arrives in the chat (sent to the Slack test chat "remicek")
- In the background: the foreground service keeps the microphone on; "Hang up" in the notification ends the call
- The guest's page in 12 languages (picked from the browser's), right-to-left for Arabic

On two real phones with Realtime SFU (2026-11-10: Chrome on a Galaxy A16 as the host, Chrome on a Galaxy A53 as
the guest, a local worker with a real SFU app, Wi-Fi):

- Encrypted video both ways at 540p, and audio both ways (sound level measured, not silence); the browsers used
  RTCRtpScriptTransform
- Camera off: the other side shows "Audio call"; camera on again: video back within seconds
- Wi-Fi off for 8 s: the call recovered within 5 s of it returning
- The call server restarting mid-call: both pages reconnected within about 3 s, video back
- Hanging up on one side ends it on the other; the old link then says the call has ended
- Unit tests: `call-link/test/` (call rules, SFU requests, frame encryption), `HttpLinkCallsTest`, `CallBackTest`;
  CI runs the worker's type check and tests (checks.yml, job `call-link`)

## Needs the owner's accounts

1. **Realtime SFU app:** created (its keys are in `call-link/.dev.vars` locally, never committed).
2. **Deploy:** done 2026-11-10 (`npm run deploy`, secrets via `npx wrangler secret bulk .dev.vars`), at
   `https://call.howyoudoin.app` (the workers.dev address is off). A call from the A16's Chrome to Chrome on the
   Mac worked on the deployed worker (video and audio both ways, hang-up ends both).
3. **TURN (recommended before release):** Realtime → TURN Server → Create, then `wrangler secret put TURN_KEY_ID`
   and `TURN_KEY_TOKEN`. Without it, calls from networks that block direct WebRTC (some offices, some mobile
   carriers) won't connect.
4. **Domain:** done, `call.howyoudoin.app` (custom domain in `wrangler.toml`).
5. **App address:** done, GitHub secret `CALL_LINK_URL` and `callLink.url` in `local.properties` are both
   `https://call.howyoudoin.app`.

## Needs a real phone

- [x] A call started from the app's call screen (the WebView) on `call.howyoudoin.app`: done 2026-11-10 on the
      Pixel_10 emulator (Slack chat "remicek") with desktop Chrome as the guest; video and audio both ways, hanging
      up in the app ended it for both. The A16 has a debug build pointing at `http://localhost:8787` (rebuild to
      use it there).
- [ ] The guest on Safari on an iPhone, and on mobile data (with TURN set up).
- [ ] **Call back through a link** under a real incoming/missed call on WhatsApp, Messenger, Signal, Telegram, Instagram.
- [ ] The link as received in each network's own app: is it clickable, does the preview look fine.
- [ ] The emulator showed an ANR when pressing Home during a call (load average around 7). Check it doesn't happen
      on a phone.

## Small things noticed

- Opening an ended link and pressing Join asks for the microphone before saying the call has ended. The page
  could check the call first.

## Later: group calls

Calls are for two people on purpose (buttons only in one-to-one chats; `room.ts` has a host and one guest). The
SFU handles groups; what it would take:

- [ ] **Server** (`src/room.ts`, `CallRoom`): a list of people with a size limit (e.g. 8) instead of host and
      guest; announce each person's publications to everyone; the call ends when the last person leaves, not
      when anyone hangs up.
- [ ] **Page** (`web/call.ts`): a video grid; receive each person's audio and newest video. The encryption needs
      no change: everyone has the same key from the link.
- [ ] **App:** call buttons in group chats, invite sent to the group (the link then reaches everyone in that chat,
      and anyone they forward it to).
- [ ] **Data:** each person downloads everyone else's video. Above 3–4 people, send two qualities (simulcast) so
      the SFU can send small tiles at the lower one.
- [ ] Test with three or more devices.

## Clean-up

- [ ] Delete the test messages sent to the Slack chat "remicek" on 2026-11-10: six "Join me on a video call…
      http://localhost:8787/…" (they can't be opened) and one with a `call.howyoudoin.app` link (that call has
      ended).

## Decisions

- **Back on the call screen returns to the chat** (decided 2026-11-10): the call goes on in the background, in a task
  of its own ("Call" in recent apps), back through the notification or recent apps. Opening the app shows the
  chats. When the call ends with its screen showing, the app comes back to the chat. Tested on the emulator.
- **The call notification is low-importance** (it shares the `calls_ongoing` channel with Matrix calls, so it
  sits under "Silent"). The green microphone indicator still shows.
- **Privacy wording:** `docs/privacy-policy.md`, `docs/data-safety.md` and the Play descriptions (en, pl) say the
  optional calls through a link use Cloudflare's servers. Re-check the Play Data safety answers before release.
- **iOS:** stubs only (no buttons). The same page would work in a WKWebView later.
- **Merge and release:** merge `feature/call-link` into `main` once deployed and a call from the app works; then
  a release (the store listing text changed too).
