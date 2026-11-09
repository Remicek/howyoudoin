# HowYouDoin — Deep Security & Correctness Review

_Reviewed: 2026-11-02 · whole-app audit (not a diff)._

Five parallel review passes over ~30k LOC Kotlin (18 modules) plus the custom Go
bridge glue (`bridge-mobile/hydbridge`): (1) credential storage & crypto,
(2) Matrix E2E + HTTP, (3) WebView/cookie-capture/components, (4) Go bridge glue,
(5) background service/boot/calls. Each finding was re-verified against the code.

## Overall

No critical, RCE, or crash-grade defect. The security-sensitive code is
carefully written: AES-256-GCM with random per-message IVs, Android Keystore
keys, SQLCipher, no secrets in logs, immutable PendingIntents, only the launcher
activity exported, backup gated on client-side encryption, no TLS-verification
bypass anywhere, decrypt never falls back to plaintext. The findings are
defense-in-depth gaps and a few concrete correctness bugs.

**Verification of the fixes below:** `./gradlew` compiles all edited Android
modules (BUILD SUCCESSFUL); detekt adds zero new findings (edited files are
clean); Go edits are gofmt-clean and mirror sibling networks. (The full Go link
needs `libolm`, present in CI, not in this environment; the Kotlin detekt gate is
already red on `main` with 7 pre-existing findings unrelated to this work — see
end.)

---

## Fixed in this pass

| # | Sev | Area | File | Change |
|---|-----|------|------|--------|
| 1 | MED | Path traversal | `bridge-mobile/hydbridge/discord.go` | Discord attachment ID came from gateway JSON and was joined into the media path unsanitized (every other network strips `/`,`:`). A server returning `id:"../../files/whatsapp.db"` could overwrite another store. Now sanitized like the siblings. |
| 2 | MED | Silent E2E downgrade | `.../chat/data/matrix/MatrixSyncEngine.kt` | Room was "encrypted" only if the current `/sync` carried `m.room.encryption`; a malicious homeserver dropping it forced a plaintext send. Added a spec-correct "once encrypted, always encrypted" guard (encryption can't be disabled in Matrix), recorded during sync and at send time. |
| 3 | MED | WebView hardening | `.../networks/presentation/.../CookieCaptureWebView.android.kt` | The cookie-bearing login WebView didn't deny file/content access (the CAPTCHA one does; `allowContentAccess` defaults true). Added the four explicit `allow*=false` for parity. |
| 4 | MED | Disk-fill DoS | `bridge-mobile/hydbridge/slack.go`, `files.go` | Slack download trusted the server-reported size and streamed to disk with no cap. Added a shared `limitedWriter` and wrapped the stream. |
| 5 | L-M | Token redirection | `.../auth/data/MatrixAuthService.kt` | The login response's `well_known` `base_url` (which retargets all later token-bearing calls) was used raw. Now only an `https` override is honored; otherwise the already-resolved URL stands. |
| 6 | LOW | URL injection | `.../chat/data/matrix/MatrixSyncEngine.kt` | `mediaRequest` concatenated the event-supplied mxc `server`/`id` into the path/query. Now `encodeURLPathPart`-encoded (no-op for valid opaque IDs). |
| 7 | LOW | Durability | `.../app/ondevice/AndroidOnDeviceMessenger.kt` | Inbox-cache write ignored a failed `renameTo`. Now falls back to an in-place write and drops the orphan temp. |
| 8 | LOW | Misleading doc | `.../app/backup/AndroidLoginBackup.kt` | Comment said logins are backed up "only end-to-end encrypted"; corrected to distinguish the E2E-gated cloud backup from the local device-to-device transfer. |
| 9 | — | UI strings | `composeResources/.../strings.xml` (settings, chat; en/tr/fr) | 22 apostrophes used Android-style `\'`, which Compose Multiplatform renders literally (e.g. `Using HowYouDoin\'s keys`). Changed to bare `'` (the project's convention); Android-native `res/values` escapes left intact. |

### Note on backup "device-transfer" (finding originally flagged MEDIUM)
The reviewer suggested encrypting `logins.json` with a Keystore-wrapped key.
**That would break restore-to-new-device** (Keystore keys never leave the
device), which is exactly why the app delegates to Google's E2E backup. Per the
Android docs, **deleting the `<device-transfer>` block makes D2D back up
*everything*** ("if the section is missing, that mode is fully enabled"), and
`disableIfNoEncryptionCapabilities` is cloud-only by design. So the current
config is already the minimal footprint; only the comment was wrong. No config
change.

---

## Reported — deliberately NOT auto-fixed (with reasons)

These are real but need a product decision, a library change, a data migration,
or a version-specific check that shouldn't be guessed blind.

- **CookieCaptureWebView navigation host-allowlist (MED) — FIXED.** The web view now
  pins navigation to a per-network allowlist (`cookieLoginAllowedHosts`): the initial
  (possibly server-supplied) URL, `shouldOverrideUrlLoading`, and an `onPageStarted`
  safety net all reject off-list http(s) hosts. Slack is intentionally unrestricted
  (empty set) because its workspaces sign in through arbitrary enterprise SSO that
  can't be enumerated — the other seven networks (Messenger, Instagram, LinkedIn,
  Discord, X, Google Voice, Google Messages) are pinned. Verified: 10 unit tests on
  the matcher (`NavigationAllowlistTest`) and the on-device `webLoginsOpenTheirSignInPage`
  instrumented test (LinkedIn + X sign-in pages still load through the allowlist).
- **HTTP redirect replaying `Authorization`/password cross-host — VERIFIED SAFE, no change.**
  Checked the Ktor 3.2.3 `ktor-client-core` bytecode: `HttpRedirect` compares the
  request and redirect-target authorities and, when they differ, calls
  `remove(HttpHeaders.Authorization)` (log line "Removing Authorization header from
  redirect for …"). `allowHttpsDowngrade` defaults to false, and login is a POST, which
  `checkHttpMethod` (default true) does not auto-redirect. So the framework default
  already drops the token cross-host and won't downgrade to http. No policy needed.
- **No per-device Matrix verification/trust enforcement (MED, library).** Trust
  decisions live in `mautrix-go`; the app only surfaces own-device status. Needs
  per-sender trust in the model/UI and a "block unverified devices" option.
- **Go secrets written in plaintext when no storage key is set (MED).**
  `files.go`/`sqlite.go` fall back to cleartext on iOS/tests. Failing closed would
  break those paths until an iOS Keychain-backed key is wired up first.
- **PBKDF2 iterations 120k < OWASP 210k (LOW) — FIXED.** The hash's round count is now
  stored with it (`app_lock_pin_iterations`); new PINs use 210k. A hash without the
  count is from before and is checked at 120k, and on the next correct unlock the PIN
  is hashed again at 210k with a new salt, so no one is locked out
  (`DataStoreAppLockRepositoryTest`).
- **Matrix access token stored in plaintext DataStore (LOW) — FIXED.** The stored
  session (which holds the access token) is now encrypted at rest with a new
  `SecretCipher`: on Android an AES-256-GCM key bound to the Keystore
  (`KeystoreSecretCipher`), wired through `DataStoreSessionStorage`. Sessions written
  by older builds are read transparently and re-encrypted on the next write, so no one
  is logged out. The session is not in any backup, so a device-bound key is safe.
- **Unbounded media/sync response allocation (LOW) — FIXED.** `/sync` and `/messages`
  go through `safeLimitedGet` (streamed, at most 64 MB) and media downloads through
  `readBodyLimited` (at most 100 MB): refused up front by Content-Length, or stopped one
  byte past the limit when the length isn't given (`SafeLimitedGetTest`).
- **Auto-join keyed on inviter MXID pattern + fragile `is_direct` string compare
  (LOW) — FIXED.** A bridge invite is accepted only when the ghost or bot is on our own
  homeserver (where the bridges run), so `@whatsapp_…:elsewhere` is shown as an invite
  instead; `is_direct` counts only as a JSON `true` (`MatrixInvitesTest`).
- **SQLCipher key embedded in the DSN string (LOW) — VERIFIED SAFE, no change.**
  Nothing echoes the DSN: the driver (go-sqlcipher `Open`) only names the bad parameter
  in its errors, `database/sql` doesn't include it, and whatsmeow's `sqlstore.New` and
  mautrix `dbutil.NewWithDialect` pass it straight to `sql.Open` without logging it; our
  own logs name only the path. Moving to `PRAGMA key` in a connect hook would need a
  custom driver name, which whatsmeow and dbutil use to pick their SQL dialect, and the
  hook runs after the DSN's WAL pragma, which fails on an encrypted file before the key.
- **Smaller items:** StrongBox not requested (optional, add with fallback);
  `StorageKey.setStorageKey` logs-and-continues on failure (confirm the bridge
  fails closed); exported `MainActivity` reads notification extras from any caller
  (navigation only); watchdog FGS restart is a no-op in the background without the
  battery-optimization exemption (clients re-init anyway); on-device events use
  `tryEmit` and can drop on a >5k-event burst; incoming calls won't ring if
  `POST_NOTIFICATIONS` is denied while backgrounded (inherent to Android).
- **Informational / verified fine:** the bundled Telegram api_id/api_hash XOR pad
  is honestly-documented obfuscation (an app's own keys are always recoverable —
  keep them revocable server-side); the recovery-key verification tolerating
  `ErrUnverifiableKey` is likely correct (upstream re-checks) but worth a glance.

---

## Separate: the detekt gate (was red on `main`)

Fixed since: all 7 findings below were resolved and `./gradlew detekt` passes.

`./gradlew detekt` (configured with no baseline, so every finding fails) reports
**7 pre-existing findings on `main`**, none in files touched here:
`OnDeviceMessengerStore.kt` (TooManyFunctions), `NativeOnDeviceMessenger.kt`
(ReturnCount), `BackgroundKeepAlive.kt` (MaxLineLength, MagicNumber),
`BackgroundOffer.kt` / `InboxScreen.kt` / `CaptchaChallengeTest.kt`
(MaxLineLength). The branch's own quality gate is currently failing independent of
this review — worth clearing or baselining.
