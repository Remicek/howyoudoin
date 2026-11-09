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
| Matrix    | ✅  | not tested (no real Matrix app on the devices) | ✅ |

## Still to test

- [ ] **Messenger "confirm on the website" flow on Instagram:** Reconnect should open instagram.com's page in the
      same way. Only Messenger was tested, after its cookie choice. It can only be tested when Instagram asks for it.
- [ ] **Messenger video preview:** the real Messenger app ignores the inline `JPEGThumbnail` we now send (still a
      grey box). Logging a video sent by the official app (2026-11-09) showed how it does it: `JPEGThumbnail` is empty
      and `thumbnail.downloadableThumbnail` points to a separately uploaded, encrypted JPEG (~9 KB) with its own
      media key (not the video's), `thumbnailWidth/Height` 480×854, no scans. Its direct path (`/v/t801.45977-0/…`)
      downloads only *without* the `hash=` query parameter (400 with it; `mms-type` doesn't matter). Still unknown:
      which HKDF info string decrypts it (image/video/link-thumbnail/document keys were being tried when the A16 was
      unplugged). Next: find that, then upload our JPEG the same way and fill `downloadableThumbnail`.

      **To pick it up again** (needs the A16: its Messenger is signed in; the A53 has no Messenger):
      1. In `downloadEncryptedMedia` (`bridge-mobile/hydbridge/e2ee_media.go`), in the video case, temporarily try
         `m.e2ee.DownloadMediaWithPath(ctx, dl.GetDirectPath(), nil, dl.GetFileSHA256(), dl.GetMediaKey(), mt,
         "image", true)` for each `whatsmeow.Media*` type, with `dl` = the transport's
         `Ancillary.Thumbnail.DownloadableThumbnail`, and `log.Printf` which one returns data starting `ffd8`
         (JPEG). The emulator's installed debug build already has this probe (logcat tag `GoLog`, `HYDTHUMBDEBUG`).
      2. Send the 6-second test video from the A16's Messenger to Mieszko Chlebek (the emulator's account) and read
         the log. In the A16 picker, if Send shows before anything is picked, leave the chat and come back first,
         or a hidden earlier selection gets sent along.
      3. In `sendEncryptedMedia` (`messenger_media.go`), encrypt-upload the JPEG with that key type and fill
         `Thumbnail.DownloadableThumbnail` (fileSHA256, fileEncSHA256, directPath, mediaKey, mediaKeyTimestamp,
         objectID) instead of `JPEGThumbnail`.
      4. Send a video from HowYouDoin and check that the A16's Messenger shows the frame instead of a grey box.
      5. Remove the probe, rebuild (`bridge-mobile/scripts/build-android.sh`) and reinstall.
- [ ] **LinkedIn:** connected only on the A16, so it has no second account to test with.
- [ ] **Bluesky:** not tested, because its chats don't support media.

## Known gaps (not bugs in what was tested)

- Videos sent to Matrix carry no `thumbnail_url`, so other Matrix apps show no preview.
- On iOS, the "confirm on the website" page from Reconnect does nothing yet (it's an empty stub).
