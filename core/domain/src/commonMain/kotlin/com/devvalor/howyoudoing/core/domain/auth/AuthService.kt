package com.devvalor.howyoudoing.core.domain.auth

import com.devvalor.howyoudoing.core.domain.util.DataError
import com.devvalor.howyoudoing.core.domain.util.EmptyResult
import com.devvalor.howyoudoing.core.domain.util.Result

interface AuthService {
    suspend fun login(
        homeserver: String,
        username: String,
        password: String
    ): Result<AuthInfo, DataError.Remote>

    suspend fun logout(): EmptyResult<DataError.Remote>
}
