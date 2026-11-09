# Testing TODO: videos and replies across networks

Tested on 2026-11-08 with the A16 (real apps and HowYouDoin) and the emulator (HowYouDoin).

The test steps:

1. Send a video from HowYouDoin.
2. It arrives in the real app on the other device and in HowYouDoin.
3. Send a video from the real app. HowYouDoin shows it with a thumbnail and can play it.
4. Reply to the video in HowYouDoin.

## Done

| Network   | 1–2 | 3 | 4 (reply) |
|-----------|-----|---|-----------|
| WhatsApp  | ✅  | ✅ | ✅ |
| Telegram  | ✅  | ✅ | ✅ |
| Signal    | ✅  | ✅ | ✅ |
| Messenger | ✅  | ✅ | ✅ |
| Instagram | ✅  | ❌ not tested | ✅ |
| Slack     | ✅  | ✅ | not tested |
| Discord   | ✅  | partly (see below) | ✅ |
| Matrix    | ✅  | not tested (no real Matrix app on the devices) | ✅ |

## Still to test

- [ ] **Instagram step 3:** send a video from the real Instagram on the A16 to remol270 (the emulator). Check the
      thumbnail, length and playback in HowYouDoin. The A16's Instagram now has full photo and video access.
- [ ] **Discord step 3:** a video was sent from the real Discord on the A16 (Rwa → Jeremy) and the emulator's inbox
      shows "Rwa · Video". Still to check: open the chat, then check the thumbnail, length and playback. The emulator
      was taken over by another app (Matchamore, demo mode) before this was checked.
- [ ] **Slack reply:** reply to a video in HowYouDoin, then check the quote in the real Slack.
- [ ] **Messenger "confirm on the website" flow on Instagram:** Reconnect should open instagram.com's page in the
      same way. Only Messenger was tested, after its cookie choice.
- [ ] **LinkedIn:** connected only on the A16, so it has no second account to test with.
- [ ] **Bluesky:** not tested, because its chats don't support media.

## Known gaps (not bugs in what was tested)

- In the real Messenger app, videos from HowYouDoin show a grey box until played, because we don't send a JPEG
  preview with them.
- Videos sent to Matrix carry no `thumbnail_url`, so other Matrix apps show no preview.
- On iOS, the "confirm on the website" page from Reconnect does nothing yet (it's an empty stub).
