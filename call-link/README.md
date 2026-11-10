# Calls through a link

HowYouDoin can't place or answer calls on WhatsApp, Messenger, Instagram, Telegram, Signal, Slack and the other
networks it reads (only Matrix calls work in the app). Instead, it offers a separate call: the app creates a room
here, sends its link in the chat, and the other person joins from their browser with nothing to install.

- **In the app:** the call buttons in a one-to-one chat's header, and **Call back here** under a call they made
  (ringing, missed or declined). Before the first call, a dialog explains what will happen; **Don't show this again**
  skips it.
- **This folder:** a Cloudflare Worker that creates [LiveKit](https://livekit.io) rooms and signs their tokens, and
  the call page (`web/`, built into `public/`) for both sides. The app shows the same page in a WebView, so it needs
  no second WebRTC library next to the one used for Matrix calls.

## Privacy

- The call is **end-to-end encrypted**: the app makes a random key and puts it only in the links' `#fragment`.
  Browsers never send a fragment to a server, so neither this worker nor LiveKit can see or hear the call.
- A room has space for **two people** and is closed 15 minutes after it's created if nobody joins, and 20 seconds
  after the last person leaves. A link to a closed room says the call has ended.
- The worker stores nothing. Room creation is limited to 6 a minute per IP address.
- Unlike the rest of HowYouDoin, this does use a server in the middle (for connecting the call, not its content).
  The app's explanation dialog says so.

## API

| Request | Answer |
|---|---|
| `POST /api/calls` | `{ room, joinUrl, hostUrl }`: the app starts a call |
| `POST /api/calls/<room>/join` | `{ url, token }`: the invited person's page joins; 404 when the room is gone |
| `GET /call/<room>` | the call page |

## Set up

1. Create a project on [LiveKit Cloud](https://cloud.livekit.io) and note its URL (`wss://….livekit.cloud`), API key
   and secret.
2. With a Cloudflare account:
   ```sh
   cd call-link
   npm install
   npx wrangler login
   # Put the LiveKit URL in wrangler.toml's LIVEKIT_URL, then:
   npx wrangler secret put LIVEKIT_API_KEY
   npx wrangler secret put LIVEKIT_API_SECRET
   npm run deploy
   ```
3. Give the worker a domain (e.g. `call.howyoudoin.app`, under Workers → Settings → Domains & Routes).
4. Build the app with the worker's address in `local.properties`:
   ```properties
   callLink.url=https://call.howyoudoin.app
   ```
   Without it, the feature is left out of the build. CI release builds need it too.

## Develop locally

```sh
brew install livekit
livekit-server --dev --bind 127.0.0.1          # key "devkey", secret "secret"
cd call-link
printf 'LIVEKIT_URL=ws://localhost:7880\nLIVEKIT_API_KEY=devkey\nLIVEKIT_API_SECRET=secret\n' > .dev.vars
npm run dev                                    # http://localhost:8787
npm test
```

For a debug build on an emulator or phone: `callLink.url=http://localhost:8787` in `local.properties`, then
`adb reverse tcp:8787 tcp:8787`, `adb reverse tcp:7880 tcp:7880` and `adb reverse tcp:7881 tcp:7881`.
The app's call page can be inspected from `chrome://inspect` in debug builds.

## Cost

LiveKit Cloud's free plan covers about 250 ten-minute video calls a month (5,000 participant-minutes, 50 GB);
beyond that it's $50 a month. The worker stays within Cloudflare's free plan. Video is capped at 540p, which keeps
data, and so cost, down.
