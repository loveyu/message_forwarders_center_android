package info.loveyu.mfca.util

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ConfigBackupManager {

    private const val BACKUP_DIR = "config_backups"
    private const val BACKUP_PREFIX = "config_backup_"
    private const val BACKUP_SUFFIX = ".yaml"

    data class BackupInfo(
        val file: File,
        val timestamp: Date,
        val displayName: String
    )

    fun getBackupDir(context: Context): File {
        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(baseDir, BACKUP_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun backupCurrentConfig(context: Context, configJson: String): BackupInfo? {
        return try {
            val backupDir = getBackupDir(context)
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val backupFile = File(backupDir, "$BACKUP_PREFIX$timestamp$BACKUP_SUFFIX")

            backupFile.writeText(configJson)

            BackupInfo(
                file = backupFile,
                timestamp = Date(),
                displayName = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            )
        } catch (e: Exception) {
            null
        }
    }

    fun listBackups(context: Context): List<BackupInfo> {
        val backupDir = getBackupDir(context)
        val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())

        return backupDir.listFiles()
            ?.filter { it.name.startsWith(BACKUP_PREFIX) && it.name.endsWith(BACKUP_SUFFIX) }
            ?.mapNotNull { file ->
                try {
                    val timestampStr = file.name
                        .removePrefix(BACKUP_PREFIX)
                        .removeSuffix(BACKUP_SUFFIX)
                    val timestamp = dateFormat.parse(timestampStr) ?: Date(file.lastModified())
                    val displayName = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(timestamp)

                    BackupInfo(
                        file = file,
                        timestamp = timestamp,
                        displayName = displayName
                    )
                } catch (e: Exception) {
                    null
                }
            }
            ?.sortedByDescending { it.timestamp }
            ?: emptyList()
    }

    fun restoreBackup(backup: BackupInfo): String? {
        return try {
            backup.file.readText()
        } catch (e: Exception) {
            null
        }
    }

    fun deleteBackup(backup: BackupInfo): Boolean {
        return try {
            backup.file.delete()
        } catch (e: Exception) {
            false
        }
    }

    fun clearAllBackups(context: Context): Boolean {
        return try {
            val backupDir = getBackupDir(context)
            backupDir.listFiles()?.forEach { it.delete() }
            true
        } catch (e: Exception) {
            false
        }
    }
}
