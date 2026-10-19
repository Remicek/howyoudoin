package com.devvalor.howyoudoing.core.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class AuthInfoSerializable(
    val homeserverUrl: String,
    val userId: String,
    val accessToken: String,
    val deviceId: String,
    val isDemo: Boolean = false,
    val isLocal: Boolean = false
)
