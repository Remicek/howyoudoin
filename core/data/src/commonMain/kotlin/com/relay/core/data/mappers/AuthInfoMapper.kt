package com.relay.core.data.mappers

import com.relay.core.data.dto.AuthInfoSerializable
import com.relay.core.domain.auth.AuthInfo

fun AuthInfoSerializable.toDomain(): AuthInfo {
    return AuthInfo(
        homeserverUrl = homeserverUrl,
        userId = userId,
        accessToken = accessToken,
        deviceId = deviceId,
        isDemo = isDemo
    )
}

fun AuthInfo.toSerializable(): AuthInfoSerializable {
    return AuthInfoSerializable(
        homeserverUrl = homeserverUrl,
        userId = userId,
        accessToken = accessToken,
        deviceId = deviceId,
        isDemo = isDemo
    )
}
