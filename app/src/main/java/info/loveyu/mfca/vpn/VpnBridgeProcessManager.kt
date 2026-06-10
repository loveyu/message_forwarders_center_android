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
        tunInterface: ParcelFileDescriptor,
        onUnexpectedExit: (exitCode: Int, tail: String) -> Unit,
    ): Result<RunningProcess> {
        return runCatching {
            stop()

            val bridgeBinary = ensureBridgeBinary(context)
            val workDir = File(context.filesDir, "vpn/runtime/${sanitize(artifacts.candidate.name)}/bridge").apply {
                mkdirs()
            }
            val stdoutLog = File(workDir, "bridge.stdout.log").apply { writeText("") }
            val stderrLog = File(workDir, "bridge.stderr.log").apply { writeText("") }
            val controlName = "mfca_vpn_${UUID.randomUUID().toString().replace("-", "")}"
            val controlServer = LocalServerSocket(controlName)
            LogManager.logInfo(
                "VPN",
                "Starting VPN bridge for ${artifacts.candidate.name}: binary=${bridgeBinary.absolutePath}, workDir=${workDir.absolutePath}, socks=127.0.0.1:${artifacts.localProxyPort}",
            )

            val command = buildList {
                    add(bridgeBinary.absolutePath)
                    add("--control-socket")
                    add(controlName)
                    add("--socks")
                    add("127.0.0.1:${artifacts.localProxyPort}")
                    add("--gateway")
                    add(MfcaVpnService.TUN_GATEWAY_CIDR)
                    add("--portal")
                    add(MfcaVpnService.TUN_PORTAL)
                    if (artifacts.dnsHijack) {
                        add("--dns")
                        add("127.0.0.1:${MfcaVpnService.MIHOMO_DNS_PORT}")
                    }
                    if (!artifacts.udpRelay) {
                        add("--udp-relay=false")
                    }
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

            val controlSocket = controlServer.accept()
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

            RunningProcess(
                candidateName = artifacts.candidate.name,
                process = process,
                controlServer = controlServer,
                controlSocket = controlSocket,
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
                    name = "vpn-bridge-watch-${sanitize(artifacts.candidate.name)}"
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
}
