// The call page, for both sides: the person invited by link (in their browser) and the HowYouDoin user (in the app,
// which opens it with ?host=1). The link's #fragment carries what no server may see: the encryption key (k) and, for
// the app, its token (t), LiveKit's address (u) and whether to start with the camera on (v).

import {
  ExternalE2EEKeyProvider,
  isE2EESupported,
  LocalTrackPublication,
  RemoteTrack,
  Room,
  RoomEvent,
  Track,
  VideoPresets,
} from "livekit-client";

declare global {
  interface Window {
    /** Set by the HowYouDoin app: closes its call screen. */
    HydCall?: { ended(): void };
  }
}

const TEXTS = {
  en: {
    inviteTitle: "You're invited to a call",
    inviteSeparate:
      "Someone using HowYouDoin invites you to a call. It's separate from WhatsApp, Messenger or any other app, " +
      "and it happens right here in your browser: nothing to install.",
    inviteEncrypted: "It's end-to-end encrypted: only the two of you can see and hear it.",
    invitePermissions: "Your browser will ask to use your microphone, and your camera if you join with video.",
    joinVideo: "Join with video",
    joinAudio: "Join with audio only",
    connecting: "Connecting…",
    waiting: "Waiting for them to open the link…",
    audioOnly: "Audio call",
    endedTitle: "The call has ended",
    endedText: "You can close this page.",
    goneTitle: "This call isn't available",
    unsupportedTitle: "This browser can't join",
    unsupportedText: "Encrypted calls need a recent Chrome, Edge, Firefox or Safari.",
    errorTitle: "Couldn't join the call",
    mic: "Microphone",
    camera: "Camera",
    leave: "Leave the call",
  },
  pl: {
    inviteTitle: "Zaproszenie do rozmowy",
    inviteSeparate:
      "Ktoś korzystający z HowYouDoin zaprasza Cię do rozmowy. Jest niezależna od WhatsAppa, Messengera i innych " +
      "aplikacji i odbywa się w przeglądarce: niczego nie trzeba instalować.",
    inviteEncrypted: "Jest szyfrowana end-to-end: widzicie i słyszycie ją tylko Wy dwoje.",
    invitePermissions: "Przeglądarka poprosi o dostęp do mikrofonu, a przy rozmowie wideo także do kamery.",
    joinVideo: "Dołącz z wideo",
    joinAudio: "Dołącz tylko z dźwiękiem",
    connecting: "Łączenie…",
    waiting: "Czekamy, aż otworzy link…",
    audioOnly: "Rozmowa głosowa",
    endedTitle: "Rozmowa zakończona",
    endedText: "Możesz zamknąć tę stronę.",
    goneTitle: "Ta rozmowa jest niedostępna",
    unsupportedTitle: "Ta przeglądarka nie może dołączyć",
    unsupportedText: "Rozmowy szyfrowane wymagają aktualnego Chrome, Edge, Firefoksa lub Safari.",
    errorTitle: "Nie udało się dołączyć",
    mic: "Mikrofon",
    camera: "Kamera",
    leave: "Zakończ rozmowę",
  },
};

type TextKey = keyof typeof TEXTS.en;
const lang = navigator.language.toLowerCase().startsWith("pl") ? "pl" : "en";
const t = (key: TextKey) => TEXTS[lang][key];

const ICONS = {
  mic: '<svg viewBox="0 0 24 24"><path d="M12 14a3 3 0 0 0 3-3V5a3 3 0 0 0-6 0v6a3 3 0 0 0 3 3Zm5-3a5 5 0 0 1-10 0H5a7 7 0 0 0 6 6.92V21h2v-3.08A7 7 0 0 0 19 11Z"/></svg>',
  camera:
    '<svg viewBox="0 0 24 24"><path d="M17 10.5V7a1 1 0 0 0-1-1H4a1 1 0 0 0-1 1v10a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-3.5l4 4v-11Z"/></svg>',
  leave:
    '<svg viewBox="0 0 24 24"><path d="M12 9c-1.6 0-3.15.25-4.6.72v3.1c0 .39-.23.74-.56.9-.98.49-1.87 1.12-2.66 1.85a1 1 0 0 1-1.41-.02L.29 13.08a1 1 0 0 1 0-1.41C3.34 8.78 7.46 7 12 7s8.66 1.78 11.71 4.67a1 1 0 0 1 0 1.41l-2.48 2.48a1 1 0 0 1-1.41.02 11.27 11.27 0 0 0-2.67-1.85 1 1 0 0 1-.56-.9v-3.1C15.15 9.25 13.6 9 12 9Z"/></svg>',
};

const $ = <T extends HTMLElement>(id: string) => document.getElementById(id) as T;

const fragment = new URLSearchParams(location.hash.slice(1));
const room = location.pathname.match(/^\/call\/([A-Za-z0-9_-]{22})\/?$/)?.[1];
const isHost = new URLSearchParams(location.search).has("host");

function show(section: "invite" | "call" | "message") {
  for (const id of ["invite", "call", "message"]) $(id).hidden = id !== section;
}

function message(title: string, text = "") {
  $("message-title").textContent = title;
  $("message-text").textContent = text;
  show("message");
}

function translate() {
  document.documentElement.lang = lang;
  document.querySelectorAll<HTMLElement>("[data-text]").forEach((el) => {
    el.textContent = t(el.dataset.text as TextKey);
  });
  for (const id of ["mic", "camera", "leave"] as const) {
    const button = $(id);
    button.innerHTML = ICONS[id];
    button.setAttribute("aria-label", t(id));
    button.title = t(id);
  }
}

async function start() {
  translate();
  const key = fragment.get("k");
  if (!room || !key) return message(t("goneTitle"));
  if (!isE2EESupported()) return message(t("unsupportedTitle"), t("unsupportedText"));

  if (isHost) {
    const token = fragment.get("t");
    const url = fragment.get("u");
    if (!token || !url) return message(t("goneTitle"));
    // Out of the address bar and history: the token and key aren't needed again
    history.replaceState(null, "", location.pathname + location.search);
    return join(url, token, key, fragment.get("v") === "1");
  }

  show("invite");
  const buttons = [$<HTMLButtonElement>("join-video"), $<HTMLButtonElement>("join-audio")];
  buttons.forEach((button, i) =>
    button.addEventListener("click", async () => {
      buttons.forEach((b) => (b.disabled = true));
      const response = await fetch(`/api/calls/${room}/join`, { method: "POST" }).catch(() => null);
      const body = await response?.json().catch(() => null);
      if (!response?.ok || !body?.token) {
        return message(response?.status === 404 ? t("goneTitle") : t("errorTitle"), body?.error ?? "");
      }
      join(body.url, body.token, key, i === 0);
    }),
  );
}

async function join(url: string, token: string, key: string, video: boolean) {
  show("call");
  const status = $("status");
  status.textContent = t("connecting");

  const keyProvider = new ExternalE2EEKeyProvider();
  await keyProvider.setKey(key);
  const call = new Room({
    adaptiveStream: true,
    dynacast: true,
    // Enough for a phone screen, light on phones' decoders and on data (what the call service charges for)
    videoCaptureDefaults: { resolution: VideoPresets.h540.resolution },
    e2ee: { keyProvider, worker: new Worker("/e2ee-worker.mjs", { type: "module" }) },
  });

  const remoteVideo = $<HTMLVideoElement>("remote");
  const localVideo = $<HTMLVideoElement>("local");
  const updateStatus = () => {
    const other = [...call.remoteParticipants.values()][0];
    if (!other) status.textContent = t("waiting");
    else status.textContent = remoteVideo.classList.contains("active") ? "" : t("audioOnly");
  };

  call
    .on(RoomEvent.TrackSubscribed, (track: RemoteTrack) => {
      if (track.kind === Track.Kind.Video) {
        track.attach(remoteVideo);
        remoteVideo.classList.add("active");
      } else {
        $("audio").appendChild(track.attach());
      }
      updateStatus();
    })
    .on(RoomEvent.TrackUnsubscribed, (track: RemoteTrack) => {
      track.detach().forEach((el) => {
        if (el === remoteVideo) remoteVideo.classList.remove("active");
        else el.remove();
      });
      updateStatus();
    })
    .on(RoomEvent.LocalTrackPublished, (publication: LocalTrackPublication) => {
      if (publication.track?.kind === Track.Kind.Video) {
        publication.track.attach(localVideo);
        localVideo.classList.add("active");
      }
    })
    .on(RoomEvent.LocalTrackUnpublished, (publication: LocalTrackPublication) => {
      if (publication.track?.kind === Track.Kind.Video) localVideo.classList.remove("active");
    })
    .on(RoomEvent.ParticipantConnected, updateStatus)
    // A call for two: when the other person leaves, it's over
    .on(RoomEvent.ParticipantDisconnected, () => call.disconnect())
    .on(RoomEvent.Disconnected, () => {
      message(t("endedTitle"), t("endedText"));
      window.HydCall?.ended();
    });

  try {
    await call.setE2EEEnabled(true);
    await call.connect(url, token);
    await call.localParticipant.setMicrophoneEnabled(true);
    if (video) await call.localParticipant.setCameraEnabled(true);
  } catch (e) {
    console.error(e);
    message(t("errorTitle"), String((e as Error)?.message ?? e));
    window.HydCall?.ended();
    return;
  }
  updateStatus();

  const mic = $("mic");
  const camera = $("camera");
  const sync = () => {
    mic.setAttribute("aria-pressed", String(call.localParticipant.isMicrophoneEnabled));
    camera.setAttribute("aria-pressed", String(call.localParticipant.isCameraEnabled));
  };
  sync();
  mic.addEventListener("click", async () => {
    await call.localParticipant.setMicrophoneEnabled(!call.localParticipant.isMicrophoneEnabled);
    sync();
  });
  camera.addEventListener("click", async () => {
    await call.localParticipant.setCameraEnabled(!call.localParticipant.isCameraEnabled);
    sync();
  });
  $("leave").addEventListener("click", () => call.disconnect());
}

start();
