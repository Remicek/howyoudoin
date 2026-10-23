package com.devvalor.howyoudoing.chat.data.matrix

import com.devvalor.howyoudoing.chat.domain.MatrixEncryption
import com.devvalor.howyoudoing.chat.domain.MatrixEncryptionStatus
import kotlinx.coroutines.flow.Flow

class MatrixAccountEncryption(private val engine: MatrixSyncEngine) : MatrixEncryption {
    override val status: Flow<MatrixEncryptionStatus> get() = engine.encryption
    override suspend fun verifyWithRecoveryKey(recoveryKey: String): Result<Int> = engine.verifyWithRecoveryKey(recoveryKey)
}
