package info.loveyu.mfca.config.models

import java.util.concurrent.TimeUnit

data class QuickSettingsConfig(
    val inputMethodSwitcher: Boolean = true
)

data class AppConfig(
    val version: String = "",
    val plugin: PluginConfig = PluginConfig(),
    val scheduler: SchedulerConfig = SchedulerConfig(),
    val geo: GeoConfig = GeoConfig(),
    val links: List<LinkConfig> = emptyList(),
    val inputs: InputsConfig = InputsConfig(),
    val queues: QueuesConfig = QueuesConfig(),
    val outputs: OutputsConfig = OutputsConfig(),
    val calls: List<CallConfig> = emptyList(),
    val rules: List<RuleConfig> = emptyList(),
    val deadLetter: DeadLetterConfig = DeadLetterConfig(),
    val quickSettings: QuickSettingsConfig = QuickSettingsConfig()
)

data class PluginConfig(
    val udp2rawCore: String = "",
    val m2mCore: String = "",
    val downloadProxy: String = "",
)

data class GeoConfig(
    val geoip: String = "",
    val geosite: String = "",
    val country: String = "",
    val asn: String = "",
)

data class SchedulerConfig(
    val tickInterval: Duration = Duration("40s"),
    val chargingTickInterval: Duration? = null,
    val wakeLockTimeout: Duration = Duration("1h"),
    val wifiLockTimeout: Duration = Duration("1h")
) {
    val effectiveTickInterval: Duration
        get() = if (tickInterval.millis >= 20_000) tickInterval else Duration("20s")

    val effectiveChargingTickInterval: Duration
        get() {
            val interval = chargingTickInterval ?: tickInterval
            return if (interval.millis >= 20_000) interval else Duration("20s")
        }
}

data class DeadLetterConfig(
    val enabled: Boolean = false,
    val maxRetry: Int = 10,
    val pipeline: List<PipelineStep> = emptyList()
)

data class Duration(
    val value: String
) {
    val millis: Long
        get() = parseDuration(value)

    val timeUnit: TimeUnit
        get() = when {
            value.endsWith("ms") -> TimeUnit.MILLISECONDS
            value.endsWith("s") -> TimeUnit.SECONDS
            value.endsWith("m") -> TimeUnit.MINUTES
            value.endsWith("h") -> TimeUnit.HOURS
            value.endsWith("d") -> TimeUnit.DAYS
            else -> TimeUnit.SECONDS
        }

    private fun parseDuration(d: String): Long {
        return try {
            val isMillis = d.endsWith("ms")
            val numStr = if (isMillis) d.dropLast(2) else d.dropLast(1)
            val num = numStr.toDoubleOrNull() ?: 0.0
            val unit = if (isMillis) "ms" else d.takeLast(1)
            when (unit) {
                "ms" -> num.toLong()
                "s" -> (num * 1_000).toLong()
                "m" -> (num * 60_000).toLong()
                "h" -> (num * 3_600_000).toLong()
                "d" -> (num * 86_400_000).toLong()
                else -> (num * 1_000).toLong()
            }
        } catch (e: Exception) {
            5000
        }
    }
}
