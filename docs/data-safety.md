# Google Play Data safety answers

What to enter in Play Console → App content → Data safety, and why. Keep it in line with
[the privacy policy](privacy-policy.md) and check it again whenever a dependency is added.

## What the app contains (checked 28 October 2026)

- **Android libraries in the release build:** AndroidX, Jetpack Compose, Kotlin libraries, Ktor/OkHttp, Koin,
  Coil, DataStore, a QR code library, Stream's build of the open-source WebRTC library, AboutLibraries. No Google
  Play services, Firebase, analytics, advertising, attribution or crash-reporting SDK.
- **Network library (Go/Rust):** the mautrix clients (whatsmeow, messagix, gotd for Telegram, libsignal, Slack,
  libgm for Google Messages (built in but not offered), libgv for Google Voice (also not offered), linkedingo, Beeper's discordgo, twittermeow for X) and HowYouDoin's own Bluesky
  chat client.
  They talk only to the networks the user connects. OpenTelemetry appears only as its no-op tracing API (no
  exporter); `segmentio/asm` is a byte-handling library, not Segment analytics.
- **Release manifest:** no services or receivers from other companies; AndroidX's own startup and profile
  installer components stay on the device.
- **Hosts the app contacts:** the connected networks (and their login pages and media servers) and, in server mode,
  the user's own Matrix homeserver. Nothing of the developer's.

Re-check before each release: `./gradlew :composeApp:dependencies --configuration releaseRuntimeClasspath` and the
merged manifest in `composeApp/build/intermediates/merged_manifest/release/`.

## The answers

Google counts any data the app sends off the phone as "collected", even when it goes straight to another
company's service. A messaging client does send messages, so answering "no data collected" would not be
accurate and is what reviewers check. Declare what is sent, for app functionality only, and that none of it is
shared (sending data to the service the user chose, at their request, is not "sharing" in Google's terms).

**Does your app collect or share any of the required user data types?** Yes.

**Is all of the user data collected by your app encrypted in transit?** Yes (every connection uses TLS; the app
only allows plain HTTP to a local development server).

**Do you provide a way for users to request that their data is deleted?** Yes: disconnecting a network deletes its
data from the phone, and uninstalling the app deletes everything. The developer holds no data to delete.

| Data type | Collected | Shared | Processed ephemerally | Required or optional | Purpose |
|---|---|---|---|---|---|
| Messages → Other in-app messages | Yes | No | No | Required | App functionality |
| Photos and videos → Photos, Videos | Yes | No | No | Optional (only those the user sends) | App functionality |
| Audio → Voice or sound recordings | Yes | No | No | Optional (voice messages, calls) | App functionality |
| Files and docs | Yes | No | No | Optional (only those the user sends) | App functionality |
| Personal info → User IDs | Yes | No | No | Required (the network login) | App functionality |

Not collected: location, device contacts (the address book is never read; contact names come from the
networks), app activity, web browsing, app info and performance (crash reports stay on the phone and are only
shared by the user, by hand), device or other IDs, financial, health, calendar.

**Crash reports:** not collected. A report is only shared when the user chooses to send it through Android's share
sheet, to whoever they pick, which Google treats as user-initiated.

**Login backup:** the logins go into Android's own Google backup, end-to-end encrypted, never to the developer.

## Before publishing

- The privacy policy must be at a public URL. The repository is private, so publish `docs/privacy-policy.md`
  (for example with GitHub Pages, or by making the repository public) and enter that URL in Play Console.
- Play Console asks for a developer contact email; the policy's "open an issue" only works once the repository
  is public.
