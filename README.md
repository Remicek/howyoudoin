# HowYouDoin

All your chats in one inbox: WhatsApp, Messenger, Instagram, Telegram and Signal, connected straight from your
Android phone. No server or account is needed. Your messages and logins stay on the phone and go only to the
networks themselves.

> **Unofficial app.** HowYouDoin is not made, endorsed or supported by Meta (WhatsApp, Messenger, Instagram),
> Telegram or Signal. Those services don't officially allow third-party apps and could limit or block an account
> that uses one. Use it at your own risk.

Status: experimental, Android only for now. (An iOS build exists in the code but isn't released; see below.)

## Features

- One inbox for all connected networks, with search and an unread filter
- Text, photos, videos, voice messages and files; reactions, replies, edits, deletions, typing and read receipts
  where the network supports them
- WhatsApp, Telegram and Signal link like a desktop app (QR code, pairing code or login code); Messenger and
  Instagram log in through their websites inside the app
- Notifications from a background connection, without push servers
- App lock with a PIN or fingerprint, hidden message text in notifications, and no screenshots while locked
- Crash reports stay on the phone; you choose whether to share them
- Optional: connect through your own Matrix homeserver with mautrix bridges instead (server mode)

## Building

Requirements: JDK 17+, the Android SDK and NDK, Go (see `bridge-mobile/go.mod`) with `gomobile`/`gobind`, and for
Signal: Rust (via rustup) with the `aarch64-linux-android` target, `cargo-ndk`, `cmake` and `protoc`.

1. Check out the network clients next to this repository, in `../howyoudoin-bridges/`:
   - `mautrix-meta` from <https://github.com/mautrix/meta> at tag `v0.2610.0`, then apply
     `bridge-mobile/patches/mautrix-meta.patch` (`git apply`)
   - `mautrix-telegram` from <https://github.com/mautrix/telegram>
   - `mautrix-signal` from <https://github.com/mautrix/signal> with its submodule (`git submodule update --init`)
2. Build the network clients library: `bridge-mobile/scripts/build-android.sh`. It writes
   `composeApp/libs/hydbridge.aar` and refreshes the in-app license list.
3. Optional, for Telegram: create an app at <https://my.telegram.org> and add to `local.properties`:
   ```
   telegram.apiId=…
   telegram.apiHash=…
   ```
   Without these the build works but doesn't offer Telegram.
4. Build the app: `./gradlew :composeApp:assembleDebug`.

After changing Gradle dependencies, refresh the license list with `./gradlew :composeApp:exportLibraryDefinitions`.

## Privacy

See [docs/privacy-policy.md](docs/privacy-policy.md). In short: no analytics, no servers of ours in the default
mode, nothing leaves the phone except to the networks you connect.

## License

HowYouDoin is free software: you can redistribute it and/or modify it under the terms of the
[GNU Affero General Public License v3](LICENSE) as published by the Free Software Foundation.

It includes third-party code under its own licenses, listed in the app under Settings → Open-source licenses.
Notably mautrix-meta, mautrix-signal and libsignal are AGPL-3.0, whatsmeow is MPL-2.0 and the Telegram MTProto
library (a gotd fork) is MIT. Because of the AGPL components, anyone distributing a modified version must also
offer its source code.

### iOS

The iOS app shares most of the code but isn't released: Apple's App Store terms conflict with the AGPL, and iOS
doesn't keep background connections, so it would need a server for timely notifications. Telegram and Signal
aren't wired up on iOS yet.
