# Networks

Every network connects straight from the phone: the app runs the network's own client (from the mautrix bridges,
built into `hydbridge`) and talks only to that network's servers. A server bridge is optional and only exists for
WhatsApp and Messenger.

"Tested" means it was connected with a real account (Bluesky: signing in, receiving with a notification, sending,
reactions both ways and reconnecting after a restart, on a Galaxy A53 on 29 October 2026; LinkedIn: signing in, the
existing inbox, receiving with a notification, sending, a reaction, an edit and reconnecting after a restart, on the
same phone the same day; X: logging in, unlocking X Chat with the passcode, receiving with a notification, sending, a
reaction, read receipts, an edit, deleting for everyone and reconnecting after a restart, between two Galaxy phones
the same day). The networks marked untested work against fake servers and
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
| Bluesky | handle and an app password allowed to read messages | HowYouDoin's own client for Bluesky's chat API | tested |
| LinkedIn | linkedin.com in the login browser | mautrix-linkedin (linkedingo) | tested |
| Discord | discord.com in the login browser (the web client's token) | Beeper's discordgo | **untested** |
| X | x.com in the login browser, then the X Chat passcode | mautrix-twitter (twittermeow) | tested |

## What the newer networks can't do yet

- **Bluesky** chats have no attachments, edits or typing indicators (Bluesky's chat API doesn't have them).
  "Delete" removes a message only for the user.
- **LinkedIn**: replies, edits, deletes, reactions, voice messages, photos and files work.
- **Discord**: direct messages and group DMs only; server channels stay out of the inbox. Discord's rules forbid
  using a personal account from another app and it bans accounts for it, so the app warns every time.
- **X**: only X Chat (the encrypted direct messages). The passcode recovers the keys once; X locks Chat after a few
  wrong tries, which the passcode screen says. Replies quote the original, and photos and videos go both ways; other
  files and voice messages can't be sent (X Chat takes audio only inside a video, which would need ffmpeg). A message
  request shows up only once it's accepted in X, and the account needs X Chat set up (X asks for the passcode the
  first time Chat is opened).

## Networks left out

These were considered, but nothing can run them on the phone alone:

- **Google Messages**: the client (mautrix-gmessages' libgm) is in the app but switched off. The Messages app dropped
  QR pairing in 2026, leaving only pairing through the Google account, and Google refuses that sign-in in an app's
  login browser ("This browser or app may not be secure"; Beeper's Android app hits the same and sends people to its
  desktop app). Tried on two Galaxy phones on 29 October 2026.
- **Google Voice**: the client (mautrix-gvoice's libgv) is in the app but switched off for the same reason: Google
  refuses the account sign-in in an app's login browser (tried on the emulator on 29 October 2026). It's also only
  available in some countries (mainly the US).

- **LINE**: no maintained open-source client for its protocol; its chats use LINE's own end-to-end encryption.
- **Viber**: no open-source client for its messaging protocol.
- **Google Chat**: the only open client is the old Python mautrix-googlechat bridge, which can't be built into the
  app the way the Go clients are, and it's no longer maintained.
- **iMessage**: needs Apple hardware (a Mac or an iPhone) or Apple's own servers to register; an Android phone
  can't do it alone.
- **Tumblr**: no open-source client for its messaging.
