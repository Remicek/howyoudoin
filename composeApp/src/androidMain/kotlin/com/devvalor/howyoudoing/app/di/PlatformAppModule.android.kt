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
import org.koin.core.qualifier.named
import com.devvalor.howyoudoing.app.ondevice.AndroidWhatsAppFactory
import com.devvalor.howyoudoing.app.ondevice.NativeOnDeviceWhatsApp
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceMessenger
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

actual val platformAppModule: Module = module {
    single(createdAtStart = true) {
        OnDeviceConnectionKeeper(
            context = androidContext(),
            clients = listOf(get<OnDeviceMessenger>(), get<OnDeviceWhatsApp>(), get<OnDeviceInstagram>()),
            whatsApp = get(),
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
