package info.loveyu.mfca.input

import android.content.Context
import info.loveyu.mfca.config.Udp2RawInputConfig
import info.loveyu.mfca.plugin.PluginCore
import info.loveyu.mfca.plugin.PluginManager
import info.loveyu.mfca.plugin.Udp2RawPluginCore
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

    @Volatile private var running = false
    @Volatile private var stopping = false
    @Volatile private var fatalError: String? = null
    @Volatile private var lastError: String? = null
    @Volatile private var resolvedRemote: String? = null
    private val lock = Any()

    private val plugin: PluginCore = Udp2RawPluginCore()

    override fun start() {
        synchronized(lock) {
            if (running) return

            val effectiveArgs = resolveEffectiveArgs()
            if (effectiveArgs.isEmpty()) {
                fatalError = "udp2raw: no args and no valid dsn configured"
                throw IllegalArgumentException(fatalError)
            }

            val resolvedArgs = resolveDomainsInArgs(effectiveArgs)

            // Ensure plugin .so is installed
            val soFile = try {
                ensurePlugin()
            } catch (e: Exception) {
                fatalError = "udp2raw plugin not available: ${e.message}"
                throw IllegalStateException(fatalError, e)
            }

            // Load the .so (idempotent — System.load tracks already-loaded libs)
            if (!plugin.isLoaded()) {
                try {
                    plugin.load(soFile.absolutePath)
                } catch (e: Exception) {
                    fatalError = "udp2raw plugin load failed: ${e.message}"
                    throw IllegalStateException(fatalError, e)
                }
            }

            val workDir = File(context.filesDir, "udp2raw/runtime/${sanitize(config.name)}").apply { mkdirs() }
            val logFile = File(workDir, "udp2raw.log").apply { if (!exists()) createNewFile() }

            registry[config.name] = logFile

            LogManager.logInfo("UDP2RAW", "Starting udp2raw '${config.name}': ${resolvedArgs.joinToString(" ")}")

            val ret = plugin.start(resolvedArgs, logFile.absolutePath)
            if (ret != 0) {
                lastError = "udp2raw plugin start failed (code $ret)"
                throw IllegalStateException(lastError)
            }

            // Give the event loop a moment to initialise; if it exits immediately it's a fatal arg error
            Thread.sleep(1200)
            if (!plugin.isRunning()) {
                val tail = logFile.readTailOrEmpty()
                lastError = "udp2raw exited immediately: $tail"
                throw IllegalStateException(lastError)
            }

            running = true
            stopping = false
            lastError = null

            // Watch for unexpected exit
            Thread {
                while (plugin.isRunning()) Thread.sleep(500)
                synchronized(lock) {
                    if (!stopping) {
                        val tail = logFile.readTailOrEmpty()
                        lastError = "udp2raw exited unexpectedly: $tail"
                        LogManager.logError("UDP2RAW", "udp2raw '${config.name}' exited unexpectedly: $lastError")
                    } else {
                        LogManager.logDebug("UDP2RAW", "udp2raw '${config.name}' stopped")
                    }
                    running = false
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
            running = false
            plugin.stop()
            // Give it up to 1.5 s to stop gracefully
            val deadline = System.currentTimeMillis() + 1500
            while (plugin.isRunning() && System.currentTimeMillis() < deadline) {
                Thread.sleep(100)
            }
            stopping = false
        }
    }

    override fun isRunning(): Boolean = running && plugin.isRunning()

    override fun setOnMessageListener(listener: (InputMessage) -> Unit) = Unit

    override fun getError(): String? = fatalError ?: lastError

    override fun hasFatalError(): Boolean = fatalError != null

    fun getResolvedRemote(): String? = resolvedRemote

    /** Ensure the plugin .so exists in internal storage and return its path. */
    private fun ensurePlugin(): File {
        if (PluginManager.isInstalled(context, "udp2raw")) {
            return PluginManager.getInstalledPath(context, "udp2raw")
        }
        // Not installed — check if config provides a download URL
        val url = config.pluginUrl
        if (!url.isNullOrBlank()) {
            return PluginManager.installFromUrl(context, "udp2raw", url)
        }
        throw IllegalStateException(
            "libudp2raw_plugin.so is not installed. " +
                "Install it via PluginManager or set pluginUrl in the udp2raw input config."
        )
    }

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
     * Format:
     *   udp2raw://[key@]remoteHost:remotePort?listen=localHost:localPort[&rawMode=faketcp][&role=client|server]
     *
     * Parameters:
     *   role     = client|server  (program role; default: client)
     *   rawMode  = faketcp|udp|icmp  (raw packet mode; default: faketcp)
     *
     * Backward compatibility:
     *   - Old `mode=faketcp|udp|icmp` is still accepted (superseded by `rawMode`).
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

            val rawModeValues = setOf("faketcp", "udp", "icmp")
            val modeParam = queryParams["mode"]
            val rawMode: String =
                when {
                    // Backward compat: old mode=faketcp|udp|icmp
                    modeParam != null && modeParam in rawModeValues -> modeParam
                    else -> queryParams["rawMode"] ?: "faketcp"
                }
            val programRole: String = queryParams["role"] ?: "client"

            buildList {
                add(if (programRole == "server") "-s" else "-c")
                add("-l$listen")
                add("-r$remoteHost:$remotePort")
                add("--raw-mode")
                add(rawMode)
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

    private fun File.readTailOrEmpty(maxChars: Int = 1200): String {
        if (!exists()) return ""
        val content = runCatching { readText() }.getOrDefault("")
        return if (content.length <= maxChars) content else content.takeLast(maxChars)
    }

    private fun sanitize(value: String): String = value.replace(Regex("[^a-zA-Z0-9._-]"), "_")

    companion object {
        /** Maps instance name → log file for the log viewer. */
        private val registry = ConcurrentHashMap<String, File>()

        fun getLogFile(name: String): File? = registry[name]
    }
}
