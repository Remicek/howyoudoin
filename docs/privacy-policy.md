# HowYouDoin privacy policy

Last updated: 19 October 2026

HowYouDoin is a free, open-source chat app that brings your WhatsApp, Messenger, Instagram, Telegram and Signal
conversations into one inbox. This policy explains what happens to your data.

## What we collect

**Nothing.** In its default mode HowYouDoin has no servers of its own, no accounts with us, no analytics, no
advertising and no tracking. The developers never receive your messages, contacts, login details or usage data.

## What stays on your phone

To show your chats, the app keeps on your phone:

- the login sessions of the networks you connect (session cookies, device keys, encryption keys), in the app's
  private storage, partly encrypted with a key from Android's Keystore;
- your chats, messages, contacts' names and pictures, and downloaded photos and files;
- your settings, including the app lock PIN as a salted one-way hash (not the PIN itself).

The app turns off Android backups, so none of this is copied to the cloud. Disconnecting a network in the app deletes its data from the
phone; uninstalling the app or clearing its data deletes everything.

## Where your data goes

When you connect a network, the app talks directly to that network's servers (Meta for WhatsApp, Messenger and
Instagram, Telegram, Signal) to send and receive your messages, just as the official apps do. Those companies'
own privacy policies apply to what they receive.

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
- **Photos and files you pick:** only the ones you choose to send.

## Children

HowYouDoin is not directed at children under the minimum age the connected networks require.

## Changes and contact

Changes to this policy are published with the app's source code. Questions: open an issue in the project's
repository.
