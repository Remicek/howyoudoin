package com.devvalor.howyoudoing.app.di

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
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceTelegram
import com.devvalor.howyoudoing.core.domain.ondevice.UnsupportedOnDeviceSignal
import com.devvalor.howyoudoing.core.domain.ondevice.UnsupportedOnDeviceTelegram
import com.devvalor.howyoudoing.chat.data.di.ON_DEVICE_SIGNAL_CACHE
import com.devvalor.howyoudoing.chat.data.di.ON_DEVICE_TELEGRAM_CACHE
import org.koin.core.qualifier.named
import com.devvalor.howyoudoing.app.ondevice.IosWhatsAppFactory
import com.devvalor.howyoudoing.app.ondevice.NativeOnDeviceWhatsApp
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceMessenger
import com.devvalor.howyoudoing.app.notifications.IncomingMessageNotifier
import com.devvalor.howyoudoing.app.notifications.MessageNotificationPresenter
import com.devvalor.howyoudoing.app.notifications.NoOpNotificationPresenter
import org.koin.core.module.Module
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
    single {
        val version = NSBundle.mainBundle.infoDictionary?.get("CFBundleShortVersionString") as? String
        AppInfo(versionName = version ?: "?", sourceCodeUrl = SOURCE_CODE_URL)
    }
    single<MessageNotificationPresenter> { NoOpNotificationPresenter }
    single(createdAtStart = true) { IncomingMessageNotifier(get(), get(), get(), get(), get(), get()) }
    single<OnDeviceInboxCache>(named(ON_DEVICE_INSTAGRAM_CACHE)) { IosInboxCache(INSTAGRAM_DIR) }
    single<OnDeviceInstagram>(createdAtStart = true) {
        NativeOnDeviceInstagram(IosInstagramFactory(), IosSecretStore(INSTAGRAM_DIR), IosDataDirectory(INSTAGRAM_DIR), get())
    }
    single<OnDeviceInboxCache>(named(ON_DEVICE_WHATSAPP_CACHE)) { IosInboxCache(WHATSAPP_DIR) }
    single<OnDeviceWhatsApp>(createdAtStart = true) {
        NativeOnDeviceWhatsApp(IosWhatsAppFactory(), IosDataDirectory(WHATSAPP_DIR), get())
    }
    // Telegram and Signal aren't built for iOS yet
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
