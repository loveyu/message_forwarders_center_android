package info.loveyu.mfca.plugin

/**
 * Abstract interface for a native plugin loaded dynamically from internal storage.
 *
 * The concrete implementation (e.g. [Udp2RawPluginCore]) loads the .so via
 * [System.load] and exposes a start/stop lifecycle.
 */
interface PluginCore {
    /** Human-readable name of this plugin. */
    val name: String

    /** Load the native .so from [soPath]. Must be called before any other method. */
    fun load(soPath: String)

    /** @return true if the plugin .so has been successfully loaded. */
    fun isLoaded(): Boolean

    /** @return version string reported by the native library, or null if not loaded. */
    fun version(): String?

    /**
     * Start the plugin with the given arguments.
     * Internally creates a native thread (the "process") and runs the plugin logic.
     *
     * @param args  Arguments passed to the plugin (same semantics as command-line argv[1..]).
     * @param logFile  Optional path to a log file the native code may write to.
     * @return 0 on success, negative on error.
     */
    fun start(args: List<String>, logFile: String? = null): Int

    /** Request the plugin to stop. Non-blocking; uses thread-safe signalling. */
    fun stop()

    /** @return true if the plugin is currently running. */
    fun isRunning(): Boolean
}
