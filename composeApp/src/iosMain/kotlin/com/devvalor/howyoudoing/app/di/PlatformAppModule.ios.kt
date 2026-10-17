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
import org.koin.core.qualifier.named
import com.devvalor.howyoudoing.app.ondevice.IosWhatsAppFactory
import com.devvalor.howyoudoing.app.ondevice.NativeOnDeviceWhatsApp
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceMessenger
import org.koin.core.module.Module
import org.koin.dsl.module

actual val platformAppModule: Module = module {
    single<OnDeviceInboxCache>(named(ON_DEVICE_INSTAGRAM_CACHE)) { IosInboxCache(INSTAGRAM_DIR) }
    single<OnDeviceInstagram>(createdAtStart = true) {
        NativeOnDeviceInstagram(IosInstagramFactory(), IosSecretStore(INSTAGRAM_DIR), IosDataDirectory(INSTAGRAM_DIR), get())
    }
    single<OnDeviceInboxCache>(named(ON_DEVICE_WHATSAPP_CACHE)) { IosInboxCache(WHATSAPP_DIR) }
    single<OnDeviceWhatsApp>(createdAtStart = true) {
        NativeOnDeviceWhatsApp(IosWhatsAppFactory(), IosDataDirectory(WHATSAPP_DIR), get())
    }
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
