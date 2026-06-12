package info.loveyu.mfca.config.models

data class OutputsConfig(
    val http: List<HttpOutputConfig> = emptyList(),
    val link: List<LinkOutputConfig> = emptyList(),
    val internal: List<InternalOutputConfig> = emptyList()
)

data class HttpOutputConfig(
    val name: String,
    val url: String,
    val method: String = "POST",
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val timeout: Duration = Duration("5s"),
    val retry: RetryConfig? = null,
    val onFailureQueue: QueueRefConfig? = null,
    val queue: QueueRefConfig? = null,
    val whenCondition: String? = null,
    val deny: String? = null,
    val format: List<OutputFormatStep>? = null
) {
    val effectiveFormatSteps: List<OutputFormatStep>?
        get() {
            val steps = mutableListOf<OutputFormatStep>()
            format?.let { steps += it }
            headers.forEach { (key, value) ->
                steps += OutputFormatStep(target = "\$header.$key", template = value)
            }
            body?.let { steps += OutputFormatStep(target = "\$data", template = it) }
            return steps.takeIf { it.isNotEmpty() }
        }
}

data class RetryConfig(
    val maxAttempts: Int = 1,
    val interval: Duration = Duration("1s")
)

data class QueueRefConfig(
    val name: String,
    val delay: Duration = Duration("0s")
)

data class LinkOutputConfig(
    val name: String,
    val linkIds: List<String>,
    val role: LinkRole,
    val topic: String? = null,
    val qos: Int? = null,
    val retain: Boolean = false,
    val retry: RetryConfig? = null,
    val onFailureQueue: QueueRefConfig? = null,
    val queue: QueueRefConfig? = null,
    val whenCondition: String? = null,
    val deny: String? = null,
    val format: List<OutputFormatStep>? = null
) {
    val linkId: String get() = linkIds.firstOrNull() ?: ""
}

data class InternalOutputConfig(
    val name: String,
    val type: InternalOutputType,
    val basePath: String? = null,
    val fileName: String? = null,
    val options: Map<String, Any>? = null,
    val channel: String? = null,
    val queue: QueueRefConfig? = null,
    val whenCondition: String? = null,
    val deny: String? = null,
    val format: List<OutputFormatStep>? = null
)

enum class InternalOutputType {
    clipboard, file, broadcast, notify, clipboardHistory
}

data class NotifyOptions(
    var title: String? = null,
    var message: String? = null,
    var icon: String? = null,
    var fixedIcon: String? = null,
    var popup: Boolean? = null,
    var persistent: Boolean? = null,
    var tag: String? = null,
    var group: String? = null,
    var id: String? = null
)

data class OutputFormatStep(
    val target: String,
    val template: String,
    val raw: Any? = null
)

data class RuleConfig(
    val name: String,
    val froms: List<String>,
    val pipeline: List<PipelineStep> = emptyList(),
    val onError: List<PipelineStep>? = null,
    val whenCondition: String? = null,
    val deny: String? = null
) {
    val from: String get() = froms.firstOrNull() ?: ""
}

data class PipelineStep(
    val transform: TransformConfig? = null,
    val to: List<String> = emptyList()
)

data class TransformConfig(
    val decode: String? = null,
    val extract: String? = null,
    val filter: String? = null,
    val detect: String? = null,
    val format: String? = null,
    val formatSteps: List<OutputFormatStep>? = null,
    val enrich: String? = null,
    val call: List<Map<String, String>>? = null,
    val breakOnReject: Boolean = false
)

data class CallConfig(
    val name: String,
    val type: CallType = CallType.http,
    val url: String = "",
    val method: String = "POST",
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val response: String? = null,
    val timeout: Duration = Duration("15s"),
    val retry: RetryConfig? = null
)

enum class CallType {
    http
}
