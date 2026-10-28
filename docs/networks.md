# Networks

Every network connects straight from the phone: the app runs the network's own client (from the mautrix bridges,
built into `hydbridge`) and talks only to that network's servers. A server bridge is optional and only exists for
WhatsApp and Messenger.

"Tested" means it was connected with a real account. The networks marked untested work against fake servers and
real servers' refusals in the tests (wrong logins, a real pairing QR code), but have never been signed in with a
real account; the app shows "Untested" on their card until they have.

| Network | Logs in with | Client | Real account |
|---|---|---|---|
| WhatsApp | QR code or pairing code (linked device) | whatsmeow | tested |
| Messenger | messenger.com in the login browser | mautrix-meta (messagix) | tested |
| Instagram | instagram.com in the login browser | mautrix-meta (messagix) | tested |
| Telegram | phone number and login code, or QR code | mautrix-telegram (gotd) | tested |
| Signal | QR code (linked device) | mautrix-signal (libsignal) | tested |
| Slack | slack.com in the login browser | mautrix-slack (slack-go) | tested |
| Matrix | homeserver, username, password | the app's own Matrix client | tested |
| Google Messages | QR code, or Google account and an emoji tapped on the phone | mautrix-gmessages (libgm) | **untested** |
| Bluesky | handle and an app password allowed to read messages | HowYouDoin's own client for Bluesky's chat API | **untested** |
| LinkedIn | linkedin.com in the login browser | mautrix-linkedin (linkedingo) | **untested** |
| Discord | discord.com in the login browser (the web client's token) | Beeper's discordgo | **untested** |
| X | x.com in the login browser, then the X Chat passcode | mautrix-twitter (twittermeow) | **untested** |
| Google Voice | Google account on voice.google.com | mautrix-gvoice (libgv) | **untested** |

## What the newer networks can't do yet

- **Google Messages** needs the Messages app on the user's Android phone to stay on and online, like Messages for web.
  The QR code has to be scanned from another screen.
- **Bluesky** chats have no attachments, edits or typing indicators (Bluesky's chat API doesn't have them).
  "Delete" removes a message only for the user.
- **LinkedIn**: replies, edits, deletes, reactions, photos and files work; voice messages go as audio files.
- **Discord**: direct messages and group DMs only; server channels stay out of the inbox. Discord's rules forbid
  using a personal account from another app and it bans accounts for it, so the app warns every time.
- **X**: only X Chat (the encrypted direct messages). The passcode recovers the keys once; X locks Chat after a few
  wrong tries, which the passcode screen says. Replies go as plain messages, photos and files can't be sent yet, and
  only each chat's latest messages load (no older history yet).
- **Google Voice**: texts, photos, calls and voicemails (shown as messages); replies, edits, deletes and reactions
  don't exist there. Google's web page signs texts with a browser-made token that the app doesn't make
  (mautrix-gvoice runs Electron for it); Google accepts texts without it but may limit accounts that send many.
  Google Voice is only available in some countries (mainly the US).
- **Google sign-in** (Google Messages' Google-account option, Google Voice) happens in the login browser, which
  Google sometimes refuses as "not secure"; Google Messages' QR code option doesn't need it.

## Networks left out

These were considered, but nothing can run them on the phone alone:

- **LINE**: no maintained open-source client for its protocol; its chats use LINE's own end-to-end encryption.
- **Viber**: no open-source client for its messaging protocol.
- **Google Chat**: the only open client is the old Python mautrix-googlechat bridge, which can't be built into the
  app the way the Go clients are, and it's no longer maintained.
- **iMessage**: needs Apple hardware (a Mac or an iPhone) or Apple's own servers to register; an Android phone
  can't do it alone.
- **Tumblr**: no open-source client for its messaging.
