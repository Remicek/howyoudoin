package com.devvalor.howyoudoing.core.domain.ondevice

/** Every network the app can connect straight from the phone, for screens that list or link them. */
data class OnDeviceAccounts(
    val messenger: OnDeviceMessenger,
    val whatsApp: OnDeviceWhatsApp,
    val instagram: OnDeviceInstagram,
    val telegram: OnDeviceTelegram,
    val signal: OnDeviceSignal,
    val slack: OnDeviceSlack,
    val matrix: OnDeviceMatrix
) {
    val all: List<OnDeviceMessenger> get() = listOf(messenger, whatsApp, instagram, telegram, signal, slack, matrix)
}
