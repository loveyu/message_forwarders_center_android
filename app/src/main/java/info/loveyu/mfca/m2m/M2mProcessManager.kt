package info.loveyu.mfca.m2m

import android.content.Context
import info.loveyu.mfca.plugin.M2mPluginCore
import info.loveyu.mfca.util.LogManager
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest

object M2mProcessManager {
    data class RunningCore(
        val candidateName: String,
        val core: M2mPluginCore,
        val workingDirectory: File,
        val stdoutLogFile: File,
        val stderrLogFile: File,
        @Volatile var stopping: Boolean = false,
    )

    @Volatile private var runningCore: RunningCore? = null
    @Volatile private var lastLogFiles: Pair<File, File>? = null

    fun getLastLogFiles(): Pair<File, File>? = lastLogFiles
    fun isRunning(): Boolean = current() != null

    fun current(): RunningCore? {
        val current = runningCore ?: return null
        if (current.core.isRunning()) {
            return current
        }
        runningCore = null
        return null
    }

    fun start(
        context: Context,
        artifacts: PreparedM2mArtifacts,
        onUnexpectedExit: (exitCode: Int, tail: String) -> Unit,
    ): Result<RunningCore> {
        return runCatching {
            stop()

            val workDir = File(context.filesDir, "vpn/runtime/${sanitize(artifacts.candidate.name)}").apply {
                mkdirs()
            }
            // Copy cached geo data files to the working directory so the mihomo core
            // can find them at the relative paths specified in the source config.
            copyGeoFiles(context, workDir)
            val logDir = File(context.cacheDir, "vpn/${sanitize(artifacts.candidate.name)}").apply {
                mkdirs()
            }
            val stdoutLog = File(logDir, "m2m.stdout.log").apply { writeText("") }
            val stderrLog = File(logDir, "m2m.stderr.log").apply { writeText("") }
            lastLogFiles = Pair(stdoutLog, stderrLog)
            LogManager.logInfo(
                "VPN",
                "Starting m2m plugin for ${artifacts.candidate.name}: plugin=${artifacts.coreFilePath}, profile=${artifacts.profileFilePath}, workDir=${workDir.absolutePath}",
            )

            val core = M2mPluginCore()
            core.load(artifacts.coreFilePath)
            val ret = core.start(buildArgs(workDir = workDir, profileFile = File(artifacts.profileFilePath)), stdoutLog.absolutePath)
            if (ret != 0) {
                throw IllegalStateException("m2m plugin start failed (code $ret): ${readFailureOutput(stdoutLog, stderrLog)}")
            }
            // Enable socket protector AFTER start so the hook survives nativeStart() reinitialization
            if (M2mPluginCore.socketProtector != null) {
                core.setSocketProtector(true)
            }
            Thread.sleep(1500)
            if (!core.isRunning()) {
                throw IllegalStateException("m2m exited immediately: ${readFailureOutput(stdoutLog, stderrLog)}")
            }
            waitForProxyReady(core, artifacts.localProxyPort, stdoutLog, stderrLog)

            RunningCore(
                candidateName = artifacts.candidate.name,
                core = core,
                workingDirectory = workDir,
                stdoutLogFile = stdoutLog,
                stderrLogFile = stderrLog,
            ).also { running ->
                runningCore = running
                Thread {
                    while (!running.stopping && core.isRunning()) {
                        Thread.sleep(1000)
                    }
                    if (!running.stopping) {
                        val tail = readFailureOutput(stdoutLog, stderrLog)
                        runningCore = null
                        onUnexpectedExit(-1, tail)
                    }
                }.apply {
                    isDaemon = true
                    name = "m2m-watch-${sanitize(artifacts.candidate.name)}"
                    start()
                }
            }
        }
    }

    fun stop(): String? {
        val current = runningCore ?: return null
        current.stopping = true
        runningCore = null
        LogManager.logInfo("VPN", "Stopping m2m for ${current.candidateName}")
        current.core.stop()
        val deadline = System.currentTimeMillis() + 1500
        while (System.currentTimeMillis() < deadline && current.core.isRunning()) {
            Thread.sleep(100)
        }
        return current.candidateName
    }

    internal fun buildArgs(workDir: File, profileFile: File): List<String> {
        return listOf(
            "-d",
            workDir.absolutePath,
            "-f",
            profileFile.absolutePath,
        )
    }

    private fun readFailureOutput(stdoutLog: File, stderrLog: File): String {
        val merged = buildString {
            append(stdoutLog.readTailOrEmpty())
            if (isNotBlank() && stderrLog.length() > 0) {
                append('\n')
            }
            append(stderrLog.readTailOrEmpty())
        }.trim()
        return merged.ifBlank { "no output" }
    }

    private fun File.readTailOrEmpty(maxChars: Int = 1200): String {
        if (!exists()) return ""
        val content = runCatching { readText() }.getOrDefault("")
        return if (content.length <= maxChars) content else content.takeLast(maxChars)
    }

    private fun sanitize(value: String): String = value.replace(Regex("[^a-zA-Z0-9._-]"), "_")

    private fun waitForProxyReady(core: M2mPluginCore, port: Int, stdoutLog: File, stderrLog: File) {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            if (!core.isRunning()) {
                throw IllegalStateException(
                    "m2m exited before proxy became ready: ${readFailureOutput(stdoutLog, stderrLog)}",
                )
            }
            if (canConnect(port)) {
                return
            }
            Thread.sleep(200)
        }
        throw IllegalStateException("Timed out waiting for local proxy 127.0.0.1:$port")
    }

    private fun canConnect(port: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), 300)
            }
            true
        } catch (_: IOException) {
            false
        }
    }

    private fun copyGeoFiles(context: Context, workDir: File) {
        val geoDir = File(context.filesDir, "vpn/geo")
        if (!geoDir.isDirectory) return
        val cachedFiles = geoDir.listFiles()?.filter { it.isFile && !it.name.endsWith(".hash") } ?: return
        val processed = mutableSetOf<String>()
        for (source in cachedFiles) {
            val target = File(workDir, source.name)
            // Use pre-computed hash from download time; auto-generate if missing (backward compat)
            var sourceHash = GeoFileManager.readSavedHash(source)
            if (sourceHash == null) {
                sourceHash = source.md5()
                val hashFile = File(geoDir, "${source.name}.hash")
                runCatching { hashFile.writeText(sourceHash) }
            }
            val storedHash = target.readSavedHash()
            if (sourceHash == storedHash && target.exists() && target.length() == source.length()) {
                LogManager.logDebug("VPN", "Skipping ${source.name}, hash unchanged")
            } else {
                runCatching {
                    source.copyTo(target, overwrite = true)
                    // Copy the hash sidecar too
                    val sourceHashFile = File(geoDir, "${source.name}.hash")
                    if (sourceHashFile.exists()) {
                        sourceHashFile.copyTo(File(workDir, "${source.name}.hash"), overwrite = true)
                    }
                    LogManager.logDebug("VPN", "Copied geo file ${source.name} to working directory")
                }
            }
            processed.add(source.name)
        }
        // Clean stale runtime files and their .hash sidecars
        workDir.listFiles()?.forEach { file ->
            if (file.isFile && file.name !in processed && !file.name.endsWith(".hash")) {
                runCatching {
                    file.delete()
                    File(workDir, "${file.name}.hash").delete()
                }
            }
        }
    }

    private fun File.md5(): String {
        val md = MessageDigest.getInstance("MD5")
        inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                md.update(buffer, 0, read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun File.readSavedHash(): String? {
        val hashFile = File(parentFile, "${name}.hash")
        return if (hashFile.exists()) hashFile.readText().trim() else null
    }
}
