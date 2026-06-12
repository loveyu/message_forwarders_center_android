package info.loveyu.mfca.output.plugin

import info.loveyu.mfca.plugin.core.PluginBase

class OutputSlot1 : PluginBase("output_plugin_slot_1") {
    override fun process(inputJson: String): String? = nativeProcess(inputJson)
    override fun getCapabilities(): Int = nativeGetCapabilities()
    private external fun nativeProcess(inputJson: String): String?
    private external fun nativeGetCapabilities(): Int
}
