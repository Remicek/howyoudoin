# HowYouDoin privacy policy

Last updated: 28 October 2026

HowYouDoin is a free, open-source chat app that brings your WhatsApp, Messenger, Instagram, Telegram, Signal, Slack,
Bluesky, LinkedIn, Discord, X and Matrix conversations into one inbox. This policy explains what happens to your data.

## What we collect

**Nothing.** In its default mode HowYouDoin has no servers of its own, no accounts with us, no analytics, no
advertising and no tracking. The developers never receive your messages, contacts, login details or usage data. The
app contains no analytics, advertising or crash-reporting libraries from other companies either.

## What stays on your phone

To show your chats, the app keeps on your phone:

- the login sessions of the networks you connect (session cookies, device keys, encryption keys), in the app's
  private storage, partly encrypted with a key from Android's Keystore;
- your chats, messages, contacts' names and pictures, and downloaded photos and files;
- your settings, including the app lock PIN as a salted one-way hash (not the PIN itself);
- your own Telegram API keys, if you enter them (Settings → Telegram API keys), encrypted like the logins.

Your chats and messages are never copied to the cloud. Settings → Back up logins is on unless you turn it off: it
puts only the network logins in your phone's Google backup, and only when that backup is end-to-end encrypted with
your screen lock, so a new phone can reconnect without logging in again. With it off, nothing is backed up. Disconnecting a network in the app deletes its data from the
phone; uninstalling the app or clearing its data deletes everything.

## Where your data goes

When you connect a network, the app talks directly to that network's servers (Meta for WhatsApp, Messenger and
Instagram, Telegram, Signal, Slack, Bluesky, LinkedIn, Discord, X, and the
Matrix homeserver of your account) to send and receive your messages, just as the official apps do. Those companies'
own privacy policies apply to what they receive.

- Messenger, Instagram, Slack, LinkedIn, Discord and X are
  logged into on their own web pages, shown inside the app; those pages are theirs, and the app only keeps the
  session they create.
- Bluesky is signed into with your handle and an app password you create in Bluesky; the app keeps the app password
  on your phone to sign in again.
- X's messages (X Chat) are end-to-end encrypted. Your X Chat passcode unlocks your keys from X's key backup once;
  the keys and the passcode then stay on your phone, encrypted.
- Profile pictures, photos and files are downloaded from those networks' servers.
- Voice and video calls (Matrix) connect to the other person directly or through the relay server of your Matrix
  homeserver.

## Server mode (optional)

If you choose to connect through a Matrix homeserver, your messages go through that server and the bridges
running there. Whoever operates that server (you, or someone you trust) is responsible for that data.

## Crash reports

If the app crashes, a report (the error, the app version and your phone model, not your messages) is saved on
your phone. It's never sent automatically. You can read, share or delete it under Settings → Crash reports, and
choose whether the app offers to share it after a crash.

## Permissions

- **Notifications:** to tell you about new messages.
- **Internet:** to connect to the networks.
- **Running in the background / starting at boot:** so messages keep arriving when the app is closed.
- **Fingerprint / face (optional):** to unlock the app; Android handles the biometric check, the app never sees
  your fingerprint.
- **Microphone (optional):** only while you record a voice message or are in a call; the recording is sent to the
  chat you recorded it in.
- **Camera (optional):** only during a video call.
- **Full-screen notifications:** to show an incoming call on a locked phone.
- **Photos and files you pick:** only the ones you choose to send.

## Children

HowYouDoin is not directed at children under the minimum age the connected networks require.

## Terms of use

Using the app is also subject to the [terms of use](terms-of-use.md), which explain that the app is unofficial,
provided "as is", and that networks may block accounts that use it.

## Changes and contact

Changes to this policy are published with the app's source code. Questions: open an issue in the project's
repository.
