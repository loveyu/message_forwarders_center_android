package info.loveyu.mfca.config

data class AppStatusConfig(
    val version: String = "1.0",
    val configUrl: String = "",
    val isRunning: Boolean = false,
    val isReceivingEnabled: Boolean = true,
    val isForwardingEnabled: Boolean = true,
    val autoStart: Boolean = false,
    val appAutoStartOnBoot: Boolean = false
)
