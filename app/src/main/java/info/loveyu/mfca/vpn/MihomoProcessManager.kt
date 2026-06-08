package info.loveyu.mfca.vpn

import android.content.Context
import info.loveyu.mfca.plugin.MihomoPluginCore
import info.loveyu.mfca.util.LogManager
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

object MihomoProcessManager {
    data class RunningCore(
        val candidateName: String,
        val core: MihomoPluginCore,
        val workingDirectory: File,
        val stdoutLogFile: File,
        val stderrLogFile: File,
        @Volatile var stopping: Boolean = false,
    )

    @Volatile private var runningCore: RunningCore? = null
    @Volatile private var lastLogFiles: Pair<File, File>? = null

    fun getLastLogFiles(): Pair<File, File>? = lastLogFiles
    fun isRunning(): Boolean = current() != null

    @Synchronized
    fun current(): RunningCore? {
        val current = runningCore ?: return null
        if (current.core.isRunning()) {
            return current
        }
        runningCore = null
        return null
    }

    @Synchronized
    fun start(
        context: Context,
        artifacts: PreparedVpnArtifacts,
        onUnexpectedExit: (exitCode: Int, tail: String) -> Unit,
    ): Result<RunningCore> {
        return runCatching {
            stop()

            val workDir = File(context.filesDir, "vpn/runtime/${sanitize(artifacts.candidate.name)}").apply {
                mkdirs()
            }
            val stdoutLog = File(workDir, "mihomo.stdout.log").apply { writeText("") }
            val stderrLog = File(workDir, "mihomo.stderr.log").apply { writeText("") }
            lastLogFiles = Pair(stdoutLog, stderrLog)
            LogManager.logInfo(
                "VPN",
                "Starting mihomo plugin for ${artifacts.candidate.name}: plugin=${artifacts.coreFilePath}, profile=${artifacts.profileFilePath}, workDir=${workDir.absolutePath}",
            )

            val core = MihomoPluginCore()
            core.load(artifacts.coreFilePath)
            val ret = core.start(buildArgs(workDir = workDir, profileFile = File(artifacts.profileFilePath)), stdoutLog.absolutePath)
            if (ret != 0) {
                throw IllegalStateException("Mihomo plugin start failed (code $ret): ${readFailureOutput(stdoutLog, stderrLog)}")
            }
            Thread.sleep(1500)
            if (!core.isRunning()) {
                throw IllegalStateException("Mihomo exited immediately: ${readFailureOutput(stdoutLog, stderrLog)}")
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
                        synchronized(this) {
                            if (runningCore?.core == core) {
                                runningCore = null
                            }
                        }
                        onUnexpectedExit(-1, tail)
                    }
                }.apply {
                    isDaemon = true
                    name = "mihomo-watch-${sanitize(artifacts.candidate.name)}"
                    start()
                }
            }
        }
    }

    @Synchronized
    fun stop(): String? {
        val current = runningCore ?: return null
        current.stopping = true
        runningCore = null
        LogManager.logInfo("VPN", "Stopping mihomo for ${current.candidateName}")
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

    private fun waitForProxyReady(core: MihomoPluginCore, port: Int, stdoutLog: File, stderrLog: File) {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            if (!core.isRunning()) {
                throw IllegalStateException(
                    "Mihomo exited before proxy became ready: ${readFailureOutput(stdoutLog, stderrLog)}",
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
}
