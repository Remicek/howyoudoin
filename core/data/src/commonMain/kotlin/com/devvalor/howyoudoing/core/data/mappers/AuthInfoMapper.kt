package com.devvalor.howyoudoing.core.data.mappers

import com.devvalor.howyoudoing.core.data.dto.AuthInfoSerializable
import com.devvalor.howyoudoing.core.domain.auth.AuthInfo

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
