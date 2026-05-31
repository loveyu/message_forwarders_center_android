package info.loveyu.mfca.input

import android.content.Context
import android.system.Os
import info.loveyu.mfca.config.Udp2RawInputConfig
import info.loveyu.mfca.util.LogManager
import java.io.File
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class Udp2RawInput(
    private val context: Context,
    private val config: Udp2RawInputConfig,
) : InputSource {
    override val inputName: String = config.name
    override val inputType: InputType = InputType.udp2raw

    @Volatile private var process: Process? = null
    @Volatile private var running = false
    @Volatile private var stopping = false
    @Volatile private var fatalError: String? = null
    @Volatile private var lastError: String? = null
    @Volatile private var resolvedRemote: String? = null
    private val lock = Any()

    override fun start() {
        synchronized(lock) {
            if (running) return

            val effectiveArgs = resolveEffectiveArgs()
            if (effectiveArgs.isEmpty()) {
                fatalError = "udp2raw: no args and no valid dsn configured"
                throw IllegalArgumentException(fatalError)
            }

            val resolvedArgs = resolveDomainsInArgs(effectiveArgs)

            val executable = try {
                ensureExecutableBinary()
            } catch (e: Exception) {
                fatalError = "prepare udp2raw binary failed: ${e.message}"
                throw IllegalStateException(fatalError, e)
            }

            val workDir = File(context.filesDir, "udp2raw/runtime/${sanitize(config.name)}").apply { mkdirs() }
            val stdoutLog = File(workDir, "udp2raw.stdout.log").apply { writeText("") }
            val stderrLog = File(workDir, "udp2raw.stderr.log").apply { writeText("") }
            val command = listOf(executable.absolutePath) + resolvedArgs

            registry[config.name] = Pair(stdoutLog, stderrLog)

            LogManager.logInfo("UDP2RAW", "Starting udp2raw '${config.name}': ${command.joinToString(" ")}")
            val startedProcess =
                ProcessBuilder(command)
                    .directory(workDir)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(stdoutLog))
                    .redirectError(ProcessBuilder.Redirect.appendTo(stderrLog))
                    .apply {
                        environment()["HOME"] = workDir.absolutePath
                    }.start()

            if (startedProcess.waitFor(1200, TimeUnit.MILLISECONDS)) {
                val exitCode = startedProcess.exitValue()
                val tail = readFailureOutput(stdoutLog, stderrLog)
                lastError = "udp2raw exited immediately ($exitCode): $tail"
                throw IllegalStateException(lastError)
            }

            process = startedProcess
            running = true
            stopping = false
            lastError = null

            Thread {
                val exitCode = startedProcess.waitFor()
                synchronized(lock) {
                    if (process != startedProcess) {
                        return@Thread
                    }
                    process = null
                    running = false
                    if (!stopping) {
                        val tail = readFailureOutput(stdoutLog, stderrLog)
                        lastError = "udp2raw exited unexpectedly ($exitCode): $tail"
                        LogManager.logError("UDP2RAW", "udp2raw '${config.name}' exited unexpectedly: $lastError")
                    } else {
                        LogManager.logDebug("UDP2RAW", "udp2raw '${config.name}' stopped")
                    }
                    stopping = false
                }
            }.apply {
                isDaemon = true
                name = "udp2raw-watch-${sanitize(config.name)}"
                start()
            }
        }
    }

    override fun stop() {
        synchronized(lock) {
            stopping = true
            val currentProcess = process
            running = false
            process = null
            if (currentProcess == null) {
                stopping = false
                return
            }
            currentProcess.destroy()
            if (!currentProcess.waitFor(1500, TimeUnit.MILLISECONDS)) {
                currentProcess.destroyForcibly()
                currentProcess.waitFor(1500, TimeUnit.MILLISECONDS)
            }
        }
    }

    override fun isRunning(): Boolean = running && (process?.isAlive == true)

    override fun setOnMessageListener(listener: (InputMessage) -> Unit) = Unit

    override fun getError(): String? = fatalError ?: lastError

    override fun hasFatalError(): Boolean = fatalError != null

    fun getResolvedRemote(): String? = resolvedRemote

    /**
     * Returns effective args: explicit args take precedence over DSN.
     */
    private fun resolveEffectiveArgs(): List<String> {
        if (config.args.isNotEmpty()) return config.args
        val dsn = config.dsn ?: return emptyList()
        return parseDsnToArgs(dsn)
    }

    /**
     * Parses a udp2raw DSN into command-line args.
     *
     * Format: udp2raw://[key@]remoteHost:remotePort?listen=localHost:localPort[&mode=faketcp][&role=client|server]
     */
    private fun parseDsnToArgs(dsn: String): List<String> {
        return try {
            val uri = URI(dsn)
            require(uri.scheme == "udp2raw") { "DSN scheme must be 'udp2raw', got '${uri.scheme}'" }

            val key = uri.rawUserInfo
            val remoteHost = uri.host ?: error("missing remote host in DSN")
            val remotePort = if (uri.port > 0) uri.port else error("missing remote port in DSN")

            val queryParams = parseQueryParams(uri.rawQuery)
            val listen = queryParams["listen"] ?: error("missing 'listen' query param in DSN")
            val mode = queryParams["mode"] ?: "faketcp"
            val role = queryParams["role"] ?: "client"

            buildList {
                add(if (role == "server") "-s" else "-c")
                add("-l$listen")
                add("-r$remoteHost:$remotePort")
                add("--raw-mode")
                add(mode)
                if (!key.isNullOrBlank()) {
                    add("-k")
                    add(key)
                }
            }
        } catch (e: Exception) {
            fatalError = "udp2raw DSN parse failed: ${e.message}"
            emptyList()
        }
    }

    /**
     * Scans args for -r host:port entries and resolves any domain names to IPs.
     * Also resolves domains embedded directly like -r<host>:<port>.
     */
    private fun resolveDomainsInArgs(args: List<String>): List<String> {
        val result = args.toMutableList()
        var i = 0
        while (i < result.size) {
            val arg = result[i]
            val hostPort = when {
                arg == "-r" && i + 1 < result.size -> {
                    val next = result[i + 1]
                    Pair(i + 1, next)
                }
                arg.startsWith("-r") && arg.length > 2 -> Pair(i, arg.substring(2))
                else -> null
            }
            if (hostPort != null) {
                val (idx, value) = hostPort
                val resolved = resolveHostPort(value)
                if (resolved != value) {
                    result[idx] = if (arg == "-r") resolved else "-r$resolved"
                    LogManager.logInfo("UDP2RAW", "'${config.name}' resolved remote $value -> $resolved")
                }
                resolvedRemote = resolved
            }
            i++
        }
        return result
    }

    /**
     * Given "host:port", resolves the host if it's a domain name.
     * Returns the original string on failure.
     */
    private fun resolveHostPort(hostPort: String): String {
        val lastColon = hostPort.lastIndexOf(':')
        if (lastColon < 0) return hostPort
        val host = hostPort.substring(0, lastColon)
        val port = hostPort.substring(lastColon + 1)
        if (host.isEmpty() || isIpAddress(host)) return hostPort
        return try {
            val ip = InetAddress.getByName(host).hostAddress ?: return hostPort
            "$ip:$port"
        } catch (_: Exception) {
            LogManager.logWarn("UDP2RAW", "'${config.name}' DNS lookup failed for $host, using original")
            hostPort
        }
    }

    private fun isIpAddress(host: String): Boolean {
        // IPv4: all numeric with dots; IPv6: contains colons or is bracketed
        if (host.startsWith('[') && host.endsWith(']')) return true
        if (host.contains(':')) return true
        return host.split('.').all { part -> part.all { it.isDigit() } }
    }

    private fun parseQueryParams(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        return rawQuery.split('&').mapNotNull { kv ->
            val eq = kv.indexOf('=')
            if (eq < 0) null else kv.substring(0, eq) to kv.substring(eq + 1)
        }.toMap()
    }

    private fun ensureExecutableBinary(): File {
        val lib = File(context.applicationInfo.nativeLibraryDir, "libudp2raw.so")
        require(lib.exists()) { "built-in udp2raw library not found: ${lib.absolutePath}" }
        val runtimeDir = File(context.filesDir, "udp2raw/core").apply { mkdirs() }
        val executable = File(runtimeDir, "udp2raw")
        executable.delete()
        Os.symlink(lib.absolutePath, executable.absolutePath)
        return executable
    }

    private fun readFailureOutput(stdoutLog: File, stderrLog: File): String {
        val merged =
            buildString {
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

    companion object {
        /** Maps instance name to (stdout, stderr) log files for the log viewer. */
        private val registry = ConcurrentHashMap<String, Pair<File, File>>()

        fun getLogFiles(name: String): Pair<File, File>? = registry[name]
    }
}
