package info.loveyu.mfca.config

data class AppStatusConfig(
    val configUrl: String = "",
    val isRunning: Boolean = false,
    val isReceivingEnabled: Boolean = true,
    val isForwardingEnabled: Boolean = true,
    val isWakeLockEnabled: Boolean = false,
    val autoStart: Boolean = false,
    val appAutoStartOnBoot: Boolean = false
)
