package info.loveyu.mfca.util

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

suspend fun exportAppDataToZip(context: Context, outputUri: Uri): String? {
    return withContext(Dispatchers.IO) {
        try {
            val filesDir = context.getExternalFilesDir(null) ?: context.filesDir
            val outputStream =
                context.contentResolver.openOutputStream(outputUri) ?: return@withContext null
            ZipOutputStream(BufferedOutputStream(outputStream)).use { zipOut ->
                zipDirectory(filesDir, filesDir.name, zipOut)
            }
            val cursor = context.contentResolver.query(outputUri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val displayNameIndex =
                        it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (displayNameIndex >= 0) {
                        return@withContext it.getString(displayNameIndex)
                    }
                }
            }
            return@withContext outputUri.lastPathSegment
        } catch (e: Exception) {
            LogManager.logError("SETTINGS", "Export error: ${e.message}")
            null
        }
    }
}

private fun zipDirectory(
    sourceDir: File,
    entryName: String,
    zipOut: ZipOutputStream,
    bufferSize: Int = 8192
) {
    val files = sourceDir.listFiles() ?: return
    for (file in files) {
        val entryPath = if (entryName.isEmpty()) file.name else "$entryName/${file.name}"
        when {
            file.isDirectory -> {
                zipDirectory(file, entryPath, zipOut, bufferSize)
            }
            file.isFile -> {
                try {
                    BufferedInputStream(FileInputStream(file), bufferSize).use { input ->
                        val entry = ZipEntry(entryPath)
                        entry.time = file.lastModified()
                        zipOut.putNextEntry(entry)
                        val buffer = ByteArray(bufferSize)
                        var len: Int
                        while (input.read(buffer).also { len = it } != -1) {
                            zipOut.write(buffer, 0, len)
                        }
                        zipOut.closeEntry()
                    }
                } catch (e: Exception) {
                    LogManager.logWarn("SETTINGS", "Failed to zip file ${file.name}: ${e.message}")
                }
            }
        }
    }
}
