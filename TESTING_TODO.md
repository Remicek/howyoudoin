# Testing TODO: videos and replies across networks

Tested on 2026-11-08 and 2026-11-09 with the A16 (real apps and HowYouDoin) and the emulator (HowYouDoin).

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
| Instagram | ✅  | ✅ | ✅ |
| Slack     | ✅  | ✅ | ✅ (shows as a thread reply in Slack) |
| Discord   | ✅  | ✅ | ✅ |
| Matrix    | ✅  | ✅ (Element on the emulator, same account; thumbnail fixed 2026-11-09) | ✅ |

## Offline and "reconnecting didn't help" (2026-11-09, emulator)

- ✅ Airplane mode: the inbox shows "You're offline" and the saved chats; a chat shows the offline note; Send shows
  the reminder and keeps the text.
- ✅ The "Reconnecting Slack didn't help" card opens Networks with Slack first and the disconnect/connect steps
  (shown with an injected problem; the timing is covered by `ConnectionMonitorTest`). Not yet seen with a real
  network that keeps failing.

## Still to test

- [ ] **Messenger "confirm on the website" flow on Instagram:** Reconnect should open instagram.com's page in the
      same way. Only Messenger was tested, after its cookie choice. It can only be tested when Instagram asks for it.
- [ ] **LinkedIn:** connected only on the A16, so it has no second account to test with.
- [ ] **Bluesky:** not tested, because its chats don't support media.

## Messenger video preview (done 2026-11-09)

Messenger's apps ignore the inline `JPEGThumbnail`; they show a video's frame from `downloadableThumbnail`, a
separately uploaded JPEG encrypted under its own media key with the HKDF info **"Messenger Preview Keys"** (found
in Messenger's native `libstartup.so`, unpacked on a rooted emulator). HowYouDoin now uploads its preview that way
(`messenger_preview.go`, tried upload types: `preview`, then `thumbnail-video`, `image`) and keeps the inline JPEG.
Checked on the A16: a video sent from HowYouDoin shows its frame in Messenger before it is played.
`messenger_preview_test.go` decrypts a preview uploaded by the official app (`testdata/messenger_preview.enc`).

## Known gaps (not bugs in what was tested)

- Videos sent to Matrix carry no `thumbnail_url`, so other Matrix apps show no preview.
- On iOS, the "confirm on the website" page from Reconnect does nothing yet (it's an empty stub).
