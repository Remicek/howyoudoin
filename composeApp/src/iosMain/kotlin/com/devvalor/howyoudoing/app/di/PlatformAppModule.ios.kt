package com.devvalor.howyoudoing.app.di

import com.devvalor.howyoudoing.core.domain.connectivity.AlwaysOnline
import com.devvalor.howyoudoing.core.domain.connectivity.InternetConnection
import com.devvalor.howyoudoing.chat.data.di.onDeviceClientName
import com.devvalor.howyoudoing.chat.data.di.onDeviceCacheName
import com.devvalor.howyoudoing.core.domain.ondevice.UnsupportedOnDeviceNetwork
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceNetworks
import com.devvalor.howyoudoing.core.domain.ondevice.ExtraAccounts
import com.devvalor.howyoudoing.app.ondevice.IosDataDirectory
import com.devvalor.howyoudoing.app.ondevice.IosMessengerFactory
import com.devvalor.howyoudoing.app.ondevice.IosSecretStore
import com.devvalor.howyoudoing.app.ondevice.IosInboxCache
import com.devvalor.howyoudoing.app.ondevice.NativeOnDeviceMessenger
import com.devvalor.howyoudoing.chat.data.ondevice.OnDeviceInboxCache
import com.devvalor.howyoudoing.chat.data.di.ON_DEVICE_INSTAGRAM_CACHE
import com.devvalor.howyoudoing.chat.data.di.ON_DEVICE_WHATSAPP_CACHE
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceInstagram
import com.devvalor.howyoudoing.app.ondevice.NativeOnDeviceInstagram
import com.devvalor.howyoudoing.app.ondevice.IosInstagramFactory
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceWhatsApp
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceSignal
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceSlack
import com.devvalor.howyoudoing.core.domain.ondevice.UnsupportedOnDeviceSlack
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceTelegram
import com.devvalor.howyoudoing.core.domain.ondevice.UnsupportedOnDeviceSignal
import com.devvalor.howyoudoing.core.domain.ondevice.UnsupportedOnDeviceTelegram
import com.devvalor.howyoudoing.chat.data.di.ON_DEVICE_SIGNAL_CACHE
import com.devvalor.howyoudoing.chat.data.di.ON_DEVICE_SLACK_CACHE
import com.devvalor.howyoudoing.chat.data.di.ON_DEVICE_TELEGRAM_CACHE
import org.koin.core.qualifier.named
import com.devvalor.howyoudoing.app.ondevice.IosWhatsAppFactory
import com.devvalor.howyoudoing.app.ondevice.NativeOnDeviceWhatsApp
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceMessenger
import com.devvalor.howyoudoing.app.notifications.IncomingMessageNotifier
import com.devvalor.howyoudoing.app.notifications.MessageNotificationPresenter
import com.devvalor.howyoudoing.app.notifications.NoOpNotificationPresenter
import org.koin.core.module.Module
import com.devvalor.howyoudoing.app.PRIVACY_POLICY_URL
import com.devvalor.howyoudoing.app.SOURCE_CODE_URL
import com.devvalor.howyoudoing.app.settings.IosBiometricAuthenticator
import com.devvalor.howyoudoing.app.settings.IosCrashReports
import com.devvalor.howyoudoing.app.settings.IosPinHasher
import com.devvalor.howyoudoing.settings.domain.AppInfo
import com.devvalor.howyoudoing.settings.domain.BiometricAuthenticator
import com.devvalor.howyoudoing.settings.domain.CrashReports
import com.devvalor.howyoudoing.settings.domain.PinHasher
import com.devvalor.howyoudoing.core.domain.config.BuildInfo
import platform.Foundation.NSBundle
import org.koin.dsl.module

actual val platformAppModule: Module = module {
    single<PinHasher> { IosPinHasher() }
    @OptIn(kotlin.experimental.ExperimentalNativeApi::class)
    single<BuildInfo> { BuildInfo { kotlin.native.Platform.isDebugBinary } }
    single<BiometricAuthenticator> { IosBiometricAuthenticator }
    single<CrashReports> { IosCrashReports }
    single<InternetConnection> { AlwaysOnline }
    single {
        val version = NSBundle.mainBundle.infoDictionary?.get("CFBundleShortVersionString") as? String
        AppInfo(versionName = version ?: "?", sourceCodeUrl = SOURCE_CODE_URL, privacyPolicyUrl = PRIVACY_POLICY_URL)
    }
    single<MessageNotificationPresenter> { NoOpNotificationPresenter }
    single(createdAtStart = true) { IncomingMessageNotifier(get(), get(), get(), get(), get(), get(), get()) }
    single<OnDeviceInboxCache>(named(ON_DEVICE_INSTAGRAM_CACHE)) { IosInboxCache(INSTAGRAM_DIR) }
    single<OnDeviceInstagram>(createdAtStart = true) {
        NativeOnDeviceInstagram(
            IosInstagramFactory(),
            IosSecretStore(INSTAGRAM_DIR),
            IosDataDirectory(INSTAGRAM_DIR),
            get()
        )
    }
    single<OnDeviceInboxCache>(named(ON_DEVICE_WHATSAPP_CACHE)) { IosInboxCache(WHATSAPP_DIR) }
    single<OnDeviceWhatsApp>(createdAtStart = true) {
        NativeOnDeviceWhatsApp(IosWhatsAppFactory(), IosDataDirectory(WHATSAPP_DIR), get())
    }
    // Telegram, Signal, Slack and calls aren't built for iOS yet
    single<com.devvalor.howyoudoing.chat.domain.CallMediaEngine> {
        com.devvalor.howyoudoing.chat.domain.NoCallMediaEngine
    }
    single { com.devvalor.howyoudoing.chat.domain.CallLauncher { _, _ -> } }
    single<com.devvalor.howyoudoing.chat.domain.LinkCalls> { com.devvalor.howyoudoing.chat.domain.NoLinkCalls }
    single { com.devvalor.howyoudoing.chat.domain.LinkCallLauncher { } }
    single<com.devvalor.howyoudoing.core.domain.ondevice.TelegramApiKeys> {
        com.devvalor.howyoudoing.core.domain.ondevice.NoTelegramApiKeys
    }
    single<com.devvalor.howyoudoing.core.domain.ondevice.BackgroundDelivery> {
        com.devvalor.howyoudoing.core.domain.ondevice.NoBackgroundDelivery
    }
    single<com.devvalor.howyoudoing.core.domain.app.AppPromotion> {
        com.devvalor.howyoudoing.core.domain.app.NoAppPromotion
    }
    single<com.devvalor.howyoudoing.core.domain.backup.LoginBackup> {
        com.devvalor.howyoudoing.core.domain.backup.NoLoginBackup
    }
    single<OnDeviceSlack> { UnsupportedOnDeviceSlack }
    // The networks added later aren't built for iOS either
    OnDeviceNetworks.ALL.forEach { network ->
        single<OnDeviceMessenger>(named(onDeviceClientName(network))) { UnsupportedOnDeviceNetwork(network) }
        single<OnDeviceInboxCache>(named(onDeviceCacheName(network))) {
            IosInboxCache("on_device_" + network.name.lowercase())
        }
    }
    single {
        OnDeviceNetworks(OnDeviceNetworks.ALL.associateWith { get<OnDeviceMessenger>(named(onDeviceClientName(it))) })
    }
    // One account per network on iOS for now
    single { ExtraAccounts() }
    single<OnDeviceInboxCache>(named(ON_DEVICE_SLACK_CACHE)) { IosInboxCache("on_device_slack") }
    single<OnDeviceTelegram> { UnsupportedOnDeviceTelegram }
    single<OnDeviceSignal> { UnsupportedOnDeviceSignal }
    single<OnDeviceInboxCache>(named(ON_DEVICE_TELEGRAM_CACHE)) { IosInboxCache("on_device_telegram") }
    single<OnDeviceInboxCache>(named(ON_DEVICE_SIGNAL_CACHE)) { IosInboxCache("on_device_signal") }
    single<OnDeviceInboxCache> { IosInboxCache() }
    single<OnDeviceMessenger>(createdAtStart = true) {
        NativeOnDeviceMessenger(
            factory = IosMessengerFactory(),
            secrets = IosSecretStore(),
            dataDir = IosDataDirectory(),
            scope = get()
        )
    }
}

private const val WHATSAPP_DIR = "on_device_whatsapp"
private const val INSTAGRAM_DIR = "on_device_instagram"
