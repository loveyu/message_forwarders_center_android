package info.loveyu.mfca.plugin

/**
 * Plugin core for mihomo.
 *
 * Loads libmihomo_plugin.so from the app private plugin directory and calls the JNI entry points
 * exported by the c-shared mihomo build. No executable process is spawned.
 */
class MihomoPluginCore : PluginCore {
    override val name: String = "mihomo"

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

    private external fun nativeGetVersion(): String

    private external fun nativeStart(args: Array<String>, logFile: String?): Int

    private external fun nativeStop()

    private external fun nativeIsRunning(): Boolean
}
