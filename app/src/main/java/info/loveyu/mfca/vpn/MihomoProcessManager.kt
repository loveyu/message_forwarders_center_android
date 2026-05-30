package info.loveyu.mfca.vpn

import android.content.Context
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.io.File
import java.util.concurrent.TimeUnit

object MihomoProcessManager {
    data class RunningProcess(
        val candidateName: String,
        val process: Process,
        val workingDirectory: File,
        val stdoutLogFile: File,
        val stderrLogFile: File,
        @Volatile var stopping: Boolean = false,
    )

    @Volatile private var runningProcess: RunningProcess? = null

    @Synchronized
    fun current(): RunningProcess? {
        val current = runningProcess ?: return null
        if (current.process.isAlive) {
            return current
        }
        runningProcess = null
        return null
    }

    @Synchronized
    fun start(
        context: Context,
        artifacts: PreparedVpnArtifacts,
        onUnexpectedExit: (exitCode: Int, tail: String) -> Unit,
    ): Result<RunningProcess> {
        return runCatching {
            stop()

            val workDir = File(context.filesDir, "vpn/runtime/${sanitize(artifacts.candidate.name)}").apply {
                mkdirs()
            }
            val stdoutLog = File(workDir, "mihomo.stdout.log").apply { writeText("") }
            val stderrLog = File(workDir, "mihomo.stderr.log").apply { writeText("") }

            val process = ProcessBuilder(
                buildCommand(
                    coreFile = File(artifacts.coreFilePath),
                    workDir = workDir,
                    profileFile = File(artifacts.profileFilePath),
                ),
            )
                .directory(workDir)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(stdoutLog))
                .redirectError(ProcessBuilder.Redirect.appendTo(stderrLog))
                .apply {
                    environment()["HOME"] = workDir.absolutePath
                }
                .start()

            if (process.waitFor(1500, TimeUnit.MILLISECONDS)) {
                throw IllegalStateException(
                    "Mihomo exited immediately (${process.exitValue()}): ${readFailureOutput(stdoutLog, stderrLog)}",
                )
            }
            waitForProxyReady(process, artifacts.localProxyPort, stdoutLog, stderrLog)

            RunningProcess(
                candidateName = artifacts.candidate.name,
                process = process,
                workingDirectory = workDir,
                stdoutLogFile = stdoutLog,
                stderrLogFile = stderrLog,
            ).also { running ->
                runningProcess = running
                Thread {
                    val exitCode = process.waitFor()
                    val tail = readFailureOutput(stdoutLog, stderrLog)
                    synchronized(this) {
                        if (runningProcess?.process == process) {
                            runningProcess = null
                        }
                    }
                    if (!running.stopping) {
                        onUnexpectedExit(exitCode, tail)
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
        val current = runningProcess ?: return null
        current.stopping = true
        runningProcess = null
        current.process.destroy()
        if (!current.process.waitFor(1500, TimeUnit.MILLISECONDS)) {
            current.process.destroyForcibly()
            current.process.waitFor(1500, TimeUnit.MILLISECONDS)
        }
        return current.candidateName
    }

    internal fun buildCommand(coreFile: File, workDir: File, profileFile: File): List<String> {
        return listOf(
            coreFile.absolutePath,
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

    private fun waitForProxyReady(process: Process, port: Int, stdoutLog: File, stderrLog: File) {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive) {
                throw IllegalStateException(
                    "Mihomo exited before proxy became ready (${process.exitValue()}): ${readFailureOutput(stdoutLog, stderrLog)}",
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
