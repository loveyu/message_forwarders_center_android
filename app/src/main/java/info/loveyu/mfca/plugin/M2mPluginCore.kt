package info.loveyu.mfca.plugin

/**
 * Plugin core for m2m.
 *
 * Loads libm2m_plugin.so from the app private plugin directory and calls the JNI entry points
 * exported by the c-shared m2m build. No executable process is spawned.
 */
class M2mPluginCore : PluginCore {
    override val name: String = "m2m"

    @Volatile private var loaded = false

    override fun load(soPath: String) {
        System.load(soPath)
        loaded = true
    }

    override fun isLoaded(): Boolean = loaded

    override fun version(): String? = if (loaded) runCatching { nativeGetVersion() }.getOrNull() else null

    override fun start(args: List<String>, logFile: String?): Int {
        check(loaded) { "Plugin not loaded; call load() first" }
        return nativeStart(args.toTypedArray(), logFile)
    }

    override fun stop() {
        if (loaded) nativeStop()
    }

    override fun isRunning(): Boolean = loaded && nativeIsRunning()

    /**
     * Enable or disable socket protection via VpnService.protect().
     * Must be called before [start] when running inside a VPN context.
     * When enabled, the native side calls [notifyMarkSocket] on every outbound socket.
     */
    fun setSocketProtector(enabled: Boolean) {
        if (loaded) {
            nativeSetSocketProtector(enabled)
        }
    }

    private external fun nativeGetVersion(): String
    private external fun nativeStart(args: Array<String>, logFile: String?): Int
    private external fun nativeStop()
    private external fun nativeIsRunning(): Boolean
    private external fun nativeSetSocketProtector(enabled: Boolean)

    /**
     * Callback invoked from native code on an arbitrary goroutine thread
     * to protect a socket file descriptor via VpnService.protect().
     */
    companion object {
        @Volatile
        var socketProtector: SocketProtector? = null

        @JvmStatic
        fun notifyMarkSocket(fd: Int) {
            socketProtector?.protect(fd)
        }
    }

    /**
     * Interface for protecting socket file descriptors.
     * Typically backed by [android.net.VpnService.protect].
     */
    fun interface SocketProtector {
        fun protect(fd: Int): Boolean
    }
}
