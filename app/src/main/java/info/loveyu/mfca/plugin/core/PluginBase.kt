package info.loveyu.mfca.plugin.core

import info.loveyu.mfca.plugin.PluginCore

abstract class PluginBase(
    override val name: String,
) : PluginCore {

    @Volatile protected var loaded = false

    override fun load(soPath: String) {
        System.load(soPath)
        loaded = true
    }

    override fun isLoaded(): Boolean = loaded

    override fun version(): String? = null

    override fun start(args: List<String>, logFile: String?): Int = 0

    override fun stop() {}

    override fun isRunning(): Boolean = loaded

    abstract fun process(inputJson: String): String?

    abstract fun getCapabilities(): Int

    companion object {
        const val CAP_FRONT = 1 shl 0
        const val CAP_REAR = 1 shl 1
    }
}
