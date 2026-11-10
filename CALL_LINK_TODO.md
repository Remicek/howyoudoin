# Calls through a link: what's left

The feature is built and tested on branch `feature/call-link` (not merged, not pushed). Everything below needs
the owner's accounts, a real phone, or a decision. How the feature works, and the setup commands, are in
[call-link/README.md](call-link/README.md).

## Done and tested (2026-11-10, Pixel_10 emulator + headless Chrome as the other person, local LiveKit server)

- Header call buttons in one-to-one non-Matrix chats; the explanation dialog; "Don't show this again" skips it
- The invite link arrives in the chat (sent to the Slack test chat "remicek")
- The app's call screen waits for the guest; encrypted video and audio both ways; video capped at 540p
- Hanging up on either side ends the call for both; an expired link says the call has ended
- In the background: the foreground service keeps the microphone on ("Call in progress" notification, the
  microphone not silenced after 25 s on the home screen); "Hang up" in the notification ends the call
- A camera that fails to start (e.g. the app sent to the background while it starts) leaves an audio call
  instead of ending it
- The guest's page in 12 languages (picked from the browser's), right-to-left for Arabic
- Unit tests: `HttpLinkCallsTest`, `CallBackTest`, `call-link/test/token.test.ts`; CI runs the worker's tests
  (checks.yml, job `call-link`)

## Needs the owner's accounts

1. **LiveKit Cloud:** create a project; note its `wss://` URL, API key and secret.
2. **Cloudflare:** `cd call-link && npm install && npx wrangler login`, put the LiveKit URL in `wrangler.toml`,
   `npx wrangler secret put LIVEKIT_API_KEY`, `npx wrangler secret put LIVEKIT_API_SECRET`, `npm run deploy`.
3. **Domain:** route e.g. `call.howyoudoin.app` to the worker (Workers → Settings → Domains & Routes).
4. **GitHub secret `CALL_LINK_URL`** (e.g. `https://call.howyoudoin.app`) so release builds include the feature;
   locally, `callLink.url=` in `local.properties`. Without it the buttons don't appear.

## Needs a real phone

- [ ] A whole call on a real phone (the A53 has the Play build; a debug build can't be installed over it without
      uninstalling). The emulator was too slow to decode 720p video; check 540p looks fine on a phone.
- [ ] **Call back here** under a real incoming/missed call on WhatsApp, Messenger, Signal, Telegram, Instagram
      (none of the test chats had one; `CallBackTest` covers which messages get the button).
- [ ] The guest on a phone browser: Safari on an iPhone and Chrome on Android, on mobile data (LiveKit Cloud
      relays through TURN when a direct connection fails; the local test server had no TURN).
- [ ] The link as received in each network's own app: is it clickable, does the preview look fine.
- [ ] The emulator showed an ANR ("HowYouDoin isn't responding", MainActivity focus timeout) when pressing Home
      during a call, with the emulator's load average around 7. Check it doesn't happen on a phone.

## Clean-up

- [ ] Delete the six test messages "Join me on a video call, it opens in your browser: http://localhost:8787/…"
      sent to the Slack chat "remicek" on 2026-11-10 (they point at a local server and can't be opened).

## Decisions

- **Back on the call screen ends the call** (like closing a browser tab). Alternative: Back returns to the chat
  and the call goes on in the background, back through the notification.
- **The call notification is low-importance** (it shares the `calls_ongoing` channel with Matrix calls, so it
  sits under "Silent"). The green microphone indicator still shows.
- **Privacy wording:** `docs/privacy-policy.md` and the Play descriptions (en, pl) were updated on this branch to
  say the optional calls through a link use a call server. Re-check the Play Data safety answers before release
  (see the note at the end of `docs/data-safety.md`).
- **iOS:** stubs only (no buttons). The same page would work in a WKWebView later.
- **Merge and release:** merge `feature/call-link` into `main` once the accounts are set up and a real-phone
  call works; then a release (the store listing text changed too).
