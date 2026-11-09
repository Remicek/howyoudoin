# HowYouDoin — Architecture Guide

HowYouDoin is a **Compose Multiplatform** (Android + iOS) unified chat inbox, in the spirit of Beeper.
It follows the same Clean Architecture + MVI layout as Matcha: feature-based Gradle modules,
convention plugins in `build-logic/`, Koin for DI and type-safe Compose Navigation.

---

## How the networks are reached

There is no public API for personal WhatsApp or Messenger accounts. Like Beeper, HowYouDoin is a
**Matrix client**: it talks to a Matrix homeserver, and **mautrix bridges** running next to it
log in to WhatsApp / Messenger on the user's behalf and mirror every chat as a Matrix room.

```
 HowYouDoin app ──Matrix C-S API──▶ Homeserver (Synapse / Conduit / ...)
     │                              ▲
     └──bridgev2 provisioning API──▶│ mautrix-whatsapp  ──▶ WhatsApp
                                    │ mautrix-meta      ──▶ Messenger
```

| Concern | Where | API |
|---|---|---|
| Sign in | `feature/auth/data/MatrixAuthService` | `POST /_matrix/client/v3/login` (+ `.well-known` discovery) |
| Inbox + messages | `feature/chat/data/matrix/MatrixSyncEngine` | `/sync` long-poll, `/messages`, `/send`, `/receipt`, `/join` |
| Which network a room is | `feature/chat/data/mappers/RoomMapper` | `m.bridge` state `protocol.id`, else ghost user prefix (`whatsapp_`, `meta_`) |
| Connect an account | `feature/networks/data/ProvisioningBridgeRepository` | `/_matrix/provision/v3/whoami`, `login/start`, `login/step`, `logout` |

Bridge logins are a server-driven state machine (`BridgeLoginStep`):

- `DisplayAndWait`: WhatsApp QR code (rendered with qrose) or pairing code; the app long-polls until the phone confirms.
- `UserInput`: phone number, 2FA code, etc.
- `Cookies`: Messenger. The user logs in on messenger.com in a web view (`CookieCaptureWebView`, expect/actual) and the required cookies go to the bridge.
- `Complete`: done.

Because the UI renders steps generically, adding Telegram / Signal / ... is mostly a matter of
flipping `ChatNetwork.isSupported` once their bridge is deployed.

### Connection problems and offline

Networks connected on the phone are watched by `ConnectionMonitor` (`core/domain/ondevice`). It turns each client's
state into `ConnectionProblem`s that the inbox shows (failed, logged out, still reconnecting after a few minutes,
expired login, unverified account) and gives the inbox's Reconnect button something to do:

- Reconnect starts the network over. If it fails again, or isn't back within a minute
  (`USER_RECONNECT_WAIT_MILLIS`), the problem comes back with `reconnectDidntHelp`. The inbox then shows "Reconnecting
  X didn't help", which opens Networks (`NetworksRoutes.List(troubleshoot = network)`) with steps to disconnect and
  connect again.
- `OnlineReconnector` (Android) restarts networks still down a few seconds after the phone is back online.

`InternetConnection` (`core/domain/connectivity`) says whether the phone is online: `AndroidInternetConnection` follows
the default network's validated internet capability; iOS uses `AlwaysOnline` for now. Offline, the inbox shows an
offline banner instead of problem cards, chats read from what's saved on the phone, and the conversation screen
reminds the user they're offline instead of sending (the draft stays).

### Demo mode

"Try demo mode" on the login screen stores a session with `isDemo = true`.
`SessionAwareChatRepository` / `SessionAwareBridgeRepository` then route to
`DemoChatRepository` / `DemoBridgeRepository`, which serve sample WhatsApp + Messenger chats and
simulated login flows. No server needed.

### Running against real bridges

1. Run a homeserver with [mautrix-whatsapp](https://docs.mau.fi/bridges/go/whatsapp/) and
   [mautrix-meta](https://docs.mau.fi/bridges/go/meta/) (bridgev2, `network.mode: messenger`).
2. Expose each bridge's provisioning API (`/_matrix/provision`) over HTTPS, e.g. via a reverse proxy path.
   Keep `provisioning.allow_matrix_auth: true` so the user's Matrix access token is accepted.
3. Sign in to HowYouDoin with your Matrix account, open **Networks** and enter that URL when connecting.
4. Bridge encryption must be **off** for now (see TODO).

---

## Module graph

```
composeApp
 ├── core:domain          Result/DataError, AuthInfo, SessionStorage, ChatNetwork
 ├── core:data            DataStore session, Ktor HttpClientFactory, safeCall
 ├── core:presentation    UiText, ObserveAsEvents, TimestampFormatter
 ├── core:designsystem    HowYouDoinTheme, network colours, ChatAvatar, NetworkBadge
 ├── feature:auth:data          MatrixAuthService, HomeserverResolver
 ├── feature:auth:presentation  Login
 ├── feature:chat:domain        Chat, Message, ChatRepository
 ├── feature:chat:data          Matrix sync engine, mappers, demo repo
 ├── feature:chat:presentation  Inbox, Conversation
 ├── feature:networks:domain        BridgeRepository, BridgeLoginStep
 ├── feature:networks:data          bridgev2 provisioning client, demo repo
 └── feature:networks:presentation  Networks list, Connect flow, QR, cookie web view
```

Dependencies only flow inward (presentation → domain ← data). Feature modules never depend on
each other; `composeApp` wires navigation between them with lambdas.

---

## MVI

Every screen package contains `XState`, `XAction`, optionally `XEvent`, `XViewModel` and `XScreen`
(with an `XRoot` that injects the ViewModel and intercepts navigation actions):

```
  UI ──(Action)──▶ ViewModel ──(StateFlow<State>)──▶ UI
                       └──(Channel<Event>)──▶ UI   one-shot: snackbar, navigate
```

ViewModels start observing in `state.onStart { if (!hasLoadedInitialData) ... }` and expose
`stateIn(WhileSubscribed(5_000))`, same as Matcha.

---

## TODO

- More networks: Telegram, Signal, Instagram, Discord, Slack, LinkedIn (enum entries exist, `isSupported = false`).
- End-to-end encryption (matrix-rust-sdk / vodozemac). Encrypted events currently show as "Encrypted message".
- Persist the sync cache + `next_batch` (Room/SQLDelight) for instant cold starts.
- Media: authenticated `mxc://` avatars and images, sending attachments.
- Reactions, replies, typing indicators, read receipts display.
- Push notifications (Matrix pusher → FCM/APNs).
- Cookie sources other than plain cookies (local storage, request headers) in `CookieCaptureWebView`.
