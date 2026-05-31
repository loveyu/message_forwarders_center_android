package info.loveyu.mfca.vpn

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

object MihomoCoreManager {
    fun ensureCore(context: Context, coreUrl: String): Result<File> {
        return runCatching {
            val normalizedUrl = coreUrl.trim()
            require(normalizedUrl.isNotEmpty()) { "VPN coreUrl cannot be blank" }
            val sourceUrl = URL(normalizedUrl)
            val targetDir = File(context.filesDir, "vpn/mihomo/${sha256(normalizedUrl)}")
            val targetFile = File(targetDir, "mihomo")
            if (targetFile.exists() && targetFile.canExecute()) {
                return@runCatching targetFile
            }

            targetDir.mkdirs()
            val tempFile = File(targetDir, sourceUrl.path.substringAfterLast('/').ifBlank { "download.tmp" })
            downloadToFile(normalizedUrl, tempFile)
            extractBinary(tempFile, targetFile)
            tempFile.delete()
            targetFile.setExecutable(true)
            targetFile
        }
    }

    private fun downloadToFile(url: String, file: File) {
        val connection = openConnection(url)
        try {
            connection.inputStream.use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(url: String): HttpURLConnection {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "FlowGate-Android")
        }
        if (connection.responseCode !in 200..299) {
            val error = connection.errorStream?.bufferedReader()?.use { it.readText() }
            throw IllegalStateException("Download failed: HTTP ${connection.responseCode}${if (error.isNullOrBlank()) "" else " - $error"}")
        }
        return connection
    }

    private fun extractBinary(source: File, target: File) {
        when {
            source.name.endsWith(".gz") -> {
                GZIPInputStream(source.inputStream()).use { input ->
                    FileOutputStream(target).use { output -> input.copyTo(output) }
                }
            }

            source.name.endsWith(".zip") -> {
                ZipInputStream(source.inputStream()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (!entry.isDirectory && (entry.name.endsWith("/mihomo") || entry.name == "mihomo")) {
                            FileOutputStream(target).use { output -> zip.copyTo(output) }
                            return
                        }
                    }
                }
                throw IllegalStateException("No mihomo binary found in zip package")
            }

            else -> {
                source.copyTo(target, overwrite = true)
            }
        }
    }

    private fun sha256(value: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
