package info.loveyu.mfca.output.plugin

import info.loveyu.mfca.plugin.core.PluginBase

class OutputSlot7 : PluginBase("output_plugin_slot_7") {
    override fun process(inputJson: String): String? = nativeProcess(inputJson)
    override fun getCapabilities(): Int = nativeGetCapabilities()
    private external fun nativeProcess(inputJson: String): String?
    private external fun nativeGetCapabilities(): Int
}
