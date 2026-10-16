package com.relay.core.domain.auth

import kotlinx.coroutines.flow.Flow

interface SessionStorage {
    fun observeAuthInfo(): Flow<AuthInfo?>
    suspend fun get(): AuthInfo?
    suspend fun set(info: AuthInfo?)
}
