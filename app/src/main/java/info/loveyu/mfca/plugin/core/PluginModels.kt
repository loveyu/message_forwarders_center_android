package info.loveyu.mfca.plugin.core

data class SlotStats(
    var callCount: Long = 0,
    var passCount: Long = 0,
    var modifyCount: Long = 0,
    var errorCount: Long = 0,
    var totalTimeMs: Long = 0,
)

data class PluginLogEntry(
    val level: String,
    val message: String,
)

enum class PluginAction {
    MODIFY,
    PASS,
}

data class PluginAppliedResult(
    val dataBase64: String? = null,
    val headers: Map<String, String>? = null,
    val metadata: Map<String, String>? = null,
    val statusCode: Int? = null,
    val responseBody: String? = null,
    val responseHeaders: Map<String, String>? = null,
)
