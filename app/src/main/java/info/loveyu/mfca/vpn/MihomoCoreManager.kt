package info.loveyu.mfca.vpn

import android.content.Context
import info.loveyu.mfca.util.LogManager
import info.loveyu.mfca.util.StoragePathResolver
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

object MihomoCoreManager {
    private data class CorePaths(
        val cacheDir: File,
        val executableFile: File,
        val archiveFile: File,
    )

    private data class CoreSource(
        val type: VpnCoreSourceType,
        val original: String,
        val file: File? = null,
    )

    fun inspectCore(context: Context, coreUrl: String): VpnCoreState {
        return runCatching {
            val normalizedUrl = coreUrl.trim()
            require(normalizedUrl.isNotEmpty()) { "VPN coreUrl cannot be blank" }
            val paths = buildPaths(context, normalizedUrl)
            val source = resolveSource(context, normalizedUrl)
            VpnCoreState(
                source = normalizedUrl,
                sourceType = source.type,
                resolvedSourcePath = source.file?.absolutePath,
                cachePath = paths.executableFile.takeIf { it.exists() && it.canExecute() }?.absolutePath,
                archivePath = paths.archiveFile.takeIf { it.exists() }?.absolutePath,
                sourceExists = source.file?.exists() ?: true,
                isReady = paths.executableFile.exists() && paths.executableFile.canExecute(),
            )
        }.getOrElse { error ->
            VpnCoreState(
                source = coreUrl,
                sourceType = if (isRemoteSource(coreUrl)) VpnCoreSourceType.remote else VpnCoreSourceType.local,
                sourceExists = false,
                isReady = false,
                errorMessage = error.message,
            )
        }
    }

    fun ensureCore(context: Context, coreUrl: String, forceRefresh: Boolean = false): Result<File> {
        return runCatching {
            val normalizedUrl = coreUrl.trim()
            require(normalizedUrl.isNotEmpty()) { "VPN coreUrl cannot be blank" }
            val paths = buildPaths(context, normalizedUrl)
            val targetFile = paths.executableFile
            if (!forceRefresh && targetFile.exists() && targetFile.canExecute()) {
                LogManager.logInfo("VPN", "Using cached mihomo core: ${targetFile.absolutePath}")
                return@runCatching targetFile
            }

            paths.cacheDir.mkdirs()
            val source = resolveSource(context, normalizedUrl)
            when {
                !forceRefresh && paths.archiveFile.exists() -> {
                    LogManager.logInfo("VPN", "Restoring mihomo core from cached package: ${paths.archiveFile.absolutePath}")
                }

                source.type == VpnCoreSourceType.remote -> {
                    LogManager.logInfo("VPN", "Downloading mihomo core from $normalizedUrl to ${paths.archiveFile.absolutePath}")
                    downloadToFile(normalizedUrl, paths.archiveFile)
                }

                else -> {
                    val sourceFile = source.file
                        ?: throw IllegalStateException("Local core source was not resolved: $normalizedUrl")
                    if (!sourceFile.exists()) {
                        throw IllegalStateException(
                            "VPN core source does not exist: ${sourceFile.absolutePath}",
                        )
                    }
                    LogManager.logInfo(
                        "VPN",
                        "Copying mihomo core from ${sourceFile.absolutePath} to ${paths.archiveFile.absolutePath}",
                    )
                    sourceFile.copyTo(paths.archiveFile, overwrite = true)
                }
            }

            LogManager.logInfo("VPN", "Extracting mihomo core to ${targetFile.absolutePath}")
            extractBinary(paths.archiveFile, targetFile)
            targetFile.setExecutable(true)
            targetFile
        }
    }

    fun deleteCore(context: Context, coreUrl: String): Result<Unit> {
        return runCatching {
            val normalizedUrl = coreUrl.trim()
            require(normalizedUrl.isNotEmpty()) { "VPN coreUrl cannot be blank" }
            val paths = buildPaths(context, normalizedUrl)
            if (paths.cacheDir.exists()) {
                LogManager.logInfo("VPN", "Deleting cached mihomo core: ${paths.cacheDir.absolutePath}")
                paths.cacheDir.deleteRecursively()
            }
        }
    }

    private fun downloadToFile(url: String, file: File) {
        file.parentFile?.mkdirs()
        val tempFile = File(file.parentFile, "${file.name}.downloading")
        val connection = openConnection(url)
        try {
            connection.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }
            if (!tempFile.renameTo(file)) {
                tempFile.copyTo(file, overwrite = true)
                tempFile.delete()
            }
        } finally {
            tempFile.delete()
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
        target.parentFile?.mkdirs()
        val tempFile = File(target.parentFile, "${target.name}.extracting")
        when {
            source.name.endsWith(".gz") -> {
                GZIPInputStream(source.inputStream()).use { input ->
                    FileOutputStream(tempFile).use { output -> input.copyTo(output) }
                }
            }

            source.name.endsWith(".zip") -> {
                ZipInputStream(source.inputStream()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (!entry.isDirectory && (entry.name.endsWith("/mihomo") || entry.name == "mihomo")) {
                            FileOutputStream(tempFile).use { output -> zip.copyTo(output) }
                            finalizeExtractedFile(tempFile, target)
                            return
                        }
                    }
                }
                tempFile.delete()
                throw IllegalStateException("No mihomo binary found in zip package")
            }

            else -> {
                source.copyTo(tempFile, overwrite = true)
            }
        }
        finalizeExtractedFile(tempFile, target)
    }

    private fun finalizeExtractedFile(tempFile: File, target: File) {
        if (!tempFile.renameTo(target)) {
            tempFile.copyTo(target, overwrite = true)
            tempFile.delete()
        }
    }

    private fun buildPaths(context: Context, coreUrl: String): CorePaths {
        val cacheDir = File(context.filesDir, "vpn/mihomo/${sha256(coreUrl)}")
        return CorePaths(
            cacheDir = cacheDir,
            executableFile = File(cacheDir, "mihomo"),
            archiveFile = File(cacheDir, archiveFileName(coreUrl)),
        )
    }

    private fun resolveSource(context: Context, coreUrl: String): CoreSource {
        return if (isRemoteSource(coreUrl)) {
            CoreSource(type = VpnCoreSourceType.remote, original = coreUrl)
        } else {
            CoreSource(
                type = VpnCoreSourceType.local,
                original = coreUrl,
                file = StoragePathResolver.resolveFile(context, coreUrl),
            )
        }
    }

    private fun isRemoteSource(coreUrl: String): Boolean {
        val normalized = coreUrl.trim().lowercase()
        return normalized.startsWith("http://") || normalized.startsWith("https://")
    }

    private fun archiveFileName(coreUrl: String): String {
        val rawName = runCatching { URL(coreUrl).path.substringAfterLast('/') }
            .getOrDefault(coreUrl.substringAfterLast('/'))
            .ifBlank { "mihomo-core.bin" }
        val sanitized = rawName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return if (sanitized.isBlank()) "mihomo-core.bin" else sanitized
    }

    private fun sha256(value: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
