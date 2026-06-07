package info.loveyu.mfca.plugin

/**
 * Plugin core for udp2raw.
 *
 * Loads [libudp2raw_plugin.so] from [soPath] via [System.load] and exposes
 * the JNI API declared in [android/jni_bridge.cpp].  The native library runs
 * the libev event loop on a dedicated pthread — no external process is spawned.
 *
 * Lifecycle:
 *   1. [load]       – System.load(soPath); validates the library is accessible.
 *   2. [start]      – launches the native worker thread.
 *   3. [stop]       – sends an ev_async signal; the event loop exits cleanly.
 *   4. [isRunning]  – polls native state.
 *
 * Only one concurrent instance is supported (global C state in the .so).
 */
class Udp2RawPluginCore : PluginCore {
    override val name: String = "udp2raw"

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

    // ── JNI declarations ─────────────────────────────────────────────────────
    // Resolved from libudp2raw_plugin.so after System.load().

    private external fun nativeGetVersion(): String

    private external fun nativeStart(args: Array<String>, logFile: String?): Int

    private external fun nativeStop()

    private external fun nativeIsRunning(): Boolean
}
