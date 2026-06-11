package info.loveyu.mfca.m2m

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.ParcelFileDescriptor
import android.system.Os
import android.util.Log
import info.loveyu.mfca.util.LogManager
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

object M2mBridgeProcessManager {
    data class RunningProcess(
        val candidateName: String,
        val process: Process,
        val controlServer: LocalServerSocket,
        val controlSocket: LocalSocket,
        val workingDirectory: File,
        val stdoutLogFile: File,
        val stderrLogFile: File,
        @Volatile var stopping: Boolean = false,
    )

    @Volatile private var runningProcess: RunningProcess? = null
    @Volatile private var lastLogFiles: Pair<File, File>? = null

    fun getLastLogFiles(): Pair<File, File>? = lastLogFiles

    fun isRunning(): Boolean = current() != null

    fun current(): RunningProcess? {
        val current = runningProcess ?: return null
        if (current.process.isAlive) {
            return current
        }
        runningProcess = null
        return null
    }

    fun start(
        context: Context,
        artifacts: PreparedM2mArtifacts,
        tunInterface: ParcelFileDescriptor,
        onUnexpectedExit: (exitCode: Int, tail: String) -> Unit,
    ): Result<RunningProcess> {
        return runCatching {
            stop()

            val bridgeBinary = ensureBridgeBinary(context)
            val workDir = File(context.filesDir, "vpn/runtime/${sanitize(artifacts.candidate.name)}/bridge").apply {
                mkdirs()
            }
            val logDir = File(context.cacheDir, "vpn/${sanitize(artifacts.candidate.name)}/bridge").apply {
                mkdirs()
            }
            val stdoutLog = File(logDir, "bridge.stdout.log").apply { writeText("") }
            val stderrLog = File(logDir, "bridge.stderr.log").apply { writeText("") }
            lastLogFiles = Pair(stdoutLog, stderrLog)
            val controlName = "mfca_vpn_${UUID.randomUUID().toString().replace("-", "")}"
            val controlServer = LocalServerSocket(controlName)
            LogManager.logInfo(
                "VPN",
                "Starting VPN bridge for ${artifacts.candidate.name}: binary=${bridgeBinary.absolutePath}, workDir=${workDir.absolutePath}, socks=127.0.0.1:${artifacts.localProxyPort}, udpRelay=${artifacts.udpRelay}, dnsHijack=${artifacts.dnsHijack}",
            )

            val command = buildList {
                    add(bridgeBinary.absolutePath)
                    add("--control-socket")
                    add(controlName)
                    add("--config")
                    add(writeConfigFile(workDir, artifacts).absolutePath)
                }
            LogManager.logDebug("VPN", "Bridge command: ${command.joinToString(" ")}")
            val process = ProcessBuilder(command)
                .directory(workDir)
                .apply {
                    environment()["HOME"] = workDir.absolutePath
                }
                .start()

            val candidateTag = sanitize(artifacts.candidate.name)
            Thread({
                pumpStream(process.inputStream, stdoutLog, false)
            }, "bridge-out-$candidateTag").apply { isDaemon = true; start() }
            Thread({
                pumpStream(process.errorStream, stderrLog, true)
            }, "bridge-err-$candidateTag").apply { isDaemon = true; start() }

            // Accept with timeout: close the server socket to unblock accept() if needed
            val acceptResult = acceptWithTimeout(controlServer, 10_000L)
            if (acceptResult == null) {
                process.destroy()
                process.waitFor(1500, TimeUnit.MILLISECONDS)
                controlServer.close()
                throw IllegalStateException(
                    "VPN bridge did not connect to control socket within timeout: ${readFailureOutput(stdoutLog, stderrLog)}",
                )
            }
            val controlSocket = acceptResult
            controlSocket.setFileDescriptorsForSend(arrayOf(tunInterface.fileDescriptor))
            controlSocket.outputStream.write(byteArrayOf(1))
            controlSocket.outputStream.flush()

            if (process.waitFor(1500, TimeUnit.MILLISECONDS)) {
                controlSocket.close()
                controlServer.close()
                throw IllegalStateException(
                    "VPN bridge exited immediately (${process.exitValue()}): ${readFailureOutput(stdoutLog, stderrLog)}",
                )
            }

            val running = RunningProcess(
                candidateName = artifacts.candidate.name,
                process = process,
                controlServer = controlServer,
                controlSocket = controlSocket,
                workingDirectory = workDir,
                stdoutLogFile = stdoutLog,
                stderrLogFile = stderrLog,
            )
            runningProcess = running
            Thread {
                val exitCode = process.waitFor()
                val tail = readFailureOutput(stdoutLog, stderrLog)
                if (!running.stopping) {
                    runningProcess = null
                    onUnexpectedExit(exitCode, tail)
                }
            }.apply {
                isDaemon = true
                name = "vpn-bridge-watch-${sanitize(artifacts.candidate.name)}"
                start()
            }
            running
        }
    }

    /**
     * Accept a connection on [controlServer] with a timeout of [timeoutMs] milliseconds.
     * Uses a dedicated thread so the caller is not blocked indefinitely and [stop] can close
     * the server socket to unblock the accept if the bridge process fails to connect.
     */
    private fun acceptWithTimeout(
        controlServer: LocalServerSocket,
        timeoutMs: Long,
    ): LocalSocket? {
        var socket: LocalSocket? = null
        var error: Exception? = null
        val thread = Thread({
            try {
                socket = controlServer.accept()
            } catch (e: Exception) {
                error = e
            }
        }, "vpn-bridge-accept").apply { isDaemon = true; start() }
        thread.join(timeoutMs)
        if (thread.isAlive) {
            // Timeout: close the server socket to unblock accept()
            runCatching { controlServer.close() }
            thread.join(1500)
            return null
        }
        error?.let { throw it }
        return socket
    }

    fun stop(): String? {
        val current = runningProcess ?: return null
        current.stopping = true
        runningProcess = null
        LogManager.logInfo("VPN", "Stopping VPN bridge for ${current.candidateName}")
        runCatching { current.controlSocket.close() }
        runCatching { current.controlServer.close() }
        current.process.destroy()
        if (!current.process.waitFor(1500, TimeUnit.MILLISECONDS)) {
            current.process.destroyForcibly()
            current.process.waitFor(1500, TimeUnit.MILLISECONDS)
        }
        return current.candidateName
    }

    private fun pumpStream(input: java.io.InputStream, logFile: File, isStderr: Boolean) {
        try {
            val reader = BufferedReader(input.reader())
            val writer = BufferedWriter(FileWriter(logFile, true))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val l = line!!
                writer.write(l)
                writer.newLine()
                writer.flush()
                if (isStderr) {
                    Log.w("M2MB", l)
                } else {
                    Log.i("M2MB", l)
                }
            }
            writer.close()
            reader.close()
        } catch (_: IOException) {
            // Stream closed when process exits
        }
    }

    private fun ensureBridgeBinary(context: Context): File {
        val lib = File(context.applicationInfo.nativeLibraryDir, "libvpnbridge.so")
        require(lib.exists()) { "内置 vpnbridge 不存在: ${lib.absolutePath}" }
        val symlink = File(context.filesDir, "vpn/core/vpnbridge")
        symlink.parentFile?.mkdirs()
        symlink.delete()
        Os.symlink(lib.absolutePath, symlink.absolutePath)
        return symlink
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

    private fun writeConfigFile(workDir: File, artifacts: PreparedM2mArtifacts): File {
        val config = buildString {
            appendLine("tunnel:")
            appendLine("  mtu: ${MfcaM2mService.TUN_MTU}")
            appendLine("  ipv4: '${MfcaM2mService.TUN_GATEWAY}'")
            appendLine("socks5:")
            appendLine("  port: ${artifacts.localProxyPort}")
            appendLine("  address: '127.0.0.1'")
            if (artifacts.udpRelay) {
                appendLine("  udp: 'udp'")
            }
            if (artifacts.dnsHijack) {
                appendLine("mapdns:")
                appendLine("  address: '${MfcaM2mService.TUN_DNS_PRIMARY}'")
                appendLine("  port: 53")
                appendLine("  network: '${MfcaM2mService.MAPDNS_NETWORK}'")
                appendLine("  netmask: '${MfcaM2mService.MAPDNS_NETMASK}'")
                appendLine("  cache-size: ${MfcaM2mService.MAPDNS_CACHE_SIZE}")
            }
            appendLine("misc:")
            val bridgeLogLevel = when (artifacts.logLevel) {
                M2mLogLevel.debug -> "debug"
                M2mLogLevel.info -> "info"
                M2mLogLevel.warning -> "warn"
                M2mLogLevel.error -> "error"
                M2mLogLevel.silent -> "off"
                null -> "warn"
            }
            appendLine("  log-level: '$bridgeLogLevel'")
        }
        val configFile = File(workDir, "config.yml")
        configFile.writeText(config)
        return configFile
    }
}
