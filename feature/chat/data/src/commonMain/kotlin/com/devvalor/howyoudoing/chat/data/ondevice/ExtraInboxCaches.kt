package com.devvalor.howyoudoing.chat.data.ondevice

import com.devvalor.howyoudoing.core.domain.ondevice.AccountRef

/** The inbox caches of the networks' second and third accounts on the phone, by account. */
data class ExtraInboxCaches(val caches: Map<AccountRef, OnDeviceInboxCache> = emptyMap())
