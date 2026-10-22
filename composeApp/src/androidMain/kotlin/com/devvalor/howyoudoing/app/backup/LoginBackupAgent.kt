package com.devvalor.howyoudoing.app.backup

import android.app.backup.BackupAgent
import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.app.backup.FullBackupDataOutput
import android.content.Context
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * The app's backup agent: Auto Backup as usual (the XML rules decide what's included), plus a note of when the
 * phone last backed the app up. Apps can't ask whether Google backup is on; a backup that happened is the proof.
 */
class LoginBackupAgent : BackupAgent() {

    override fun onFullBackup(data: FullBackupDataOutput) {
        super.onFullBackup(data)
        runCatching { markerFile(this).writeText(System.currentTimeMillis().toString()) }
    }

    // Auto Backup only (fullBackupOnly): the key-value calls aren't used
    override fun onBackup(oldState: ParcelFileDescriptor?, data: BackupDataOutput?, newState: ParcelFileDescriptor?) = Unit

    override fun onRestore(data: BackupDataInput?, appVersionCode: Int, newState: ParcelFileDescriptor?) = Unit

    companion object {
        /** In no-backup storage, so a restore doesn't bring back the old phone's time. */
        private fun markerFile(context: Context) = File(context.noBackupFilesDir, "login-backup-last")

        fun lastBackupMillis(context: Context): Long? =
            markerFile(context).takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull()
    }
}
