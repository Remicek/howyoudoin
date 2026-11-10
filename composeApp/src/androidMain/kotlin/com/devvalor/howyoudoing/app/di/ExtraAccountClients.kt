package com.devvalor.howyoudoing.app.di

import android.content.Context
import com.devvalor.howyoudoing.app.ondevice.AndroidChatClientFactory
import com.devvalor.howyoudoing.app.ondevice.AndroidDataDirectory
import com.devvalor.howyoudoing.app.ondevice.AndroidGoogleMessagesFactory
import com.devvalor.howyoudoing.app.ondevice.AndroidInstagramFactory
import com.devvalor.howyoudoing.app.ondevice.AndroidMessengerFactory
import com.devvalor.howyoudoing.app.ondevice.AndroidSignalFactory
import com.devvalor.howyoudoing.app.ondevice.AndroidSlackFactory
import com.devvalor.howyoudoing.app.ondevice.AndroidTelegramFactory
import com.devvalor.howyoudoing.app.ondevice.AndroidWhatsAppFactory
import com.devvalor.howyoudoing.app.ondevice.KeystoreSecretStore
import com.devvalor.howyoudoing.app.ondevice.NativeOnDeviceGoogleMessages
import com.devvalor.howyoudoing.app.ondevice.NativeOnDeviceInstagram
import com.devvalor.howyoudoing.app.ondevice.NativeOnDeviceMessenger
import com.devvalor.howyoudoing.app.ondevice.NativeOnDeviceSignal
import com.devvalor.howyoudoing.app.ondevice.NativeOnDeviceSlack
import com.devvalor.howyoudoing.app.ondevice.NativeOnDeviceTelegram
import com.devvalor.howyoudoing.app.ondevice.NativeOnDeviceWebAccount
import com.devvalor.howyoudoing.app.ondevice.NativeOnDeviceWhatsApp
import com.devvalor.howyoudoing.core.domain.network.ChatNetwork
import com.devvalor.howyoudoing.core.domain.ondevice.AccountRef
import com.devvalor.howyoudoing.core.domain.ondevice.CaptchaSolver
import com.devvalor.howyoudoing.core.domain.ondevice.ExtraAccounts
import com.devvalor.howyoudoing.core.domain.ondevice.MAX_ACCOUNTS_PER_NETWORK
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceMessenger
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceNetworks
import com.devvalor.howyoudoing.core.domain.ondevice.OwnTelegramKeys
import kotlinx.coroutines.CoroutineScope

/** The networks that can have a second and third account on the phone. Matrix has its own account screen. */
val MULTI_ACCOUNT_NETWORKS = listOf(
    ChatNetwork.MESSENGER,
    ChatNetwork.WHATSAPP,
    ChatNetwork.INSTAGRAM,
    ChatNetwork.TELEGRAM,
    ChatNetwork.SIGNAL,
    ChatNetwork.SLACK
) + OnDeviceNetworks.ALL

/**
 * Where an account keeps its data: the first account in the folder it always had, the second and third next to it
 * ("on_device_whatsapp_2"). Its saved login is in SharedPreferences and a Keystore key of the same name.
 */
internal val AccountRef.dataDir: String
    get() {
        val first = when (network) {
            ChatNetwork.MESSENGER -> MESSENGER_DIR
            ChatNetwork.WHATSAPP -> WHATSAPP_DIR
            ChatNetwork.INSTAGRAM -> INSTAGRAM_DIR
            ChatNetwork.TELEGRAM -> TELEGRAM_DIR
            ChatNetwork.SIGNAL -> SIGNAL_DIR
            ChatNetwork.SLACK -> SLACK_DIR
            else -> network.dataDir
        }
        return if (slot == 0) first else "${first}_${slot + 1}"
    }

/** The clients of the second and third accounts; each connects (if linked) when created, like the first ones. */
internal fun extraAccountClients(
    context: Context,
    scope: CoroutineScope,
    telegramKeys: OwnTelegramKeys?,
    captchaSolver: CaptchaSolver
): ExtraAccounts = ExtraAccounts(
    MULTI_ACCOUNT_NETWORKS.flatMap { network ->
        (1 until MAX_ACCOUNTS_PER_NETWORK).map { slot ->
            client(AccountRef(network, slot), context, scope, telegramKeys, captchaSolver)
        }
    }
)

private fun client(
    account: AccountRef,
    context: Context,
    scope: CoroutineScope,
    telegramKeys: OwnTelegramKeys?,
    captchaSolver: CaptchaSolver
): OnDeviceMessenger {
    val dir = account.dataDir
    val slot = account.slot
    val data = AndroidDataDirectory(context, dir)
    val secrets = KeystoreSecretStore(context, dir)
    return when (val network = account.network) {
        ChatNetwork.MESSENGER ->
            NativeOnDeviceMessenger(AndroidMessengerFactory(), secrets, data, scope, slot = slot)
        ChatNetwork.INSTAGRAM -> NativeOnDeviceInstagram(AndroidInstagramFactory(), secrets, data, scope, slot)
        ChatNetwork.SLACK -> NativeOnDeviceSlack(AndroidSlackFactory(), secrets, data, scope, slot)
        ChatNetwork.WHATSAPP -> NativeOnDeviceWhatsApp(AndroidWhatsAppFactory(), data, scope, slot)
        ChatNetwork.SIGNAL -> NativeOnDeviceSignal(AndroidSignalFactory(), data, scope, slot)
        ChatNetwork.TELEGRAM -> NativeOnDeviceTelegram(
            telegramKeys?.let { AndroidTelegramFactory(it.apiId, it.apiHash) },
            data,
            scope,
            slot
        )
        ChatNetwork.GOOGLE_MESSAGES -> NativeOnDeviceGoogleMessages(AndroidGoogleMessagesFactory(), data, scope, slot)
        else -> NativeOnDeviceWebAccount(
            network,
            AndroidChatClientFactory(network.goName),
            secrets,
            data,
            scope,
            captchaSolver,
            slot
        )
    }
}
