# HowYouDoin — Security review, 11 November 2026

_Status, 12 November: items 1–10 fixed (see "What was done" at the end); 11 and 12 are accepted risk. The link
format also changed (one 128-bit key after `#`, the call named by its hash), see call-link/README.md._

_Scope: what was added since the whole-app review of 2 November (SECURITY_REVIEW.md): calls through a link (the
Worker in `call-link/`, its call page, the app's call screen), Premium and Google Play Billing, up to 3 accounts
per network, the rating prompt, the new Go bridge code (calls from other networks, video previews, MP4 metadata),
the CI workflows, and today's Messenger and top-up changes. Read against `main` at 8ddf555. Nothing was changed
by this review; every item is a recommendation, with the fix next to it._

## Overall

**No critical or high finding.** The call service is carefully built: 128-bit random room names and host tokens,
compared in constant time; the encryption key and host token only in the link's `#fragment` (never sent to a
server; the host's is wiped from the address bar on load); the Realtime SFU secret kept on the server; every SFU
session and track name checked against the room's own state before use; Google Play purchases verified
server-side and counted per order, so a shared token can't multiply minutes; strict patterns on every token; a
per-IP limit on creating calls; nothing kept once a call ends. The app keeps the earlier guarantees: HTTPS only,
no exported components beyond the launcher, backups limited to the end-to-end-encrypted login file, WebViews with
file and content access off and a one-method JavaScript interface.

What's left: three medium items (one about server cost, two about what the encryption promise covers) and a set
of low, defence-in-depth items. Suggested order: **1, 2, 4, 7** first; they're small and worth the most.

## Medium

### 1. Call server: SFU sessions aren't capped per person
`call-link/src/worker.ts`, `CallRoom.handle()`. In `publish`, a new SFU session is opened before the request is
fully validated, and a rejected request leaves that session open. In `subscribe`, `fresh: true` opens a new
session each time, with no ceiling. Track *names* are capped (`MAX_TRACKS`), sessions aren't. Someone already in
a call could run the SFU bill up without using any minutes.
**Fix:** validate first (`publish(room, …)` before `sfu.newSession()`), count sessions per member and refuse past
a small number (e.g. 10), and add a per-connection message rate (e.g. 30 messages a minute) in
`webSocketMessage`.

### 2. "End-to-end encrypted" is only as strong as the chat the link goes through
The key is in the invite's `#fragment`, so HowYouDoin's server and Cloudflare can't read the call: that claim is
true. But the invite itself travels through the other network. On chats that aren't end-to-end encrypted
(Telegram cloud chats, Slack, Discord, LinkedIn, X, Bluesky, Google Messages over SMS/RCS without encryption) the
provider holds the key and the room name, and whoever opens the link first within 15 minutes is the guest.
`link_call_how_encrypted` ("It goes through HowYouDoin's call server, which can't see or hear it") and the privacy
policy's "Calls through a link" section say nothing about this.
**Fix (text, now):** one sentence in the explanation and the privacy policy: "Anyone who can read the chat you
send the link in can join or listen; on networks without end-to-end encryption that includes the network." **Fix
(product, later):** show the guest's arrival to the host before media flows ("Someone opened your link: let them
in?"), which also closes the first-come-first-served window.

### 3. One frame key for both directions, no replay protection
`call-link/web/frame-crypto.ts`, `e2ee-worker.ts`. Both people encrypt with the same AES-GCM key and random
96-bit IVs. Random IVs are fine for a call's frame count (far below the 2^32 guidance). What's missing: frames
aren't bound to a direction or a sequence, so a relay that could see the ciphertext (the SFU, or whoever controls
the Worker) could feed a person their own earlier frames as the other person's. Not a confidentiality issue, an
integrity one, and only against a compromised relay.
**Fix:** derive two keys with HKDF from the link key (`info = "host"` / `"guest"`), and put a per-sender frame
counter in the additional data; drop frames whose counter goes backwards.

## Low

### 4. No security headers on the call page
`call-link/public/` has no `_headers` file, so the page is served without a Content-Security-Policy,
`frame-ancestors`, `Permissions-Policy` or `Referrer-Policy` header (the page sets `no-referrer` through a meta
tag, which covers only navigation). The page has no inline scripts and no user-supplied HTML, so a strict policy
costs nothing.
**Fix:** add `public/_headers` with `Content-Security-Policy: default-src 'self'; connect-src 'self' wss:;
media-src 'self' blob:; worker-src 'self'; frame-ancestors 'none'`, `Permissions-Policy: camera=(self),
microphone=(self)`, `Referrer-Policy: no-referrer`, `X-Content-Type-Options: nosniff`.

### 5. The per-IP rate limit will bite phones on carrier NAT
`wrangler.toml` allows 6 call creations a minute per IP, and `/api/usage` shares the same limiter. Mobile
carriers put thousands of phones behind one address, so this is an availability risk for real users before it
is a defence. **Fix:** key the limit on IP *and* install ID (`${kind}:${ip}:${install}`) with a higher per-IP
ceiling (e.g. 60/min), or use two limiters.

### 6. CI: workflows without a `permissions:` block
`android.yml`, `demo-gif.yml` and `bridge.yml` don't set `permissions`, so their `GITHUB_TOKEN` gets the
repository default (often read/write). `checks.yml` and `release.yml` do set it. `android.yml` runs on every push
and pull request. Also `reactivecircus/android-emulator-runner@v2` is pinned to a moving tag.
**Fix:** `permissions: contents: read` in the three workflows; pin third-party actions to a commit SHA.

### 7. Call screen: any URL the page navigates to is handed to Android
`LinkCallActivity.shouldOverrideUrlLoading` sends every non-origin URL to `startActivity(ACTION_VIEW)`, custom
schemes included (`intent:`, `tel:`, other apps' deep links). Only the first-party call page can trigger it today,
so this matters only if the call server were compromised. **Fix:** allow `http`/`https` only.

### 8. Release builds keep `println` logging
`HttpClientExt`, `NativeOnDeviceMessenger.log`, `NativeOnDeviceLinkedAccount.log`, `MatrixSyncEngine` and others
print to logcat in release builds, including exception messages that can contain URLs. No secrets were found in
them, and on modern Android only system apps read logcat, but it's free to remove. **Fix:** route through a
logger that's a no-op when `!BuildConfig.DEBUG`, or a ProGuard `-assumenosideeffects` rule for the log helpers.

### 9. Go bridge: a received video is read whole to find its size
`bridge-mobile/hydbridge/mp4meta.go`, `withVideoMeta` (called for Discord and Slack videos) does
`os.ReadFile(path)` to walk the MP4 boxes. The parser itself is bounds-checked. On a phone, a video at the
download cap becomes a same-size allocation. **Fix:** read with `os.Open` + `io.ReadAtLeast` of the first few MB
(moov is normally at the start) and fall back to zero values otherwise.

### 10. Account-action WebView: third-party cookies on, no `onPageStarted` safety net
`AccountActionWebView.android.kt` pins navigation with `shouldOverrideUrlLoading` and the per-network allowlist
(good), but unlike `CookieCaptureWebView` it has no `onPageStarted` fallback and it enables third-party cookies
for the session it injects. **Fix:** copy the safety net from `CookieCaptureWebView`; keep third-party cookies on
only if Facebook's consent flow fails without them (worth one test).

### 11. Premium gate for extra accounts is client-side only
Adding a 2nd/3rd account asks for Premium in the app; a modified app can skip it. The call minutes, the thing
with a cost, are enforced by the server, so this is accepted risk. Noting it so nobody mistakes it for a server
check.

### 12. Install ID is the free-plan identity
A reinstall gives a new random install ID and so a new month of free minutes (already in PREMIUM_TODO.md). Cents
per abuse; Play Integrity would close it if it ever matters.

## Verified fine (so nobody re-checks)

- Room name and host token: `crypto.getRandomValues`, 16 bytes, base64url; `sameSecret` is constant-time.
- The guest's return secret and the host token are compared, never logged; the Worker's catch-all answers a fixed
  message and logs only server-side.
- `subscribe` is accepted only for the other person's *server-issued* publishing session and announced tracks;
  `publish` only for valid names within `MAX_TRACKS`; track names match `^(audio|video[1-9][0-9]{0,2})$`.
- SDP from the page goes only to the SFU, never into HTML; the page writes only `textContent` and constant SVG.
- `decodeKey` requires exactly 32 bytes; a frame that fails to decrypt is dropped, never played in the clear.
- Google Play: subscription state checked with the Developer API, cached 10 minutes, keyed by the token's SHA-256;
  ownership is the first order ID; a failed check never grants Premium to an unknown token; purchase tokens must
  match `^[A-Za-z0-9._:-]{10,500}$`, install IDs `^[A-Za-z0-9_-]{16,64}$`.
- The app's key for a call: `SecureRandom`, 32 bytes, only ever in the fragments of the two links.
- `LinkCallActivity` is not exported; `onPermissionRequest` grants camera/microphone to the call origin only, and
  only what Android already granted; `about:blank` + `destroy()` on leaving.
- Network security config: cleartext off in release. Backup rules unchanged from the last review.
- MP4 box walker: every slice is checked against the buffer, depth-limited to 8; thumbnails capped at 256 KB and
  checked with `image.DecodeConfig` (headers only) before use; Telegram thumbnails go through `mediaPath`.
- Call events from the networks carry only state, timestamps and a fixed English text; no HTML, no URLs.
- Multi-account storage directories come from the app's own `AccountRef` slots, not from network data.
- Release gate: gitleaks and govulncheck run in `checks.yml` before every release build.
- Today's changes: the Messenger encryption fix only ever *raises* a chat to encrypted (never lowers); the
  pending-message cache keeps the same text the user typed, marked failed; the top-up removal leaves no endpoint
  that accepts a purchase token other than the subscription check.

## Not reviewed here

The `feature/group-calls` branch (unmerged, not deployed), the iOS stubs, and the third-party bridge libraries
beyond HowYouDoin's own glue.

## What was done (12 November)

| # | Fix |
|---|---|
| 1 | `publish` validates before any SFU session is opened; each person may open at most 12 SFU sessions in a call (`MAX_SESSIONS`, `takeSession`); each connection may send 60 WebSocket messages a minute (closed with 4008 past that). |
| 2 | The explanation before the first call (13 languages) and the privacy policy now say the link is as private as the chat it's sent in, and that a network without end-to-end encryption could read it. An optional admission step followed: with Settings → Privacy → *Let people into calls yourself* on, the guest waits until the host lets them in (`askFirst`, `letIn`/`turnAway` in `room.ts`). |
| 3 | A key per direction (HKDF of the link key with the sender's role) and an IV made of a random stream prefix plus a frame counter; a receiver drops a frame whose counter doesn't move forward (`frame-crypto.ts`, with tests). |
| 4 | `public/_headers`: CSP, `frame-ancestors 'none'`, Permissions-Policy, Referrer-Policy, nosniff. |
| 5 | The 6-a-minute limit is now per install and address; a second limiter caps an address at 120 a minute (`IP_LIMIT`). |
| 6 | `permissions: contents: read` in `android.yml`, `demo-gif.yml`, `bridge.yml`; `android-emulator-runner` pinned to its commit. |
| 7 | `LinkCallActivity` hands only `http`/`https` URLs to Android. |
| 8 | ProGuard strips `println`/`print` from release builds. |
| 9 | `withVideoMeta` reads at most the first 4 MB of a video. |
| 10 | `AccountActionWebView` got the `onPageStarted` safety net; third-party cookies stay on (Facebook's consent flow needs its own frames). |
