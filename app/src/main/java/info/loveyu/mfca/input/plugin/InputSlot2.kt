package info.loveyu.mfca.input.plugin

import info.loveyu.mfca.plugin.core.PluginBase

class InputSlot2 : PluginBase("input_plugin_slot_2") {
    override fun process(inputJson: String): String? = nativeProcess(inputJson)
    override fun getCapabilities(): Int = nativeGetCapabilities()
    private external fun nativeProcess(inputJson: String): String?
    private external fun nativeGetCapabilities(): Int
}
