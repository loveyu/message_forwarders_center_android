package info.loveyu.mfca.pipeline

import info.loveyu.mfca.config.AppConfig
import info.loveyu.mfca.config.RuleConfig
import info.loveyu.mfca.input.InputMessage
import info.loveyu.mfca.output.OutputManager
import info.loveyu.mfca.queue.QueueItem
import info.loveyu.mfca.util.LogLevel
import info.loveyu.mfca.util.LogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 规则引擎
 * 使用预编译表达式和 Worker 线程执行模型
 */
class RuleEngine(
    private val config: AppConfig,
    private val onForwarded: (() -> Unit)? = null
) {
    private val rules = mutableMapOf<String, RuleConfig>()
    private val inputRulesMap = ConcurrentHashMap<String, MutableList<RuleConfig>>()

    // 表达式引擎
    private val expressionEngine = ExpressionEngine()

    // Worker 线程池
    private val workerPool = Executors.newFixedThreadPool(4).asCoroutineDispatcher()
    private val scope = CoroutineScope(workerPool + SupervisorJob())

    init {
        config.rules.forEach { rule ->
            rules[rule.name] = rule
            val fromValues = if (rule.froms.isNotEmpty()) rule.froms else listOf(rule.from)
            fromValues.forEach { from ->
                inputRulesMap.getOrPut(from) { mutableListOf() }.add(rule)
            }
        }

        // 预编译所有规则中的表达式
        precompileExpressions()
    }

    /**
     * 预编译所有表达式
     */
    private fun precompileExpressions() {
        val expressions = mutableListOf<String>()

        config.rules.forEach { rule ->
            rule.pipeline.forEach { step ->
                step.transform?.extract?.let { expressions.add(it) }
                step.transform?.filter?.let { expressions.add(it) }
            }
        }

        expressionEngine.precompileExpressions(expressions)
        LogManager.log("RULE", "Precompiled ${expressions.size} expressions")
    }

    /**
     * 处理输入消息
     */
    fun process(inputMessage: InputMessage) {
        LogManager.log(LogLevel.DEBUG, "RULE", "NATIVE RuleEngine.process: source=${inputMessage.source}")
        LogManager.log("TRACE:RULE", "RuleEngine.process called: source=${inputMessage.source}, data=${String(inputMessage.data).take(50)}")
        val matchingRules = inputRulesMap[inputMessage.source]
        if (matchingRules == null) {
            LogManager.log("TRACE:RULE", "No rules found for source=${inputMessage.source}, inputRulesMap keys=${inputRulesMap.keys}")
            return
        }
        LogManager.log("TRACE:RULE", "Found ${matchingRules.size} matching rules for ${inputMessage.source}")

        matchingRules.forEach { rule ->
            scope.launch {
                try {
                    processRule(rule, inputMessage)
                } catch (e: Exception) {
                    LogManager.log("RULE", "Error processing rule ${rule.name}: ${e.message}")
                    handleError(rule, inputMessage, e)
                }
            }
        }
    }

    /**
     * 同步处理（用于测试）
     */
    fun processSync(inputMessage: InputMessage) {
        val matchingRules = inputRulesMap[inputMessage.source] ?: return

        matchingRules.forEach { rule ->
            try {
                processRuleSync(rule, inputMessage)
            } catch (e: Exception) {
                LogManager.log("RULE", "Error processing rule ${rule.name}: ${e.message}")
                handleError(rule, inputMessage, e)
            }
        }
    }

    private suspend fun processRule(rule: RuleConfig, inputMessage: InputMessage) {
        LogManager.log("RULE", "Processing rule: ${rule.name}")

        var currentData = inputMessage.data

        for (step in rule.pipeline) {
            val transform = step.transform
            var skipStep = false

            // Apply detect first if specified
            if (transform?.detect != null) {
                if (!detectMedia(currentData, transform.detect)) {
                    LogManager.log("RULE", "Detect failed for ${transform.detect}")
                    skipStep = true
                }
            }

            // Apply transform if present
            if (!skipStep && transform != null) {
                val transformed = applyTransform(transform, currentData, inputMessage)
                if (transformed == null) {
                    LogManager.log("RULE", "Transform returned null, skipping")
                    skipStep = true
                } else {
                    currentData = transformed
                }
            }

            // Apply filter if present
            if (!skipStep && transform?.filter != null) {
                val passed = withContext(Dispatchers.Default) {
                    evaluateFilter(transform.filter!!, currentData, inputMessage.headers)
                }
                if (!passed) {
                    LogManager.log("RULE", "Filter rejected message")
                    skipStep = true
                }
            }

            if (skipStep) {
                continue
            }

            // Send to outputs (async)
            step.to.forEach { outputName ->
                val output = OutputManager.getOutput(outputName)
                if (output != null) {
                    val item = QueueItem(
                        data = currentData,
                        metadata = mapOf("rule" to rule.name)
                    )
                    output.send(item) { success ->
                        LogManager.log("RULE", "Output $outputName: ${if (success) "OK" else "FAILED"}")
                        if (success) onForwarded?.invoke()
                    }
                } else {
                    LogManager.log("RULE", "Output not found: $outputName")
                }
            }
        }
    }

    private fun processRuleSync(rule: RuleConfig, inputMessage: InputMessage) {
        LogManager.log("RULE", "Processing rule: ${rule.name}")

        var currentData = inputMessage.data

        for (step in rule.pipeline) {
            val transform = step.transform
            var skipStep = false

            // Apply detect first if specified
            if (transform?.detect != null) {
                if (!detectMedia(currentData, transform.detect)) {
                    LogManager.log("RULE", "Detect failed for ${transform.detect}")
                    skipStep = true
                }
            }

            // Apply transform if present
            if (!skipStep && transform != null) {
                val transformed = applyTransform(transform, currentData, inputMessage)
                if (transformed == null) {
                    LogManager.log("RULE", "Transform returned null, skipping")
                    skipStep = true
                } else {
                    currentData = transformed
                }
            }

            // Apply filter if present
            if (!skipStep && transform?.filter != null) {
                if (!evaluateFilter(transform.filter!!, currentData, inputMessage.headers)) {
                    LogManager.log("RULE", "Filter rejected message")
                    skipStep = true
                }
            }

            if (skipStep) {
                continue
            }

            // Send to outputs
            step.to.forEach { outputName ->
                val output = OutputManager.getOutput(outputName)
                if (output != null) {
                    val item = QueueItem(
                        data = currentData,
                        metadata = mapOf("rule" to rule.name)
                    )
                    output.send(item) { success ->
                        LogManager.log("RULE", "Output $outputName: ${if (success) "OK" else "FAILED"}")
                        if (success) onForwarded?.invoke()
                    }
                } else {
                    LogManager.log("RULE", "Output not found: $outputName")
                }
            }
        }
    }

    private fun applyTransform(
        transform: info.loveyu.mfca.config.TransformConfig,
        data: ByteArray,
        inputMessage: InputMessage
    ): ByteArray? {
        var currentData = data

        val json = try {
            JSONObject(String(data))
        } catch (e: Exception) {
            if (transform.extract != null) return null
            null
        }

        // Extract using GJSON path or function expression
        if (json != null && transform.extract != null) {
            val extracted = expressionEngine.evaluateExtractExpression(json, transform.extract, inputMessage.headers)
            if (extracted != null) {
                currentData = extracted
            } else {
                return null
            }
        }

        // Apply format template
        transform.format?.let { template ->
            return applyFormat(template, currentData, inputMessage.headers)
        }

        return currentData
    }

    /**
     * 格式化模板: 通过表达式引擎解析 {expression} 占位符
     * 支持 {data}, {headers}, {$headers.X}, {path.to.key}, {func(arg)} 等
     */
    private fun applyFormat(template: String, data: ByteArray, headers: Map<String, String>): ByteArray {
        return expressionEngine.evaluateFormatTemplate(template, data, headers)
    }

    private fun evaluateFilter(filter: String, data: ByteArray, headers: Map<String, String>?): Boolean {
        return expressionEngine.executeFilter(filter, data, headers)
    }

    private fun detectMedia(data: ByteArray, type: String): Boolean {
        return when (type.lowercase()) {
            "image" -> isImage(data)
            "json" -> isJson(data)
            "text" -> isText(data)
            else -> true
        }
    }

    private fun isImage(data: ByteArray): Boolean {
        if (data.size < 4) return false

        // PNG signature
        if (data[0] == 0x89.toByte() && data[1] == 0x50.toByte() &&
            data[2] == 0x4E.toByte() && data[3] == 0x47.toByte()) {
            return true
        }

        // JPEG signature
        if (data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte()) {
            return true
        }

        // GIF signature
        if (data[0] == 0x47.toByte() && data[1] == 0x49.toByte() &&
            data[2] == 0x46.toByte()) {
            return true
        }

        // BMP signature
        if (data[0] == 0x42.toByte() && data[1] == 0x4D.toByte()) {
            return true
        }

        // WebP signature
        if (data.size >= 12 &&
            data[0] == 0x52.toByte() && data[1] == 0x49.toByte() &&
            data[2] == 0x46.toByte() && data[3] == 0x46.toByte() &&
            data[8] == 0x57.toByte() && data[9] == 0x45.toByte() &&
            data[10] == 0x42.toByte() && data[11] == 0x50.toByte()) {
            return true
        }

        return false
    }

    private fun isJson(data: ByteArray): Boolean {
        try {
            val str = String(data.take(100).toByteArray()).trim()
            return str.startsWith("{") || str.startsWith("[")
        } catch (e: Exception) {
            return false
        }
    }

    private fun isText(data: ByteArray): Boolean {
        if (data.isEmpty()) return true
        // Check if data is mostly printable ASCII
        var printable = 0
        for (b in data.take(100)) {
            val byte = b.toInt() and 0xFF
            if (byte in 32..126 || byte in 9..10 || byte == 13) {
                printable++
            }
        }
        return printable > (data.size.coerceAtMost(100)) * 0.85
    }

    private fun handleError(rule: RuleConfig, inputMessage: InputMessage, error: Exception) {
        rule.onError?.forEach { step ->
            step.to.forEach { outputName ->
                val output = OutputManager.getOutput(outputName)
                if (output != null) {
                    val item = QueueItem(
                        data = inputMessage.data,
                        metadata = mapOf(
                            "rule" to rule.name,
                            "error" to (error.message ?: "Unknown error")
                        )
                    )
                    output.send(item, null)
                }
            }
        }
    }

    fun getRulesForInput(inputName: String): List<RuleConfig> {
        return inputRulesMap[inputName] ?: emptyList()
    }

    fun getAllRules(): Map<String, RuleConfig> = rules.toMap()

    /**
     * 获取表达式引擎（用于调试）
     */
    fun getExpressionEngine(): ExpressionEngine = expressionEngine

    /**
     * 关闭引擎
     */
    fun shutdown() {
        scope.cancel()
        workerPool.close()
        expressionEngine.shutdown()
    }
}
