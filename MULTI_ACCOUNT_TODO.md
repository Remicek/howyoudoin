# Several accounts of one network: what's left

Branch `feature/multi-account` (not merged). Up to 3 accounts of each network connected on the phone (Messenger,
WhatsApp, Instagram, Telegram, Signal, Slack, Google Messages, Bluesky, LinkedIn, Discord, X, Google Voice). Matrix
keeps its one account.

## How it works

- An account is a network plus a slot (`AccountRef`): slot 0 is the account the app always had, with its folders and
  chat IDs unchanged; slots 1 and 2 live in `on_device_<network>_2` / `_3` and their chats are `wadevice2:…`,
  `wadevice3:…`.
- Each slot has its own native client, inbox store and saved login (`ExtraAccountClients.kt`, `ChatDataModule`).
  A 2nd or 3rd linked-device slot (WhatsApp, Signal, Telegram, Google Messages) that never started logging in isn't
  opened at launch (`account_in_use` marker).
- Names and colours: `AccountLabels` (DataStore). Tags appear only once a network has two accounts logged in
  (`AccountTags`): chat rows, the account filter row under the network's, the chat header, "Replying as …",
  notifications, the problem banner, and the new-message account step.
- Connection problems, Reconnect and Disconnect are per account (`ConnectionMonitor` keyed by `AccountRef`).

## Tested (Pixel_10 emulator, 2026-11-10)

- Existing accounts unchanged after the update: the same chats, no tags with one account per network.
- Networks screen: one row per account with a menu, "Add another account · 2 of 3".
- Add another WhatsApp: its own QR code from its own folder, the first account stayed connected.
- Launch time not worse than `main` (about 9 s vs 13 s on this loaded emulator).
- Unit tests: `AccountSlotsTest`, `ConnectionMonitorTest.twoAccountsOfOneNetworkAreWatchedApart`, `ChatIdPrefixTest`.

## Needs a second real account

- [x] Log in a second account: done 2026-11-10 on the Galaxy A16, a second Messenger account; both connected, named
      and coloured on the networks screen.
- [x] With two accounts (A16, 2026-11-10, Messenger "djdjd" and "bahaba"): tags on chat rows, the "2" on the
      Messenger filter, the account chips filtering each account's chats, the header, "Replying as bahaba", a message
      sent from bahaba arriving on djdjd, and its notification titled "Mieszko Chlebek · djdjd". Fixed on the way: the
      chat header wrapped into a five-line column; it's now one line each, account name first.
- [x] New message on a network with two accounts: "Which Messenger account writes?", then that account's contacts;
      picking one opened that account's existing chat (A16, 2026-11-10).
- [ ] Disconnect the first account while the second stays: the second keeps working; adding again reuses slot 0.
- [x] Two cookie logins (Messenger) one after the other: the login page started signed out and both stay
      connected (A16, 2026-11-10).

## Done after the first round (2026-11-10)

- **Login backup** saves every account: cookie logins and Telegram's session files per slot, plus the accounts'
  names and colours. A network's first account keeps its old key in the file, so backups made before this still
  restore (`SavedLoginsTest`). Restoring a network brings back all its saved accounts.
- **The same account twice:** each store's own account ID ("self") is compared within a network; the newer login is
  disconnected and the user is told (`StoreDuplicateAccounts`, `StoreDuplicateAccountsTest`). Messenger's unlink only
  disconnects, so the first login keeps working.
- **Account count** on a network's icon in the inbox filter row when it has more than one account (also read out
  by TalkBack: "WhatsApp, 2 accounts").

## Not done

- **Server bridge logins** are unchanged (they already allowed several).

## Emulator note

The Pixel_10 emulator showed "isn't responding" dialogs on 2026-11-10 (also on `main` at 15:15, and in another
app). A thread dump during one had the app's main and render threads idle while the emulator's graphics service
used 64% CPU in the kernel: the emulator, not the app. Check on a phone anyway.
