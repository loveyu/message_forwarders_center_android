package info.loveyu.mfca.output.plugin

import info.loveyu.mfca.plugin.core.PluginBase

class OutputSlot8 : PluginBase("output_plugin_slot_8") {
    override fun process(inputJson: String): String? = nativeProcess(inputJson)
    override fun getCapabilities(): Int = nativeGetCapabilities()
    private external fun nativeProcess(inputJson: String): String?
    private external fun nativeGetCapabilities(): Int
}
