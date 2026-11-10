package com.devvalor.howyoudoing.app.di

import com.devvalor.howyoudoing.app.premium.PlayPremium
import com.devvalor.howyoudoing.core.domain.premium.CallAllowance
import com.devvalor.howyoudoing.core.domain.premium.Premium
import org.koin.dsl.binds
import org.koin.android.ext.koin.androidApplication
import com.devvalor.howyoudoing.app.promotion.AndroidAppPromotion
import com.devvalor.howyoudoing.core.domain.ondevice.account
import com.devvalor.howyoudoing.core.domain.app.AppPromotion
import com.devvalor.howyoudoing.app.connectivity.AndroidInternetConnection
import com.devvalor.howyoudoing.core.domain.connectivity.InternetConnection
import com.devvalor.howyoudoing.app.ondevice.AndroidTelegramApiKeys
import com.devvalor.howyoudoing.core.domain.ondevice.TelegramApiKeys
import com.devvalor.howyoudoing.chat.data.di.onDeviceClientName
import com.devvalor.howyoudoing.chat.data.di.onDeviceCacheName
import com.devvalor.howyoudoing.app.ondevice.AndroidGoogleMessagesFactory
import com.devvalor.howyoudoing.app.ondevice.AndroidChatClientFactory
import com.devvalor.howyoudoing.app.ondevice.NativeOnDeviceGoogleMessages
import com.devvalor.howyoudoing.app.ondevice.NativeOnDeviceWebAccount
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceLinkedAccount
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceNetworks
import com.devvalor.howyoudoing.app.ondevice.AndroidDataDirectory
import com.devvalor.howyoudoing.app.ondevice.AndroidMessengerFactory
import com.devvalor.howyoudoing.app.ondevice.AndroidMatrixMediaCache
import com.devvalor.howyoudoing.app.ondevice.GoMatrixCryptoFactory
import com.devvalor.howyoudoing.app.ondevice.KeystoreSecretStore
import com.devvalor.howyoudoing.chat.data.matrix.MatrixCryptoFactory
import com.devvalor.howyoudoing.chat.data.matrix.MatrixMediaCache
import com.devvalor.howyoudoing.app.ondevice.AndroidInboxCache
import com.devvalor.howyoudoing.app.ondevice.NativeOnDeviceMessenger
import com.devvalor.howyoudoing.app.ondevice.OnDeviceConnectionKeeper
import com.devvalor.howyoudoing.chat.data.ondevice.OnDeviceInboxCache
import com.devvalor.howyoudoing.chat.data.di.ON_DEVICE_INSTAGRAM_CACHE
import com.devvalor.howyoudoing.chat.data.di.ON_DEVICE_WHATSAPP_CACHE
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceInstagram
import com.devvalor.howyoudoing.app.ondevice.NativeOnDeviceInstagram
import com.devvalor.howyoudoing.app.ondevice.AndroidInstagramFactory
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceWhatsApp
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceSignal
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceSlack
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceTelegram
import com.devvalor.howyoudoing.app.BuildConfig
import com.devvalor.howyoudoing.app.ondevice.AndroidSignalFactory
import com.devvalor.howyoudoing.app.ondevice.AndroidSlackFactory
import com.devvalor.howyoudoing.app.ondevice.NativeOnDeviceSlack
import com.devvalor.howyoudoing.app.ondevice.AndroidTelegramFactory
import com.devvalor.howyoudoing.app.ondevice.NativeOnDeviceSignal
import com.devvalor.howyoudoing.app.ondevice.NativeOnDeviceTelegram
import com.devvalor.howyoudoing.chat.data.di.ON_DEVICE_SIGNAL_CACHE
import com.devvalor.howyoudoing.chat.data.di.ON_DEVICE_SLACK_CACHE
import com.devvalor.howyoudoing.chat.data.di.ON_DEVICE_TELEGRAM_CACHE
import org.koin.core.qualifier.named
import com.devvalor.howyoudoing.app.ondevice.AndroidWhatsAppFactory
import com.devvalor.howyoudoing.app.ondevice.NativeOnDeviceWhatsApp
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceMessenger
import org.koin.android.ext.koin.androidContext
import com.devvalor.howyoudoing.app.notifications.IncomingMessageNotifier
import com.devvalor.howyoudoing.app.notifications.MessageNotificationPresenter
import com.devvalor.howyoudoing.app.notifications.AndroidNotificationPresenter
import org.koin.core.module.Module
import com.devvalor.howyoudoing.app.PRIVACY_POLICY_URL
import com.devvalor.howyoudoing.app.SOURCE_CODE_URL
import com.devvalor.howyoudoing.app.settings.AndroidBiometricAuthenticator
import com.devvalor.howyoudoing.app.settings.AndroidCrashReports
import com.devvalor.howyoudoing.app.settings.AndroidPinHasher
import com.devvalor.howyoudoing.settings.domain.AppInfo
import com.devvalor.howyoudoing.settings.domain.BiometricAuthenticator
import com.devvalor.howyoudoing.settings.domain.CrashReports
import com.devvalor.howyoudoing.settings.domain.PinHasher
import com.devvalor.howyoudoing.core.domain.config.BuildInfo
import org.koin.dsl.module
import com.devvalor.howyoudoing.app.backup.AndroidLoginBackup
import com.devvalor.howyoudoing.core.domain.auth.MATRIX_ACCOUNT_SESSION
import com.devvalor.howyoudoing.core.domain.backup.LoginBackup
import com.devvalor.howyoudoing.core.domain.ondevice.BackgroundDelivery
import com.devvalor.howyoudoing.app.ondevice.AndroidBackgroundDelivery
import com.devvalor.howyoudoing.app.ondevice.OnlineReconnector
import com.devvalor.howyoudoing.core.domain.network.ChatNetwork
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceMatrix
import java.io.File
import org.koin.dsl.bind
import com.devvalor.howyoudoing.core.domain.ondevice.AccountRef
import com.devvalor.howyoudoing.app.backup.SavedLoginsFormat
import com.devvalor.howyoudoing.core.domain.ondevice.ExtraAccounts
import com.devvalor.howyoudoing.chat.data.ondevice.ExtraInboxCaches
import com.devvalor.howyoudoing.app.call.AndroidCallMediaEngine
import com.devvalor.howyoudoing.app.call.CallActivity
import com.devvalor.howyoudoing.app.call.LinkCallActivity
import com.devvalor.howyoudoing.chat.data.linkcall.HttpLinkCalls
import com.devvalor.howyoudoing.chat.domain.LinkCallLauncher
import com.devvalor.howyoudoing.chat.domain.LinkCalls
import java.security.SecureRandom
import com.devvalor.howyoudoing.app.call.CallNotifier
import com.devvalor.howyoudoing.app.notifications.ConnectionProblemNotifier
import com.devvalor.howyoudoing.chat.domain.CallLauncher
import com.devvalor.howyoudoing.chat.domain.CallMediaEngine

actual val platformAppModule: Module = module {
    single<PinHasher> { AndroidPinHasher() }
    single<BuildInfo> { BuildInfo { BuildConfig.DEBUG } }
    single<BiometricAuthenticator> { AndroidBiometricAuthenticator(androidContext()) }
    single<CrashReports> { AndroidCrashReports(androidContext()) }
    single {
        val version = runCatching {
            androidContext().packageManager.getPackageInfo(androidContext().packageName, 0).versionName
        }.getOrNull()
        AppInfo(versionName = version ?: "?", sourceCodeUrl = SOURCE_CODE_URL, privacyPolicyUrl = PRIVACY_POLICY_URL)
    }
    single<MessageNotificationPresenter> { AndroidNotificationPresenter(androidContext()) }
    single(createdAtStart = true) { IncomingMessageNotifier(get(), get(), get(), get(), get(), get(), get()) }
    single<BackgroundDelivery> { AndroidBackgroundDelivery(androidContext()) }
    single(createdAtStart = true) { OnlineReconnector(androidContext(), get(), get()) }
    single<InternetConnection> { AndroidInternetConnection(androidContext()) }
    single(createdAtStart = true) {
        OnDeviceConnectionKeeper(
            context = androidContext(),
            clients = listOf(
                get<OnDeviceMessenger>(),
                get<OnDeviceWhatsApp>(),
                get<OnDeviceInstagram>(),
                get<OnDeviceTelegram>(),
                get<OnDeviceSignal>(),
                get<OnDeviceSlack>()
            ) + get<OnDeviceNetworks>().clients.values + get<ExtraAccounts>().clients,
            linkedAccounts = listOf(get<OnDeviceWhatsApp>(), get<OnDeviceTelegram>(), get<OnDeviceSignal>()) +
                get<OnDeviceNetworks>().clients.values.filterIsInstance<OnDeviceLinkedAccount>() +
                get<ExtraAccounts>().clients.filterIsInstance<OnDeviceLinkedAccount>(),
            scope = get()
        )
    }
    single<OnDeviceInboxCache>(named(ON_DEVICE_INSTAGRAM_CACHE)) { AndroidInboxCache(androidContext(), INSTAGRAM_DIR) }
    single<OnDeviceInstagram>(createdAtStart = true) {
        NativeOnDeviceInstagram(
            factory = AndroidInstagramFactory(),
            secrets = KeystoreSecretStore(androidContext(), INSTAGRAM_DIR),
            dataDir = AndroidDataDirectory(androidContext(), INSTAGRAM_DIR),
            scope = get()
        )
    }
    single<OnDeviceInboxCache>(named(ON_DEVICE_WHATSAPP_CACHE)) { AndroidInboxCache(androidContext(), WHATSAPP_DIR) }
    single<OnDeviceWhatsApp>(createdAtStart = true) {
        NativeOnDeviceWhatsApp(AndroidWhatsAppFactory(), AndroidDataDirectory(androidContext(), WHATSAPP_DIR), get())
    }
    single<OnDeviceInboxCache>(named(ON_DEVICE_TELEGRAM_CACHE)) { AndroidInboxCache(androidContext(), TELEGRAM_DIR) }
    single { AndroidTelegramApiKeys(androidContext()) } bind TelegramApiKeys::class
    single<OnDeviceTelegram>(createdAtStart = true) {
        // The user's own keys (Settings → Telegram API keys), else the app's; without either, no Telegram
        val factory = get<AndroidTelegramApiKeys>().current()?.let { AndroidTelegramFactory(it.apiId, it.apiHash) }
        NativeOnDeviceTelegram(factory, AndroidDataDirectory(androidContext(), TELEGRAM_DIR), get())
    }
    single<OnDeviceInboxCache>(named(ON_DEVICE_SIGNAL_CACHE)) { AndroidInboxCache(androidContext(), SIGNAL_DIR) }
    single<OnDeviceSignal>(createdAtStart = true) {
        NativeOnDeviceSignal(AndroidSignalFactory(), AndroidDataDirectory(androidContext(), SIGNAL_DIR), get())
    }
    single<OnDeviceInboxCache>(named(ON_DEVICE_SLACK_CACHE)) { AndroidInboxCache(androidContext(), SLACK_DIR) }
    single<OnDeviceSlack>(createdAtStart = true) {
        NativeOnDeviceSlack(
            factory = AndroidSlackFactory(),
            secrets = KeystoreSecretStore(androidContext(), SLACK_DIR),
            dataDir = AndroidDataDirectory(androidContext(), SLACK_DIR),
            scope = get()
        )
    }
    OnDeviceNetworks.ALL.forEach { network ->
        single<OnDeviceInboxCache>(named(onDeviceCacheName(network))) {
            AndroidInboxCache(androidContext(), network.dataDir)
        }
    }
    OnDeviceNetworks.WEB_LOGIN.forEach { network ->
        single<OnDeviceMessenger>(named(onDeviceClientName(network)), createdAtStart = true) {
            NativeOnDeviceWebAccount(
                network = network,
                factory = AndroidChatClientFactory(network.goName),
                secrets = KeystoreSecretStore(androidContext(), network.dataDir),
                dataDir = AndroidDataDirectory(androidContext(), network.dataDir),
                scope = get(),
                captchaSolver = get()
            )
        }
    }
    single<OnDeviceMessenger>(named(onDeviceClientName(ChatNetwork.GOOGLE_MESSAGES)), createdAtStart = true) {
        NativeOnDeviceGoogleMessages(
            AndroidGoogleMessagesFactory(),
            AndroidDataDirectory(androidContext(), ChatNetwork.GOOGLE_MESSAGES.dataDir),
            get()
        )
    }
    single {
        OnDeviceNetworks(OnDeviceNetworks.ALL.associateWith { get<OnDeviceMessenger>(named(onDeviceClientName(it))) })
    }
    single { extraAccountClients(androidContext(), get(), get<AndroidTelegramApiKeys>().current(), get()) }
    single {
        ExtraInboxCaches(
            get<ExtraAccounts>().clients.associate {
                it.account to AndroidInboxCache(androidContext(), it.account.dataDir)
            }
        )
    }
    single { AndroidCallMediaEngine(androidContext()) } bind CallMediaEngine::class
    single<MatrixCryptoFactory> { GoMatrixCryptoFactory(androidContext()) }
    single<MatrixMediaCache> { AndroidMatrixMediaCache(androidContext()) }
    single(createdAtStart = true) { CallNotifier(androidContext(), get(), get()) }
    single(createdAtStart = true) { ConnectionProblemNotifier(androidContext(), get(), get()) }
    single<CallLauncher> { CallLauncher { chatId, video -> CallActivity.startCall(androidContext(), chatId, video) } }
    single<OnDeviceInboxCache> { AndroidInboxCache(androidContext()) }
    single<AppPromotion> { AndroidAppPromotion(androidContext()) }
    single<Premium>(createdAtStart = true) { PlayPremium(androidApplication(), get()) }
    single {
        val random = SecureRandom()
        HttpLinkCalls(get(), BuildConfig.CALL_LINK_URL, get(), get()) { size ->
            ByteArray(size).also(random::nextBytes)
        }
    } binds arrayOf(LinkCalls::class, CallAllowance::class)
    single<LinkCallLauncher> { LinkCallLauncher { hostUrl -> LinkCallActivity.start(androidContext(), hostUrl) } }
    single<LoginBackup>(createdAtStart = true) {
        AndroidLoginBackup(
            context = androidContext(),
            appSession = get(),
            matrixSession = get(named(MATRIX_ACCOUNT_SESSION)),
            sources = AndroidLoginBackup.BackupSources(
                cookieLogins = mapOf(
                    AccountRef(ChatNetwork.MESSENGER) to
                        (get<OnDeviceMessenger>() to KeystoreSecretStore(androidContext())),
                    AccountRef(ChatNetwork.INSTAGRAM) to
                        (get<OnDeviceInstagram>() to KeystoreSecretStore(androidContext(), INSTAGRAM_DIR)),
                    AccountRef(ChatNetwork.SLACK) to
                        (get<OnDeviceSlack>() to KeystoreSecretStore(androidContext(), SLACK_DIR))
                ) + OnDeviceNetworks.WEB_LOGIN.associate { network ->
                    AccountRef(network) to (
                        get<OnDeviceMessenger>(named(onDeviceClientName(network))) to
                            KeystoreSecretStore(androidContext(), network.dataDir)
                        )
                } + get<ExtraAccounts>().clients
                    .filter { it.network in SavedLoginsFormat.COOKIE_NETWORKS }
                    .associate { it.account to (it to KeystoreSecretStore(androidContext(), it.account.dataDir)) },
                telegram = (listOf(get<OnDeviceTelegram>()) + get<ExtraAccounts>().clients)
                    .filter { it.network == ChatNetwork.TELEGRAM }
                    .filterIsInstance<NativeOnDeviceTelegram>()
                    .associate { it.slot to (it to File(androidContext().noBackupFilesDir, it.account.dataDir)) },
                relinkOnly = listOf(get<OnDeviceWhatsApp>(), get<OnDeviceSignal>()) +
                    OnDeviceNetworks.PAIRED.map { get<OnDeviceMessenger>(named(onDeviceClientName(it))) } +
                    get<ExtraAccounts>().clients.filter {
                        it.network == ChatNetwork.WHATSAPP || it.network == ChatNetwork.SIGNAL ||
                            it.network in OnDeviceNetworks.PAIRED
                    },
                matrix = get<OnDeviceMatrix>(),
                labels = get()
            ),
            scope = get()
        )
    }
    single<OnDeviceMessenger>(createdAtStart = true) {
        NativeOnDeviceMessenger(
            factory = AndroidMessengerFactory(),
            secrets = KeystoreSecretStore(androidContext()),
            dataDir = AndroidDataDirectory(androidContext()),
            scope = get()
        )
    }
}

/** The first Messenger account's folder (the app's first network, so named like the defaults). */
internal const val MESSENGER_DIR = "on_device_messenger"
internal const val WHATSAPP_DIR = "on_device_whatsapp"
internal const val INSTAGRAM_DIR = "on_device_instagram"
internal const val TELEGRAM_DIR = "on_device_telegram"
internal const val SIGNAL_DIR = "on_device_signal"
internal const val SLACK_DIR = "on_device_slack"

/** Where a network added later keeps its data ("on_device_bluesky"). */
internal val ChatNetwork.dataDir: String get() = "on_device_" + name.lowercase()

/** The network's name in hydbridge's NewChatClient. */
internal val ChatNetwork.goName: String get() = name.lowercase().replace("_", "")
