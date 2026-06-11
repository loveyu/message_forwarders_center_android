package info.loveyu.mfca.vpn

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.ParcelFileDescriptor
import android.system.Os
import info.loveyu.mfca.util.LogManager
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

object VpnBridgeProcessManager {
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
        artifacts: PreparedVpnArtifacts,
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
                .redirectOutput(ProcessBuilder.Redirect.appendTo(stdoutLog))
                .redirectError(ProcessBuilder.Redirect.appendTo(stderrLog))
                .apply {
                    environment()["HOME"] = workDir.absolutePath
                }
                .start()

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

    private fun writeConfigFile(workDir: File, artifacts: PreparedVpnArtifacts): File {
        val config = buildString {
            appendLine("tunnel:")
            appendLine("  mtu: ${MfcaVpnService.TUN_MTU}")
            appendLine("  ipv4: '${MfcaVpnService.TUN_GATEWAY}'")
            appendLine("socks5:")
            appendLine("  port: ${artifacts.localProxyPort}")
            appendLine("  address: '127.0.0.1'")
            if (artifacts.udpRelay) {
                appendLine("  udp: 'udp'")
            }
            if (artifacts.dnsHijack) {
                appendLine("mapdns:")
                appendLine("  address: '${MfcaVpnService.TUN_DNS_PRIMARY}'")
                appendLine("  port: 53")
                appendLine("  network: '${MfcaVpnService.MAPDNS_NETWORK}'")
                appendLine("  netmask: '${MfcaVpnService.MAPDNS_NETMASK}'")
                appendLine("  cache-size: ${MfcaVpnService.MAPDNS_CACHE_SIZE}")
            }
            appendLine("misc:")
            val bridgeLogLevel = when (artifacts.logLevel) {
                VpnLogLevel.debug -> "debug"
                VpnLogLevel.info -> "info"
                VpnLogLevel.warning -> "warn"
                VpnLogLevel.error -> "error"
                VpnLogLevel.silent -> "off"
                null -> "warn"
            }
            appendLine("  log-level: '$bridgeLogLevel'")
        }
        val configFile = File(workDir, "config.yml")
        configFile.writeText(config)
        return configFile
    }
}