# Calls through a link

HowYouDoin can't place or answer calls on WhatsApp, Messenger, Instagram, Telegram, Signal, Slack and the other
networks it reads (only Matrix calls work in the app). Instead, it offers a separate call: the app creates a room
here, sends its link in the chat, and the other person joins from their browser with nothing to install.

- **In the app:** the call buttons in a one-to-one chat's header, and **Call back here** under a call they made
  (ringing, missed or declined). Before the first call, a dialog explains what will happen; **Don't show this again**
  skips it.
- **This folder:** a Cloudflare Worker that runs each call (a Durable Object per call), and the call page (`web/`,
  built into `public/`) for both sides. Audio and video go through
  [Cloudflare Realtime SFU](https://developers.cloudflare.com/realtime/sfu/). The app shows the same page in a
  WebView, so it needs no second WebRTC library next to the one used for Matrix calls.

## How a call works

1. The app sends `POST /api/calls`; the worker makes a call (a `CallRoom` Durable Object) and answers with the
   invite link and the app's own link, which carries the call's secret token in its `#fragment`.
2. The app adds a random encryption key to both links' fragments, sends the invite in the chat and opens its link.
3. Each page opens a WebSocket to its call (`/api/calls/<room>/ws`): the host with the token, the invited person
   with nothing (they get a secret of their own, to come back after a dropped connection).
4. Each page sends its microphone (and camera) to the SFU over one WebRTC connection and receives the other
   person's over another. `CallRoom` makes the SFU requests, so the SFU's secret stays on the server, and tells
   each page what the other publishes.
5. Every audio and video frame is encrypted in the page (`web/e2ee-worker.ts`, `web/frame-crypto.ts`: AES-GCM
   with the link's key), so the SFU forwards frames it can't read. Video is VP8 and at most 540p.

## Privacy

- The call is **end-to-end encrypted**: the app makes a random key and puts it only in the links' `#fragment`.
  Browsers never send a fragment to a server, so neither this worker nor Cloudflare's SFU can see or hear the
  call.
- A call has room for **two people**. It ends 15 minutes after it's created if nobody joins, when either person
  hangs up, when someone stays disconnected for 20 seconds, and after 4 hours at most. A link to an ended call
  says the call has ended.
- The worker keeps a call's state (who's in it, the SFU session IDs) only while it lasts. Call creation is limited
  to 6 a minute per IP address.
- Unlike the rest of HowYouDoin, this does use a server in the middle (for connecting the call, not its content).
  The app's explanation dialog says so.

## API

| Request | Answer |
|---|---|
| `POST /api/calls` | `{ room, joinUrl, hostUrl }`: the app starts a call |
| `GET /api/calls/<room>/ws` | WebSocket to the call (JSON messages, see `CallRoom` in `src/worker.ts`) |
| `GET /call/<room>` | the call page |

## Set up

1. In the Cloudflare dashboard: **Realtime → Serverless SFU → Create**; note the App ID and App Secret.
   Optionally **Realtime → TURN Server → Create** too (its Key ID and API token): it relays calls on networks that
   block direct connections, such as some offices and mobile carriers.
2. Deploy:
   ```sh
   cd call-link
   npm install
   npx wrangler login
   npx wrangler secret put SFU_APP_ID
   npx wrangler secret put SFU_APP_SECRET
   npx wrangler secret put TURN_KEY_ID        # optional
   npx wrangler secret put TURN_KEY_TOKEN     # optional
   npm run deploy
   ```
3. The worker answers at `https://call.howyoudoin.app` only (`routes` in `wrangler.toml`; Cloudflare makes the
   DNS record and certificate on deploy). Every invite link starts with it, so changing it breaks links already
   sent.
4. Build the app with the worker's address in `local.properties`:
   ```properties
   callLink.url=https://call.howyoudoin.app
   ```
   Without it, the feature is left out of the build. CI release builds read it from the `CALL_LINK_URL` secret.

## Develop locally

```sh
cd call-link
printf 'SFU_APP_ID=…\nSFU_APP_SECRET=…\n' > .dev.vars   # a Realtime SFU app; never committed
npm run dev                                            # http://localhost:8787
npm run types && npm run typecheck && npm test
```

For a debug build on an emulator or phone: `callLink.url=http://localhost:8787` in `local.properties`, then
`adb reverse tcp:8787 tcp:8787` (a phone's Chrome can join the same way: open `http://localhost:8787/call/…`).
The media goes straight to Cloudflare. The app's call page can be inspected from `chrome://inspect` in debug
builds.

## Cost

Realtime SFU charges for data sent out of Cloudflare: the first 1,000 GB a month are free, then $0.05 per GB. A
ten-minute 540p video call is about 150 MB, so roughly 6,000 such calls a month are free. The worker and its
Durable Objects stay within Cloudflare's free plan for a small number of calls.
