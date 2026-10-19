package com.devvalor.howyoudoing.app.di

import com.devvalor.howyoudoing.app.ondevice.AndroidDataDirectory
import com.devvalor.howyoudoing.app.ondevice.AndroidMessengerFactory
import com.devvalor.howyoudoing.app.ondevice.KeystoreSecretStore
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
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceTelegram
import com.devvalor.howyoudoing.app.BuildConfig
import com.devvalor.howyoudoing.app.ondevice.AndroidSignalFactory
import com.devvalor.howyoudoing.app.ondevice.AndroidTelegramFactory
import com.devvalor.howyoudoing.app.ondevice.NativeOnDeviceSignal
import com.devvalor.howyoudoing.app.ondevice.NativeOnDeviceTelegram
import com.devvalor.howyoudoing.chat.data.di.ON_DEVICE_SIGNAL_CACHE
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
import org.koin.dsl.module

actual val platformAppModule: Module = module {
    single<MessageNotificationPresenter> { AndroidNotificationPresenter(androidContext()) }
    single(createdAtStart = true) { IncomingMessageNotifier(get(), get(), get(), get(), get()) }
    single(createdAtStart = true) {
        OnDeviceConnectionKeeper(
            context = androidContext(),
            clients = listOf(
                get<OnDeviceMessenger>(), get<OnDeviceWhatsApp>(), get<OnDeviceInstagram>(), get<OnDeviceTelegram>(), get<OnDeviceSignal>()
            ),
            linkedAccounts = listOf(get<OnDeviceWhatsApp>(), get<OnDeviceTelegram>(), get<OnDeviceSignal>()),
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
    single<OnDeviceTelegram>(createdAtStart = true) {
        val factory = BuildConfig.TELEGRAM_API_ID.takeIf { it != 0L && BuildConfig.TELEGRAM_API_HASH.isNotEmpty() }
            ?.let { AndroidTelegramFactory(it, BuildConfig.TELEGRAM_API_HASH) }
        NativeOnDeviceTelegram(factory, AndroidDataDirectory(androidContext(), TELEGRAM_DIR), get())
    }
    single<OnDeviceInboxCache>(named(ON_DEVICE_SIGNAL_CACHE)) { AndroidInboxCache(androidContext(), SIGNAL_DIR) }
    single<OnDeviceSignal>(createdAtStart = true) {
        NativeOnDeviceSignal(AndroidSignalFactory(), AndroidDataDirectory(androidContext(), SIGNAL_DIR), get())
    }
    single<OnDeviceInboxCache> { AndroidInboxCache(androidContext()) }
    single<OnDeviceMessenger>(createdAtStart = true) {
        NativeOnDeviceMessenger(
            factory = AndroidMessengerFactory(),
            secrets = KeystoreSecretStore(androidContext()),
            dataDir = AndroidDataDirectory(androidContext()),
            scope = get()
        )
    }
}

private const val WHATSAPP_DIR = "on_device_whatsapp"
private const val INSTAGRAM_DIR = "on_device_instagram"
private const val TELEGRAM_DIR = "on_device_telegram"
private const val SIGNAL_DIR = "on_device_signal"
