package com.devvalor.howyoudoing.core.domain.auth

import kotlinx.coroutines.flow.Flow

interface SessionStorage {
    fun observeAuthInfo(): Flow<AuthInfo?>
    suspend fun get(): AuthInfo?
    suspend fun set(info: AuthInfo?)
}

/** Koin name of the [SessionStorage] holding the Matrix account connected as a network (see OnDeviceMatrix). */
const val MATRIX_ACCOUNT_SESSION = "matrixAccountSession"
