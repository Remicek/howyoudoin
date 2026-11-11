// The call page, for both sides: the person invited by link (in their browser) and the HowYouDoin user (in the app,
// which opens it with ?host=1). The link's #fragment carries what no server may see: the encryption key (k) and, for
// the app, the call's token (t) and whether to start with the camera on (v).
//
// Media goes through Cloudflare Realtime SFU over two WebRTC connections, one sending and one receiving, every frame
// encrypted by e2ee-worker.ts. The worker's CallRoom says who's in the call and makes the SFU requests (over
// /api/calls/<room>/ws).

declare global {
  interface Window {
    /** Set by the HowYouDoin app: closes its call screen. */
    HydCall?: { ended(): void };
    RTCRtpScriptTransform?: new (worker: Worker, options: unknown) => unknown;
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
    videoUsedUp: "No video minutes left: the call goes on with audio.",
    videoEndsIn: "Video stops in {n} min; the call goes on with audio.",
    endsIn: "The call ends in {n} min",
    endedLimit: "This call reached its time limit.",
    inviteTitleGroup: "You're invited to a group call",
    inviteEncryptedGroup: "It's end-to-end encrypted: only the people in the call can see and hear it.",
    waitingGroup: "Waiting for others to open the link…",
    fullTitle: "This call is full",
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
    videoUsedUp: "Skończyły się minuty wideo: rozmowa trwa dalej z samym dźwiękiem.",
    videoEndsIn: "Wideo wyłączy się za {n} min; rozmowa potrwa dalej z samym dźwiękiem.",
    endsIn: "Rozmowa skończy się za {n} min",
    endedLimit: "Ta rozmowa osiągnęła limit czasu.",
    inviteTitleGroup: "Zaproszenie do rozmowy grupowej",
    inviteEncryptedGroup: "Jest szyfrowana end-to-end: widzą i słyszą ją tylko osoby w rozmowie.",
    waitingGroup: "Czekamy, aż inni otworzą link…",
    fullTitle: "Ta rozmowa jest pełna",
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
    videoUsedUp: "Keine Videominuten mehr: Der Anruf geht nur mit Ton weiter.",
    videoEndsIn: "Das Video endet in {n} Min.; der Anruf geht mit Ton weiter.",
    endsIn: "Der Anruf endet in {n} Min.",
    endedLimit: "Dieser Anruf hat sein Zeitlimit erreicht.",
    inviteTitleGroup: "Du bist zu einem Gruppenanruf eingeladen",
    inviteEncryptedGroup: "Er ist Ende-zu-Ende-verschlüsselt: Nur die Personen im Anruf können ihn sehen und hören.",
    waitingGroup: "Warten, bis andere den Link öffnen…",
    fullTitle: "Dieser Anruf ist voll",
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
    videoUsedUp: "No quedan minutos de vídeo: la llamada sigue solo con audio.",
    videoEndsIn: "El vídeo se detiene en {n} min; la llamada sigue con audio.",
    endsIn: "La llamada termina en {n} min",
    endedLimit: "Esta llamada alcanzó su límite de tiempo.",
    inviteTitleGroup: "Te han invitado a una llamada de grupo",
    inviteEncryptedGroup: "Está cifrada de extremo a extremo: solo quienes están en la llamada pueden verla y oírla.",
    waitingGroup: "Esperando a que otros abran el enlace…",
    fullTitle: "Esta llamada está completa",
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
    videoUsedUp: "Plus de minutes vidéo : l'appel continue en audio.",
    videoEndsIn: "La vidéo s'arrête dans {n} min ; l'appel continue en audio.",
    endsIn: "L'appel se termine dans {n} min",
    endedLimit: "Cet appel a atteint sa limite de durée.",
    inviteTitleGroup: "Vous êtes invité à un appel de groupe",
    inviteEncryptedGroup: "Il est chiffré de bout en bout : seules les personnes dans l'appel peuvent le voir et l'entendre.",
    waitingGroup: "En attente que d'autres ouvrent le lien…",
    fullTitle: "Cet appel est complet",
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
    videoUsedUp: "Acabaram os minutos de vídeo: a chamada continua só com áudio.",
    videoEndsIn: "O vídeo para em {n} min; a chamada continua com áudio.",
    endsIn: "A chamada termina em {n} min",
    endedLimit: "Esta chamada atingiu o limite de tempo.",
    inviteTitleGroup: "Você foi convidado para uma chamada em grupo",
    inviteEncryptedGroup: "Ela tem criptografia de ponta a ponta: só quem está na chamada pode vê-la e ouvi-la.",
    waitingGroup: "Aguardando outras pessoas abrirem o link…",
    fullTitle: "Esta chamada está cheia",
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
    videoUsedUp: "Минуты видео закончились: звонок продолжается только со звуком.",
    videoEndsIn: "Видео отключится через {n} мин; звонок продолжится со звуком.",
    endsIn: "Звонок завершится через {n} мин",
    endedLimit: "Звонок достиг ограничения по времени.",
    inviteTitleGroup: "Вас приглашают на групповой звонок",
    inviteEncryptedGroup: "Звонок защищён сквозным шифрованием: видеть и слышать его могут только его участники.",
    waitingGroup: "Ждём, пока другие откроют ссылку…",
    fullTitle: "В этом звонке нет мест",
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
    videoUsedUp: "Görüntülü dakika kalmadı: arama sesli devam ediyor.",
    videoEndsIn: "Görüntü {n} dk içinde kapanacak; arama sesli devam edecek.",
    endsIn: "Arama {n} dk içinde bitecek",
    endedLimit: "Bu arama süre sınırına ulaştı.",
    inviteTitleGroup: "Bir grup aramasına davet edildiniz",
    inviteEncryptedGroup: "Arama uçtan uca şifrelidir: yalnızca aramadakiler görebilir ve duyabilir.",
    waitingGroup: "Diğerlerinin bağlantıyı açması bekleniyor…",
    fullTitle: "Bu arama dolu",
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
    videoUsedUp: "ビデオの時間がなくなりました。音声のみで通話を続けます。",
    videoEndsIn: "ビデオはあと {n} 分で停止します。通話は音声で続きます。",
    endsIn: "通話はあと {n} 分で終了します",
    endedLimit: "この通話は時間の上限に達しました。",
    inviteTitleGroup: "グループ通話に招待されています",
    inviteEncryptedGroup: "エンドツーエンドで暗号化されており、見聞きできるのは通話の参加者だけです。",
    waitingGroup: "ほかの人がリンクを開くのを待っています…",
    fullTitle: "この通話は満員です",
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
    videoUsedUp: "वीडियो मिनट खत्म हो गए: कॉल ऑडियो के साथ जारी है।",
    videoEndsIn: "वीडियो {n} मिनट में बंद होगा; कॉल ऑडियो के साथ जारी रहेगी।",
    endsIn: "कॉल {n} मिनट में खत्म होगी",
    endedLimit: "यह कॉल अपनी समय सीमा तक पहुँच गई।",
    inviteTitleGroup: "आपको एक ग्रुप कॉल पर बुलाया गया है",
    inviteEncryptedGroup: "यह एंड-टू-एंड एन्क्रिप्टेड है: इसे सिर्फ़ कॉल में शामिल लोग देख और सुन सकते हैं।",
    waitingGroup: "दूसरों के लिंक खोलने का इंतज़ार…",
    fullTitle: "यह कॉल भर गई है",
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
    videoUsedUp: "Menit video habis: panggilan berlanjut dengan audio saja.",
    videoEndsIn: "Video berhenti dalam {n} menit; panggilan berlanjut dengan audio.",
    endsIn: "Panggilan berakhir dalam {n} menit",
    endedLimit: "Panggilan ini mencapai batas waktunya.",
    inviteTitleGroup: "Anda diundang ke panggilan grup",
    inviteEncryptedGroup: "Panggilan terenkripsi end-to-end: hanya orang dalam panggilan yang bisa melihat dan mendengarnya.",
    waitingGroup: "Menunggu yang lain membuka tautan…",
    fullTitle: "Panggilan ini sudah penuh",
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
    videoUsedUp: "نفدت دقائق الفيديو: تستمر المكالمة بالصوت فقط.",
    videoEndsIn: "سيتوقف الفيديو خلال {n} د؛ تستمر المكالمة بالصوت.",
    endsIn: "تنتهي المكالمة خلال {n} د",
    endedLimit: "بلغت هذه المكالمة حدها الزمني.",
    inviteTitleGroup: "أنت مدعو إلى مكالمة جماعية",
    inviteEncryptedGroup: "المكالمة مشفرة من طرف إلى طرف: لا يمكن رؤيتها أو سماعها إلا للمشاركين فيها.",
    waitingGroup: "في انتظار أن يفتح الآخرون الرابط…",
    fullTitle: "هذه المكالمة ممتلئة",
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
/** A group call (started in a group chat): the server says so too, but the invitation is shown before joining. */
const isGroupLink = new URLSearchParams(location.search).get("g") === "1";

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


/** The host's plan's limits for this call (see LimitsView in src/room.ts). */
interface Limits {
  videoAllowed: boolean;
  videoHeight: number;
  videoBitrate: number;
  endsAt?: number;
  videoEndsAt?: number;
  now: number;
}

interface Peer {
  id: string;
  present: boolean;
  session?: string;
  tracks: string[];
  camera: boolean;
}

/** Everyone else in the call and its limits (see CallView in src/room.ts). */
interface CallInfo {
  group: boolean;
  peers: Peer[];
  limits: Limits;
}

interface Welcome {
  member: string;
  secret?: string;
  iceServers: RTCIceServer[];
  call: CallInfo;
}

/** How long to keep trying after the connection drops; the call itself waits 20 s for someone to come back. */
const RECONNECT_FOR_MS = 25_000;
/** The camera at the plan's height (the server says which: 360p free, 540p Premium). */
const video = (height: number) => ({
  width: { ideal: Math.round((height * 16) / 9) },
  height: { ideal: height },
  frameRate: { ideal: 24 },
  facingMode: "user",
});
/** Until the server says otherwise: the free plan's, the least. */
const DEFAULT_LIMITS: Limits = { videoAllowed: true, videoHeight: 360, videoBitrate: 350_000, now: Date.now() };
/** Warn this long before the call or its video ends. */
const WARN_MS = 5 * 60 * 1000;

const encryptionSupported = () =>
  "RTCRtpScriptTransform" in window || "createEncodedStreams" in RTCRtpSender.prototype;

/** The base64url key from the link. */
function decodeKey(key: string): Uint8Array<ArrayBuffer> | null {
  try {
    const bytes = Uint8Array.from(atob(key.replace(/-/g, "+").replace(/_/g, "/")), (c) => c.charCodeAt(0));
    return bytes.length === 32 ? bytes : null;
  } catch {
    return null;
  }
}

async function start() {
  translate();
  const key = decodeKey(fragment.get("k") ?? "");
  if (!room || !key) return message(t("goneTitle"));
  if (!encryptionSupported()) return message(t("unsupportedTitle"), t("unsupportedText"));

  if (isHost) {
    const token = fragment.get("t");
    if (!token) return message(t("goneTitle"));
    // Out of the address bar and history: the token and key aren't needed again
    history.replaceState(null, "", location.pathname + location.search);
    return new Call(room, key, { token }).start(fragment.get("v") === "1");
  }

  if (isGroupLink) {
    $("invite-title").textContent = t("inviteTitleGroup");
    $("invite-encrypted").textContent = t("inviteEncryptedGroup");
  }
  show("invite");
  const buttons = [$<HTMLButtonElement>("join-video"), $<HTMLButtonElement>("join-audio")];
  buttons.forEach((button, i) =>
    button.addEventListener("click", () => {
      buttons.forEach((b) => (b.disabled = true));
      new Call(room, key, {}).start(i === 0);
    }),
  );
}

/** The WebSocket to the call: requests with answers, and the call's news (the other person, the end). */
class Signal {
  private ws?: WebSocket;
  private nextId = 1;
  private readonly waiting = new Map<number, { resolve: (v: any) => void; reject: (e: Error) => void }>();

  constructor(
    private readonly url: string,
    private readonly onNews: (news: { type: string; call?: CallInfo; reason?: string }) => void,
    private readonly onClose: () => void,
  ) {}

  open(): Promise<void> {
    return new Promise((resolve, reject) => {
      const ws = new WebSocket(this.url);
      this.ws = ws;
      ws.onopen = () => resolve();
      ws.onerror = () => reject(new Error("Can't reach the call"));
      ws.onmessage = (event) => {
        const data = JSON.parse(event.data);
        const waiter = data.id !== undefined ? this.waiting.get(data.id) : undefined;
        if (waiter) {
          this.waiting.delete(data.id);
          if (data.error) waiter.reject(new Error(data.error));
          else waiter.resolve(data);
        } else {
          this.onNews(data);
        }
      };
      ws.onclose = () => {
        if (this.ws !== ws) return;
        for (const waiter of this.waiting.values()) waiter.reject(new Error("Disconnected"));
        this.waiting.clear();
        this.onClose();
      };
    });
  }

  request<T = any>(message: object): Promise<T> {
    const id = this.nextId++;
    return new Promise((resolve, reject) => {
      this.waiting.set(id, { resolve, reject });
      this.ws?.send(JSON.stringify({ id, ...message }));
    });
  }

  send(message: object) {
    if (this.ws?.readyState === WebSocket.OPEN) this.ws.send(JSON.stringify(message));
  }

  close() {
    const ws = this.ws;
    this.ws = undefined;
    ws?.close();
  }
}

/** One other person in the call: what they publish, the connection that receives it, and their tile. */
class Remote {
  info: Peer;
  receiving?: RTCPeerConnection;
  receivingFrom?: string;
  readonly received = new Map<string, string>(); // trackName -> mid
  readonly receivedTracks = new Map<string, MediaStreamTrack>(); // mid -> track
  readonly tile = document.createElement("div");
  readonly video = document.createElement("video");
  readonly audio = document.createElement("audio");

  constructor(info: Peer) {
    this.info = info;
    this.tile.className = "tile";
    this.video.autoplay = true;
    this.video.playsInline = true;
    this.video.muted = true;
    this.audio.autoplay = true;
    this.tile.append(this.video, this.audio);
  }

  close() {
    this.receiving?.close();
    this.receiving = undefined;
    this.receivingFrom = undefined;
    this.received.clear();
    this.receivedTracks.clear();
  }

  track(name: string | undefined) {
    const mid = name ? this.received.get(name) : undefined;
    return mid ? this.receivedTracks.get(mid) : undefined;
  }
}

class Call {
  private signal?: Signal;
  private ice: RTCIceServer[] = [];
  private secret?: string;
  private ended = false;
  private reconnecting = false;
  /** Every change to the connections runs in turn: the SFU wants one negotiation at a time per session. */
  private queue: Promise<unknown> = Promise.resolve();

  private mic?: MediaStreamTrack;
  private camera?: MediaStreamTrack;
  private cameraOn = false;
  private videoCount = 0;
  private sending?: RTCPeerConnection;
  private videoSender?: RTCRtpSender;

  private readonly remotes = new Map<string, Remote>();
  private group = isGroupLink;
  private limits: Limits = DEFAULT_LIMITS;
  /** The server's clock minus this one's. */
  private clockOffset = 0;
  private noticeTimer?: ReturnType<typeof setInterval>;
  private readonly notice = $("notice");
  private peerEverSeen = false;

  private readonly worker = new Worker("/e2ee-worker.js", { type: "module" });
  private readonly status = $("status");
  private readonly grid = $("remotes");
  private readonly localVideo = $<HTMLVideoElement>("local");

  constructor(
    private readonly room: string,
    private readonly key: Uint8Array<ArrayBuffer>,
    private readonly credentials: { token?: string },
  ) {
    // Android's WebView pauses media while the app is in the background and doesn't start it again on return
    document.addEventListener("visibilitychange", () => {
      if (document.visibilityState !== "visible") return;
      const media = [this.localVideo, ...[...this.remotes.values()].flatMap((r) => [r.video, r.audio])];
      for (const m of media) if (m.srcObject && m.paused) m.play().catch(() => undefined);
    });
  }

  async start(video: boolean) {
    show("call");
    this.status.textContent = t("connecting");
    try {
      this.mic = (
        await navigator.mediaDevices.getUserMedia({
          audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true },
        })
      ).getAudioTracks()[0];
    } catch (e) {
      return this.fail(e);
    }
    // Without the camera (refused, busy, or the app went to the background while it started) it's an audio call
    if (video) await this.openCamera().catch((e) => console.warn("Continuing without the camera", e));
    this.setUpControls();
    addEventListener("pagehide", () => this.hangUp());
    try {
      await this.connect();
    } catch (e) {
      this.fail(e);
    }
  }

  /** Joins (or joins again after a dropped connection) and starts sending. */
  private async connect() {
    const url = `${location.protocol === "https:" ? "wss" : "ws"}://${location.host}/api/calls/${this.room}/ws`;
    const signal = new Signal(
      url,
      (news) => this.onNews(news),
      () => this.onDropped(signal),
    );
    this.signal = signal;
    await signal.open();
    const welcome = await signal.request<Welcome>({
      type: "hello",
      ...(this.secret ? { secret: this.secret } : this.credentials),
    });
    this.secret = welcome.secret ?? this.secret;
    this.ice = welcome.iceServers;
    // Before sending: without video minutes the camera goes off first
    this.applyLimits(welcome.call.limits);
    await this.run(() => this.startSending());
    this.onCall(welcome.call);
  }

  private onNews(news: { type: string; call?: CallInfo; reason?: string }) {
    if (news.type === "ended") this.end(news.reason);
    else if (news.type === "call" && news.call) this.onCall(news.call);
  }

  /** The connection to the call dropped, or a media connection failed: try again for a while. */
  private onDropped(signal: Signal) {
    if (this.ended || this.reconnecting || this.signal !== signal) return;
    this.reconnecting = true;
    this.signal = undefined;
    signal.close();
    this.closeConnections();
    this.status.textContent = t("connecting");
    const until = Date.now() + RECONNECT_FOR_MS;
    const retry = async () => {
      if (this.ended) return;
      try {
        await this.connect();
        this.reconnecting = false;
      } catch (e) {
        if ((e as Error).message === "ended" || (e as Error).message === "forbidden") return this.end();
        this.signal?.close();
        this.signal = undefined;
        this.closeConnections();
        if (Date.now() < until) setTimeout(retry, 2000);
        else this.fail(e);
      }
    };
    setTimeout(retry, 1000);
  }

  private newConnection(): RTCPeerConnection {
    const config: RTCConfiguration & { encodedInsertableStreams?: boolean } = {
      iceServers: this.ice,
      bundlePolicy: "max-bundle",
    };
    if (!("RTCRtpScriptTransform" in window)) config.encodedInsertableStreams = true;
    const pc = new RTCPeerConnection(config);
    pc.addEventListener("connectionstatechange", () => {
      if (pc.connectionState === "failed" && this.signal) this.onDropped(this.signal);
    });
    return pc;
  }

  private protect(target: RTCRtpSender | RTCRtpReceiver, op: "encrypt" | "decrypt") {
    const options = { op, key: this.key };
    if (window.RTCRtpScriptTransform) {
      (target as any).transform = new window.RTCRtpScriptTransform(this.worker, options);
    } else {
      const { readable, writable } = (target as any).createEncodedStreams();
      this.worker.postMessage({ ...options, readable, writable }, [readable, writable]);
    }
  }

  private async startSending() {
    this.sending = this.newConnection();
    this.videoSender = undefined;
    const tracks = [this.addSender(this.mic!, "audio")];
    if (this.cameraOn && this.camera) tracks.push(this.addVideo(this.camera));
    await this.publish(tracks);
    if (this.cameraOn) this.signal!.send({ type: "camera", on: true });
  }

  private addSender(track: MediaStreamTrack, trackName: string) {
    const transceiver = this.sending!.addTransceiver(track, { direction: "sendonly" });
    this.protect(transceiver.sender, "encrypt");
    return { transceiver, trackName };
  }

  private addVideo(track: MediaStreamTrack) {
    const added = this.addSender(track, `video${++this.videoCount}`);
    const { transceiver } = added;
    // The encryption keeps VP8's header readable; other codecs would need other rules
    const vp8 = RTCRtpReceiver.getCapabilities("video")?.codecs.filter((c) => /\/(VP8|rtx)$/i.test(c.mimeType));
    if (vp8?.length) transceiver.setCodecPreferences(vp8);
    const parameters = transceiver.sender.getParameters();
    parameters.encodings = [{ ...(parameters.encodings?.[0] ?? {}), maxBitrate: this.limits.videoBitrate }];
    transceiver.sender.setParameters(parameters).catch(() => {});
    this.videoSender = transceiver.sender;
    return added;
  }

  /** Offers the new tracks to the SFU, and once they're flowing, to the others. */
  private async publish(tracks: { transceiver: RTCRtpTransceiver; trackName: string }[]) {
    const pc = this.sending!;
    await pc.setLocalDescription(await pc.createOffer());
    await gathered(pc);
    const { answer } = await this.signal!.request({
      type: "publish",
      offer: pc.localDescription,
      tracks: tracks.map(({ transceiver, trackName }) => ({ mid: transceiver.mid, trackName })),
    });
    await pc.setRemoteDescription(answer);
    await connected(pc);
    await this.signal!.request({ type: "announce" });
  }

  /** Who's in the call now: receives each person's audio and newest video, and drops those who left. */
  private onCall(call: CallInfo) {
    this.group = call.group;
    this.applyLimits(call.limits);
    const ids = new Set(call.peers.map((p) => p.id));
    for (const [id, remote] of this.remotes) {
      if (ids.has(id)) continue;
      remote.close();
      remote.tile.remove();
      this.remotes.delete(id);
    }
    for (const info of call.peers) {
      let remote = this.remotes.get(info.id);
      if (!remote) {
        remote = new Remote(info);
        this.remotes.set(info.id, remote);
      }
      remote.info = info;
    }
    this.run(async () => {
      for (const remote of this.remotes.values()) {
        const latest = remote.info;
        if (!latest.present || !latest.session) {
          remote.close();
          continue;
        }
        const wanted = latest.tracks.filter((n) => n === "audio" || n === newestVideo(latest.tracks));
        const fresh = latest.session !== remote.receivingFrom;
        const missing = fresh ? wanted : wanted.filter((n) => !remote.received.has(n));
        if (missing.length) {
          await this.subscribe(remote, latest.session, missing, fresh).catch((e) =>
            console.warn("Couldn't receive", latest.id, e),
          );
        }
      }
      this.showRemotes();
    }).catch((e) => console.warn("Couldn't receive", e));
    this.showRemotes();
  }

  private async subscribe(remote: Remote, session: string, names: string[], fresh: boolean) {
    if (fresh) {
      remote.close();
      const pc = this.newConnection();
      pc.addEventListener("track", (event) => {
        this.protect(event.receiver, "decrypt");
        remote.receivedTracks.set(event.transceiver.mid!, event.track);
        this.showRemotes();
      });
      remote.receiving = pc;
      remote.receivingFrom = session;
    }
    const pc = remote.receiving!;
    const { offer, tracks } = await this.signal!.request<{
      offer: RTCSessionDescriptionInit;
      tracks: { mid: string; trackName: string }[];
    }>({ type: "subscribe", peer: remote.info.id, session, tracks: names, fresh });
    for (const track of tracks) remote.received.set(track.trackName, track.mid);
    await pc.setRemoteDescription(offer);
    await pc.setLocalDescription(await pc.createAnswer());
    await gathered(pc);
    await this.signal!.request({ type: "answer", peer: remote.info.id, answer: pc.localDescription });
  }

  /** A tile for everyone in the call now, with their video while their camera is on. */
  private showRemotes() {
    const present = [...this.remotes.values()].filter((r) => r.info.present);
    let anyVideo = false;
    let anyAudio = false;
    for (const remote of this.remotes.values()) {
      const audio = remote.track("audio");
      if (audio && (remote.audio.srcObject as MediaStream | null)?.getTracks()[0] !== audio) {
        remote.audio.srcObject = new MediaStream([audio]);
      }
      const video = remote.info.camera ? remote.track(newestVideo(remote.info.tracks)) : undefined;
      if (video && (remote.video.srcObject as MediaStream | null)?.getTracks()[0] !== video) {
        remote.video.srcObject = new MediaStream([video]);
      }
      remote.tile.classList.toggle("active", Boolean(video));
      if (remote.info.present && !remote.tile.isConnected) this.grid.append(remote.tile);
      if (!remote.info.present && remote.tile.isConnected) remote.tile.remove();
      if (remote.info.present && video) anyVideo = true;
      if (remote.info.present && audio) anyAudio = true;
    }
    this.grid.dataset.count = String(present.length);
    if (present.length) this.peerEverSeen = true;
    if (!present.length) {
      this.status.textContent = this.group ? t("waitingGroup") : this.peerEverSeen ? t("connecting") : t("waiting");
    } else if (this.group) {
      // In a group the tiles say who's there; only a call nobody's video shows says it's audio
      this.status.textContent = anyVideo ? "" : anyAudio ? t("audioOnly") : t("connecting");
    } else {
      this.status.textContent = anyVideo ? "" : anyAudio ? t("audioOnly") : t("connecting");
    }
    this.status.classList.toggle("over", anyVideo);
  }

  private async openCamera() {
    this.camera = (await navigator.mediaDevices.getUserMedia({ video: video(this.limits.videoHeight) })).getVideoTracks()[0];
    this.cameraOn = true;
    this.localVideo.srcObject = new MediaStream([this.camera]);
    this.localVideo.classList.add("active");
  }

  private closeCamera() {
    this.camera?.stop();
    this.camera = undefined;
    this.cameraOn = false;
    this.localVideo.classList.remove("active");
  }

  private setUpControls() {
    const micButton = $("mic");
    const cameraButton = $("camera");
    const sync = () => {
      micButton.setAttribute("aria-pressed", String(this.mic?.enabled ?? false));
      cameraButton.setAttribute("aria-pressed", String(this.cameraOn));
    };
    sync();
    micButton.addEventListener("click", () => {
      if (this.mic) this.mic.enabled = !this.mic.enabled;
      sync();
    });
    cameraButton.addEventListener("click", () => {
      this.run(async () => {
        if (this.cameraOn) {
          // Stopped, not just hidden: the camera's light goes off too. Turning it on again publishes a new track
          this.closeCamera();
          this.videoSender?.replaceTrack(null);
          this.signal?.send({ type: "camera", on: false });
        } else {
          await this.openCamera();
          if (this.sending && this.signal) {
            this.videoSender?.replaceTrack(null);
            await this.publish([this.addVideo(this.camera!)]);
            this.signal.send({ type: "camera", on: true });
          }
        }
      })
        .catch((e) => console.warn("Camera", e))
        .finally(sync);
    });
    $("leave").addEventListener("click", () => this.hangUp());
  }

  /** The plan's limits, from the server: the video's quality, video turned off once its minutes are gone, and warnings. */
  private applyLimits(limits: Limits) {
    const before = this.limits;
    this.limits = limits;
    this.clockOffset = limits.now - Date.now();
    if (limits.videoHeight !== before.videoHeight && this.camera) {
      this.camera.applyConstraints(video(limits.videoHeight)).catch(() => {});
    }
    if (limits.videoBitrate !== before.videoBitrate && this.videoSender) {
      const parameters = this.videoSender.getParameters();
      if (parameters.encodings?.[0]) parameters.encodings[0].maxBitrate = limits.videoBitrate;
      this.videoSender.setParameters(parameters).catch(() => {});
    }
    const cameraButton = $<HTMLButtonElement>("camera");
    cameraButton.disabled = !limits.videoAllowed;
    if (!limits.videoAllowed && this.cameraOn) {
      this.run(async () => {
        this.closeCamera();
        this.videoSender?.replaceTrack(null);
        cameraButton.setAttribute("aria-pressed", "false");
      });
    }
    this.showNotice();
    this.noticeTimer ??= setInterval(() => this.showNotice(), 15_000);
  }

  /** "No video minutes left", or the minutes until the end (or the video's end) once it's close. */
  private showNotice() {
    const now = Date.now() + this.clockOffset;
    const soon = (at?: number) => (at !== undefined && at - now <= WARN_MS ? Math.max(1, Math.ceil((at - now) / 60_000)) : 0);
    const end = soon(this.limits.endsAt);
    const videoEnd = this.limits.videoAllowed ? soon(this.limits.videoEndsAt) : 0;
    let text = "";
    if (end) text = t("endsIn").replace("{n}", String(end));
    else if (videoEnd) text = t("videoEndsIn").replace("{n}", String(videoEnd));
    else if (!this.limits.videoAllowed) text = t("videoUsedUp");
    this.notice.textContent = text;
    this.notice.hidden = !text;
  }

  /** Leaves: a one-to-one call ends for both; a group call goes on for the others. */
  private hangUp() {
    if (this.ended) return;
    this.signal?.send({ type: "bye" });
    this.end();
  }

  private end(reason?: string) {
    if (this.ended) return;
    this.ended = true;
    clearInterval(this.noticeTimer);
    this.signal?.close();
    this.closeConnections();
    this.mic?.stop();
    this.closeCamera();
    message(t("endedTitle"), reason ? t("endedLimit") : t("endedText"));
    window.HydCall?.ended();
  }

  private fail(e: unknown) {
    console.error("Couldn't join", e);
    const reason = (e as Error)?.message;
    if (reason === "ended" || reason === "forbidden") return this.end();
    this.ended = true;
    this.signal?.close();
    this.closeConnections();
    this.mic?.stop();
    this.closeCamera();
    message(reason === "busy" ? (this.group ? t("fullTitle") : t("goneTitle")) : t("errorTitle"));
    window.HydCall?.ended();
  }

  private closeConnections() {
    this.sending?.close();
    this.sending = undefined;
    this.videoSender = undefined;
    for (const remote of this.remotes.values()) remote.close();
  }

  private run<T>(task: () => Promise<T>): Promise<T> {
    const next = this.queue.then(task, task);
    this.queue = next.catch(() => {});
    return next;
  }
}

function newestVideo(names: string[]): string | undefined {
  return names.filter((n) => n.startsWith("video")).sort((a, b) => Number(a.slice(5)) - Number(b.slice(5))).pop();
}

/** Waits (briefly) for the connection's network addresses, which go in the description the SFU gets. */
function gathered(pc: RTCPeerConnection): Promise<void> {
  if (pc.iceGatheringState === "complete") return Promise.resolve();
  return new Promise((resolve) => {
    const done = () => {
      pc.removeEventListener("icegatheringstatechange", check);
      resolve();
    };
    const check = () => pc.iceGatheringState === "complete" && done();
    pc.addEventListener("icegatheringstatechange", check);
    setTimeout(done, 1500);
  });
}

function connected(pc: RTCPeerConnection, timeoutMs = 15_000): Promise<void> {
  if (pc.connectionState === "connected") return Promise.resolve();
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error("Couldn't connect the call")), timeoutMs);
    const check = () => {
      if (pc.connectionState === "connected") {
        clearTimeout(timer);
        pc.removeEventListener("connectionstatechange", check);
        resolve();
      } else if (pc.connectionState === "failed" || pc.connectionState === "closed") {
        clearTimeout(timer);
        pc.removeEventListener("connectionstatechange", check);
        reject(new Error("Couldn't connect the call"));
      }
    };
    pc.addEventListener("connectionstatechange", check);
  });
}

start();

export {};
