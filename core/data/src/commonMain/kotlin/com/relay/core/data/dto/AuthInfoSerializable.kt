package com.relay.core.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class AuthInfoSerializable(
    val homeserverUrl: String,
    val userId: String,
    val accessToken: String,
    val deviceId: String,
    val isDemo: Boolean = false
)
