package info.loveyu.mfca.vpn

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

object MihomoCoreManager {
    private const val API_BASE = "https://api.github.com/repos/MetaCubeX/mihomo/releases"

    fun ensureCore(context: Context, requestedVersion: String): Result<File> {
        return runCatching {
            val release = fetchReleaseInfo(requestedVersion)
            val targetDir = File(context.filesDir, "vpn/mihomo/${release.tag}")
            val targetFile = File(targetDir, "mihomo")
            if (targetFile.exists() && targetFile.canExecute()) {
                return@runCatching targetFile
            }

            targetDir.mkdirs()
            val tempFile = File(targetDir, "download.tmp")
            downloadToFile(release.assetUrl, tempFile)
            extractBinary(tempFile, targetFile)
            tempFile.delete()
            targetFile.setExecutable(true)
            targetFile
        }
    }

    private fun fetchReleaseInfo(requestedVersion: String): ReleaseInfo {
        val normalizedVersion = requestedVersion.trim().ifBlank { "latest" }
        val apiUrl = if (normalizedVersion == "latest") {
            "$API_BASE/latest"
        } else {
            "$API_BASE/tags/$normalizedVersion"
        }
        val json = requestString(apiUrl)
        val payload = JSONObject(json)
        val tag = payload.optString("tag_name").ifBlank { normalizedVersion }
        val assets = payload.optJSONArray("assets") ?: throw IllegalStateException("No release assets for $normalizedVersion")
        val assetUrl = selectAssetUrl(assets) ?: throw IllegalStateException("No Android mihomo asset for ${Build.SUPPORTED_ABIS.firstOrNull()}")
        return ReleaseInfo(tag = tag, assetUrl = assetUrl)
    }

    private fun selectAssetUrl(assets: org.json.JSONArray): String? {
        val preferredKeywords = currentAbiKeywords()
        val candidates = mutableListOf<Pair<String, String>>()
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            val name = asset.optString("name")
            val url = asset.optString("browser_download_url")
            if (name.isBlank() || url.isBlank()) continue
            candidates += name to url
        }

        preferredKeywords.forEach { keyword ->
            candidates.firstOrNull { (name, _) ->
                name.contains("android", ignoreCase = true) &&
                    name.contains(keyword, ignoreCase = true) &&
                    (name.endsWith(".gz") || name.endsWith(".zip"))
            }?.let { return it.second }
        }

        return candidates.firstOrNull { (name, _) ->
            name.contains("android", ignoreCase = true) &&
                (name.endsWith(".gz") || name.endsWith(".zip"))
        }?.second
    }

    private fun currentAbiKeywords(): List<String> {
        return when {
            Build.SUPPORTED_ABIS.any { it.contains("arm64") } -> listOf("arm64", "aarch64")
            Build.SUPPORTED_ABIS.any { it.contains("armeabi") || it.contains("arm") } -> listOf("armv7", "armv7a", "arm")
            Build.SUPPORTED_ABIS.any { it.contains("x86_64") } -> listOf("x86_64", "amd64")
            Build.SUPPORTED_ABIS.any { it.contains("x86") } -> listOf("386", "x86")
            else -> emptyList()
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

    private fun requestString(url: String): String {
        val connection = openConnection(url)
        return try {
            connection.inputStream.bufferedReader().use { reader -> reader.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(url: String): HttpURLConnection {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/vnd.github+json")
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

    private data class ReleaseInfo(
        val tag: String,
        val assetUrl: String,
    )
}
