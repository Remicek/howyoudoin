package com.relay.core.domain.auth

import com.relay.core.domain.util.DataError
import com.relay.core.domain.util.EmptyResult
import com.relay.core.domain.util.Result

interface AuthService {
    suspend fun login(
        homeserver: String,
        username: String,
        password: String
    ): Result<AuthInfo, DataError.Remote>

    suspend fun logout(): EmptyResult<DataError.Remote>
}
