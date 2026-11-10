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
  de: {
    inviteTitle: "Du bist zu einem Anruf eingeladen",
    inviteSeparate: "Jemand, der HowYouDoin nutzt, lädt dich zu einem Anruf ein. Er ist unabhängig von WhatsApp, Messenger und anderen Apps und läuft direkt hier im Browser: Du musst nichts installieren.",
    inviteEncrypted: "Er ist Ende-zu-Ende-verschlüsselt: Nur ihr beide könnt ihn sehen und hören.",
    invitePermissions: "Dein Browser fragt nach dem Mikrofon und, wenn du mit Video beitrittst, nach der Kamera.",
    joinVideo: "Mit Video beitreten",
    joinAudio: "Nur mit Audio beitreten",
    connecting: "Verbindung wird hergestellt…",
    waiting: "Warten, bis der Link geöffnet wird…",
    audioOnly: "Audioanruf",
    endedTitle: "Der Anruf ist beendet",
    endedText: "Du kannst diese Seite schließen.",
    goneTitle: "Dieser Anruf ist nicht verfügbar",
    unsupportedTitle: "Dieser Browser kann nicht beitreten",
    unsupportedText: "Verschlüsselte Anrufe brauchen ein aktuelles Chrome, Edge, Firefox oder Safari.",
    errorTitle: "Beitritt fehlgeschlagen",
    mic: "Mikrofon",
    camera: "Kamera",
    leave: "Anruf verlassen",
  },
  es: {
    inviteTitle: "Te han invitado a una llamada",
    inviteSeparate: "Alguien que usa HowYouDoin te invita a una llamada. Es independiente de WhatsApp, Messenger y cualquier otra app, y ocurre aquí mismo, en tu navegador: no hay nada que instalar.",
    inviteEncrypted: "Está cifrada de extremo a extremo: solo vosotros dos podéis verla y oírla.",
    invitePermissions: "Tu navegador pedirá usar el micrófono, y la cámara si te unes con vídeo.",
    joinVideo: "Unirse con vídeo",
    joinAudio: "Unirse solo con audio",
    connecting: "Conectando…",
    waiting: "Esperando a que abra el enlace…",
    audioOnly: "Llamada de voz",
    endedTitle: "La llamada ha terminado",
    endedText: "Puedes cerrar esta página.",
    goneTitle: "Esta llamada no está disponible",
    unsupportedTitle: "Este navegador no puede unirse",
    unsupportedText: "Las llamadas cifradas necesitan una versión reciente de Chrome, Edge, Firefox o Safari.",
    errorTitle: "No se pudo unir a la llamada",
    mic: "Micrófono",
    camera: "Cámara",
    leave: "Salir de la llamada",
  },
  fr: {
    inviteTitle: "Vous êtes invité à un appel",
    inviteSeparate: "Quelqu'un qui utilise HowYouDoin vous invite à un appel. Il est indépendant de WhatsApp, Messenger et de toute autre appli, et se passe ici même, dans votre navigateur : rien à installer.",
    inviteEncrypted: "Il est chiffré de bout en bout : vous seuls pouvez le voir et l'entendre.",
    invitePermissions: "Votre navigateur demandera l'accès au micro, et à la caméra si vous rejoignez en vidéo.",
    joinVideo: "Rejoindre en vidéo",
    joinAudio: "Rejoindre en audio seulement",
    connecting: "Connexion…",
    waiting: "En attente de l'ouverture du lien…",
    audioOnly: "Appel audio",
    endedTitle: "L'appel est terminé",
    endedText: "Vous pouvez fermer cette page.",
    goneTitle: "Cet appel n'est pas disponible",
    unsupportedTitle: "Ce navigateur ne peut pas rejoindre l'appel",
    unsupportedText: "Les appels chiffrés nécessitent une version récente de Chrome, Edge, Firefox ou Safari.",
    errorTitle: "Impossible de rejoindre l'appel",
    mic: "Micro",
    camera: "Caméra",
    leave: "Quitter l'appel",
  },
  pt: {
    inviteTitle: "Você foi convidado para uma chamada",
    inviteSeparate: "Alguém que usa o HowYouDoin está convidando você para uma chamada. Ela é separada do WhatsApp, do Messenger e de qualquer outro app, e acontece aqui mesmo, no seu navegador: não é preciso instalar nada.",
    inviteEncrypted: "Ela tem criptografia de ponta a ponta: só vocês dois podem vê-la e ouvi-la.",
    invitePermissions: "Seu navegador vai pedir para usar o microfone, e a câmera se você entrar com vídeo.",
    joinVideo: "Entrar com vídeo",
    joinAudio: "Entrar só com áudio",
    connecting: "Conectando…",
    waiting: "Aguardando a pessoa abrir o link…",
    audioOnly: "Chamada de voz",
    endedTitle: "A chamada terminou",
    endedText: "Você pode fechar esta página.",
    goneTitle: "Esta chamada não está disponível",
    unsupportedTitle: "Este navegador não consegue entrar",
    unsupportedText: "Chamadas criptografadas precisam de uma versão recente do Chrome, Edge, Firefox ou Safari.",
    errorTitle: "Não foi possível entrar na chamada",
    mic: "Microfone",
    camera: "Câmera",
    leave: "Sair da chamada",
  },
  ru: {
    inviteTitle: "Вас приглашают на звонок",
    inviteSeparate: "Пользователь HowYouDoin приглашает вас на звонок. Он не связан с WhatsApp, Messenger и другими приложениями и проходит прямо здесь, в браузере: ничего устанавливать не нужно.",
    inviteEncrypted: "Звонок защищён сквозным шифрованием: видеть и слышать его можете только вы двое.",
    invitePermissions: "Браузер попросит доступ к микрофону, а если вы подключитесь с видео — и к камере.",
    joinVideo: "Подключиться с видео",
    joinAudio: "Подключиться только со звуком",
    connecting: "Подключение…",
    waiting: "Ждём, пока собеседник откроет ссылку…",
    audioOnly: "Голосовой звонок",
    endedTitle: "Звонок завершён",
    endedText: "Эту страницу можно закрыть.",
    goneTitle: "Этот звонок недоступен",
    unsupportedTitle: "Этот браузер не может подключиться",
    unsupportedText: "Для зашифрованных звонков нужен свежий Chrome, Edge, Firefox или Safari.",
    errorTitle: "Не удалось подключиться",
    mic: "Микрофон",
    camera: "Камера",
    leave: "Завершить звонок",
  },
  tr: {
    inviteTitle: "Bir aramaya davet edildiniz",
    inviteSeparate: "HowYouDoin kullanan biri sizi bir aramaya davet ediyor. Arama WhatsApp, Messenger ve diğer uygulamalardan bağımsızdır ve doğrudan burada, tarayıcınızda gerçekleşir: hiçbir şey yüklemeniz gerekmez.",
    inviteEncrypted: "Arama uçtan uca şifrelidir: yalnızca ikiniz görebilir ve duyabilirsiniz.",
    invitePermissions: "Tarayıcınız mikrofonu, görüntülü katılırsanız kamerayı da kullanmak için izin isteyecek.",
    joinVideo: "Görüntülü katıl",
    joinAudio: "Yalnızca sesli katıl",
    connecting: "Bağlanıyor…",
    waiting: "Bağlantıyı açması bekleniyor…",
    audioOnly: "Sesli arama",
    endedTitle: "Arama sona erdi",
    endedText: "Bu sayfayı kapatabilirsiniz.",
    goneTitle: "Bu arama kullanılamıyor",
    unsupportedTitle: "Bu tarayıcı katılamıyor",
    unsupportedText: "Şifreli aramalar için güncel bir Chrome, Edge, Firefox veya Safari gerekir.",
    errorTitle: "Aramaya katılınamadı",
    mic: "Mikrofon",
    camera: "Kamera",
    leave: "Aramadan ayrıl",
  },
  ja: {
    inviteTitle: "通話に招待されています",
    inviteSeparate: "HowYouDoin を使っている人が、あなたを通話に招待しています。WhatsApp や Messenger などのアプリとは別の通話で、このブラウザ上で行われます。インストールは不要です。",
    inviteEncrypted: "エンドツーエンドで暗号化されており、見聞きできるのはお二人だけです。",
    invitePermissions: "ブラウザからマイクの使用、ビデオで参加する場合はカメラの使用の許可を求められます。",
    joinVideo: "ビデオで参加",
    joinAudio: "音声のみで参加",
    connecting: "接続中…",
    waiting: "相手がリンクを開くのを待っています…",
    audioOnly: "音声通話",
    endedTitle: "通話は終了しました",
    endedText: "このページを閉じてかまいません。",
    goneTitle: "この通話は利用できません",
    unsupportedTitle: "このブラウザでは参加できません",
    unsupportedText: "暗号化通話には最新の Chrome、Edge、Firefox、Safari が必要です。",
    errorTitle: "通話に参加できませんでした",
    mic: "マイク",
    camera: "カメラ",
    leave: "通話を終了",
  },
  hi: {
    inviteTitle: "आपको एक कॉल पर बुलाया गया है",
    inviteSeparate: "HowYouDoin इस्तेमाल करने वाला कोई व्यक्ति आपको एक कॉल पर बुला रहा है। यह WhatsApp, Messenger या किसी दूसरे ऐप से अलग है और यहीं आपके ब्राउज़र में होती है: कुछ भी इंस्टॉल नहीं करना है।",
    inviteEncrypted: "यह एंड-टू-एंड एन्क्रिप्टेड है: इसे सिर्फ़ आप दोनों देख और सुन सकते हैं।",
    invitePermissions: "आपका ब्राउज़र माइक्रोफ़ोन, और वीडियो के साथ जुड़ने पर कैमरा इस्तेमाल करने की अनुमति माँगेगा।",
    joinVideo: "वीडियो के साथ जुड़ें",
    joinAudio: "सिर्फ़ ऑडियो के साथ जुड़ें",
    connecting: "कनेक्ट हो रहा है…",
    waiting: "उनके लिंक खोलने का इंतज़ार…",
    audioOnly: "ऑडियो कॉल",
    endedTitle: "कॉल खत्म हो गई",
    endedText: "आप यह पेज बंद कर सकते हैं।",
    goneTitle: "यह कॉल उपलब्ध नहीं है",
    unsupportedTitle: "यह ब्राउज़र नहीं जुड़ सकता",
    unsupportedText: "एन्क्रिप्टेड कॉल के लिए नया Chrome, Edge, Firefox या Safari चाहिए।",
    errorTitle: "कॉल से नहीं जुड़ सके",
    mic: "माइक्रोफ़ोन",
    camera: "कैमरा",
    leave: "कॉल छोड़ें",
  },
  id: {
    inviteTitle: "Anda diundang ke panggilan",
    inviteSeparate: "Seseorang yang memakai HowYouDoin mengundang Anda ke panggilan. Panggilan ini terpisah dari WhatsApp, Messenger, atau aplikasi lain, dan berlangsung di browser ini: tidak perlu memasang apa pun.",
    inviteEncrypted: "Panggilan terenkripsi end-to-end: hanya kalian berdua yang bisa melihat dan mendengarnya.",
    invitePermissions: "Browser akan meminta izin memakai mikrofon, dan kamera jika Anda bergabung dengan video.",
    joinVideo: "Gabung dengan video",
    joinAudio: "Gabung dengan audio saja",
    connecting: "Menghubungkan…",
    waiting: "Menunggu mereka membuka tautan…",
    audioOnly: "Panggilan suara",
    endedTitle: "Panggilan telah berakhir",
    endedText: "Anda bisa menutup halaman ini.",
    goneTitle: "Panggilan ini tidak tersedia",
    unsupportedTitle: "Browser ini tidak bisa bergabung",
    unsupportedText: "Panggilan terenkripsi memerlukan Chrome, Edge, Firefox, atau Safari versi terbaru.",
    errorTitle: "Tidak bisa bergabung ke panggilan",
    mic: "Mikrofon",
    camera: "Kamera",
    leave: "Tinggalkan panggilan",
  },
  ar: {
    inviteTitle: "أنت مدعو إلى مكالمة",
    inviteSeparate: "يدعوك شخص يستخدم HowYouDoin إلى مكالمة. إنها منفصلة عن واتساب وماسنجر وأي تطبيق آخر، وتجري هنا في متصفحك: لا حاجة لتثبيت أي شيء.",
    inviteEncrypted: "المكالمة مشفرة من طرف إلى طرف: لا يمكن لأحد غيركما رؤيتها أو سماعها.",
    invitePermissions: "سيطلب متصفحك استخدام الميكروفون، والكاميرا إذا انضممت بالفيديو.",
    joinVideo: "الانضمام بالفيديو",
    joinAudio: "الانضمام بالصوت فقط",
    connecting: "جارٍ الاتصال…",
    waiting: "في انتظار أن يفتح الرابط…",
    audioOnly: "مكالمة صوتية",
    endedTitle: "انتهت المكالمة",
    endedText: "يمكنك إغلاق هذه الصفحة.",
    goneTitle: "هذه المكالمة غير متاحة",
    unsupportedTitle: "لا يمكن لهذا المتصفح الانضمام",
    unsupportedText: "تحتاج المكالمات المشفرة إلى إصدار حديث من Chrome أو Edge أو Firefox أو Safari.",
    errorTitle: "تعذر الانضمام إلى المكالمة",
    mic: "الميكروفون",
    camera: "الكاميرا",
    leave: "مغادرة المكالمة",
  },
};

type TextKey = keyof typeof TEXTS.en;
type Lang = keyof typeof TEXTS;
/** The browser's first language the page has, e.g. "pt-BR" → pt; "in" is Indonesian's old code. */
const lang: Lang =
  navigator.languages
    .map((l) => l.toLowerCase().split("-")[0].replace(/^in$/, "id"))
    .find((l): l is Lang => l in TEXTS) ?? "en";
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
  document.documentElement.dir = lang === "ar" ? "rtl" : "ltr";
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
  } catch (e) {
    console.error("Couldn't join", e);
    call.removeAllListeners(RoomEvent.Disconnected);
    await call.disconnect();
    message(t("errorTitle"), String((e as Error)?.message ?? e));
    window.HydCall?.ended();
    return;
  }
  // Without the camera (refused, busy, or the app went to the background while it started) it's an audio call
  if (video) {
    await call.localParticipant
      .setCameraEnabled(true)
      .catch((e) => console.warn("Continuing without the camera", e));
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
