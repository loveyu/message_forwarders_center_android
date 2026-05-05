package info.loveyu.mfca.pipeline

import android.content.Context
import android.provider.Settings
import info.loveyu.mfca.clipboard.ClipboardHistoryDbHelper
import info.loveyu.mfca.config.AppConfig
import info.loveyu.mfca.config.RuleConfig
import info.loveyu.mfca.input.InputMessage
import info.loveyu.mfca.output.Output
import info.loveyu.mfca.output.OutputManager
import info.loveyu.mfca.output.OutputType
import info.loveyu.mfca.queue.QueueItem
import info.loveyu.mfca.service.ForwardService
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
    private val context: Context? = null,
    private val onForwarded: (() -> Unit)? = null
) {
    private val rules = mutableMapOf<String, RuleConfig>()
    private val inputRulesMap = ConcurrentHashMap<String, MutableList<RuleConfig>>()

    // 表达式引擎
    private val expressionEngine = ExpressionEngine()

    // Worker 线程池
    private val workerPool = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(workerPool + SupervisorJob())

    // Enrichers
    private val enrichers = ConcurrentHashMap<String, Enricher>()

    init {
        config.rules.forEach { rule ->
            rules[rule.name] = rule
            val fromValues = if (rule.froms.isNotEmpty()) rule.froms else listOf(rule.from)
            fromValues.forEach { from ->
                inputRulesMap.getOrPut(from) { mutableListOf() }.add(rule)
            }
        }

        // Register built-in enrichers
        val gotifyIconEnricher = GotifyIconEnricher(context)
        enrichers[gotifyIconEnricher.type] = gotifyIconEnricher

        // Register raw data functions that need Android context
        context?.let { ctx ->
            val appCtx = ctx.applicationContext

            // clipboardUpdateBefore 函数：获取距上次更新的秒数
            expressionEngine.clipboardUpdateBeforeFn = { text ->
                ClipboardHistoryDbHelper.getSecondsSinceLastUpdate(appCtx, text)
            }

            // clipboardNew(seconds): 检查当前数据是否为新内容，或距上次更新已超过指定秒数
            // 用于剪贴板去重同步，在写入历史之前调用
            expressionEngine.registerRawDataFunction(
                "clipboardNew",
                ExpressionEngine.RawDataFunction("clipboardNew") { data, args ->
                    val seconds = args.getOrNull(0)
                    val maxAgeMs = when (seconds) {
                        is Long -> seconds * 1000L
                        is Double -> (seconds * 1000).toLong()
                        is String -> seconds.toLongOrNull()?.times(1000L) ?: 10_000L
                        else -> 10_000L
                    }
                    val text = String(data)
                    val sec = expressionEngine.clipboardUpdateBeforeFn?.invoke(text) ?: -1L
                    // sec < 0: content not in history (new), sec*1000 > maxAgeMs: expired
                    sec < 0 || sec * 1000 > maxAgeMs
                }
            )

            // deviceId(): 返回设备 ANDROID_ID（每个应用签名+设备唯一）
            expressionEngine.deviceIdValue =
                Settings.Secure.getString(appCtx.contentResolver, Settings.Secure.ANDROID_ID) ?: ""
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
        LogManager.logDebug("RULE", "Precompiled ${expressions.size} expressions")
    }

    /**
     * 处理输入消息
     */
    fun process(inputMessage: InputMessage) {
        LogManager.logDebug("RULE", "process: source=${inputMessage.source}, dataLen=${inputMessage.data.size}")
        val matchingRules = inputRulesMap[inputMessage.source]
        if (matchingRules == null) {
            LogManager.logDebug("RULE", "No rules for source=${inputMessage.source}")
            return
        }
        LogManager.logDebug("RULE", "Source ${inputMessage.source} matched ${matchingRules.size} rules")

        matchingRules.forEach { rule ->
            scope.launch {
                try {
                    processRule(rule, inputMessage)
                } catch (e: Exception) {
                    LogManager.logError("RULE", "Error processing rule ${rule.name}: ${e.message}")
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
                LogManager.logError("RULE", "Error processing rule ${rule.name}: ${e.message}")
                handleError(rule, inputMessage, e)
            }
        }
    }

    /**
     * 尝试从 ByteArray 解析 JSONObject，失败返回 null
     */
    private fun parseJson(data: ByteArray): JSONObject? {
        return try {
            JSONObject(String(data))
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun processRule(rule: RuleConfig, inputMessage: InputMessage) {
        LogManager.logDebug("RULE", "Processing rule: ${rule.name} (${rule.pipeline.size} steps)")

        var currentData = inputMessage.data
        // 跟踪已解析的 JSONObject，数据不变时复用
        var currentJson: JSONObject? = parseJson(currentData)

        for (step in rule.pipeline) {
            val transform = step.transform
            var skipStep = false

            // Apply decode before detect
            if (transform?.decode != null) {
                val decoded = expressionEngine.evaluateDecodePipeline(transform.decode!!, currentData)
                if (decoded != null) {
                    currentData = decoded
                    currentJson = parseJson(currentData)
                } else {
                    LogManager.logDebug("RULE", "Rule [${rule.name}] decode [${transform.decode}] -> null, SKIPPED")
                    skipStep = true
                }
            }

            // Apply detect first if specified
            if (!skipStep && transform?.detect != null) {
                if (!detectMedia(currentData, transform.detect)) {
                    LogManager.logDebug("RULE", "Rule [${rule.name}] detect [${transform.detect}] -> SKIPPED")
                    skipStep = true
                } else if (LogManager.isDebugEnabled()) {
                    LogManager.logDebug("RULE", "Rule [${rule.name}] detect [${transform.detect}] -> PASS, dataLen=${currentData.size}")
                }
            }

            // Apply enrich if specified (before other transforms)
            if (!skipStep && transform?.enrich != null) {
                try {
                    val enriched = applyEnrich(transform.enrich, currentData, currentJson)
                    if (enriched != null) {
                        currentData = enriched
                        currentJson = parseJson(currentData)
                        if (LogManager.isDebugEnabled()) {
                            LogManager.logDebug("RULE", "Rule [${rule.name}] enrich [${transform.enrich}] -> OK, dataLen=${currentData.size}, preview=${expressionEngine.truncateForLog(String(currentData))}")
                        }
                    }
                } catch (e: Exception) {
                    LogManager.logWarn("RULE", "Rule [${rule.name}] enrich [${transform.enrich}] failed: ${e.message}")
                }
            }

            // Apply filter before extract (先过滤，再提取)
            if (!skipStep && transform?.filter != null) {
                val passed = withContext(Dispatchers.Default) {
                    expressionEngine.executeTwoPhaseFilter(transform.filter!!, currentJson, currentData, inputMessage.headers)
                }
                if (!passed) {
                    LogManager.logDebug("RULE", "Rule [${rule.name}] filter [${transform.filter}] -> REJECTED")
                    skipStep = true
                } else {
                    if (LogManager.isDebugEnabled()) {
                        LogManager.logDebug("RULE", "Rule [${rule.name}] filter [${transform.filter}] -> PASS, json=${expressionEngine.truncateForLog(currentJson?.toString())}, data=${expressionEngine.truncateForLog(String(currentData))}")
                    }
                    if (transform.filter!!.contains("clipboardUpdateBefore")) {
                        ClipboardHistoryDbHelper.updateLastPassedTime(String(currentData))
                    }
                }
            }

            // Apply extract + format if present (过滤通过后再提取)
            if (!skipStep && transform != null) {
                val transformed = applyTransform(transform, currentData, currentJson, inputMessage, rule.name)
                if (transformed == null) {
                    LogManager.logDebug("RULE", "Rule [${rule.name}] extract [${transform.extract}] -> null, SKIPPED")
                    skipStep = true
                } else if (transformed !== currentData) {
                    // 数据实际发生了变化，重新解析 JSON
                    currentData = transformed
                    currentJson = parseJson(currentData)
                    if (LogManager.isDebugEnabled()) {
                        LogManager.logDebug("RULE", "Rule [${rule.name}] transform extract=[${transform.extract}], format=[${transform.format}] -> OK, dataLen=${currentData.size}, preview=${expressionEngine.truncateForLog(String(currentData))}")
                    }
                } else {
                    if (LogManager.isDebugEnabled()) {
                        LogManager.logDebug("RULE", "Rule [${rule.name}] transform extract=[${transform.extract}], format=[${transform.format}] -> unchanged")
                    }
                }
            }

            if (skipStep) {
                if (transform?.breakOnReject == true) {
                    LogManager.logDebug("RULE", "Rule [${rule.name}] breakOnReject: breaking pipeline")
                    break
                }
                continue
            }

            // Send to outputs (async)
            step.to.forEach { outputName ->
                val output = OutputManager.getOutput(outputName)
                if (output != null) {
                    // Skip non-internal outputs when forwarding is disabled
                    if (!ForwardService.isForwardingEnabled && output.type != OutputType.internal) {
                        LogManager.logDebug("RULE", "转发已暂停, 跳过输出: $outputName")
                        return@forEach
                    }
                    if (LogManager.isDebugEnabled()) {
                        LogManager.logDebug("RULE", "Rule [${rule.name}] output -> $outputName: sending, data=${expressionEngine.truncateForLog(String(currentData))}, headers=$inputMessage.headers")
                    }
                    val ruleCtx = buildRuleContext(rule.name, inputMessage)
                    val (outData, outHeaders) =
                        applyOutputFormat(output, currentData, inputMessage.headers, ruleCtx)
                    val item =
                        QueueItem(
                            data = outData,
                            metadata = mapOf("rule" to rule.name),
                            headers = outHeaders
                        )
                    output.send(item) { success ->
                        if (success) {
                            LogManager.logDebug("RULE", "Rule [${rule.name}] -> $outputName: OK")
                            onForwarded?.invoke()
                        } else {
                            LogManager.logWarn("RULE", "Rule [${rule.name}] -> $outputName: FAILED")
                        }
                    }
                } else {
                    LogManager.logWarn("RULE", "Rule [${rule.name}] output not found: $outputName")
                }
            }
        }
    }

    private fun processRuleSync(rule: RuleConfig, inputMessage: InputMessage) {
        LogManager.logDebug("RULE", "Processing rule (sync): ${rule.name} (${rule.pipeline.size} steps)")

        var currentData = inputMessage.data
        var currentJson: JSONObject? = parseJson(currentData)

        for (step in rule.pipeline) {
            val transform = step.transform
            var skipStep = false

            // Apply decode before detect
            if (transform?.decode != null) {
                val decoded = expressionEngine.evaluateDecodePipeline(transform.decode!!, currentData)
                if (decoded != null) {
                    currentData = decoded
                    currentJson = parseJson(currentData)
                } else {
                    LogManager.logDebug("RULE", "Rule [${rule.name}] decode [${transform.decode}] -> null, SKIPPED")
                    skipStep = true
                }
            }

            // Apply detect first if specified
            if (!skipStep && transform?.detect != null) {
                if (!detectMedia(currentData, transform.detect)) {
                    LogManager.logDebug("RULE", "Rule [${rule.name}] detect [${transform.detect}] -> SKIPPED")
                    skipStep = true
                } else if (LogManager.isDebugEnabled()) {
                    LogManager.logDebug("RULE", "Rule [${rule.name}] detect [${transform.detect}] -> PASS, dataLen=${currentData.size}")
                }
            }

            // Apply enrich if specified (before other transforms)
            if (!skipStep && transform?.enrich != null) {
                try {
                    val enriched = kotlinx.coroutines.runBlocking {
                        applyEnrich(transform.enrich, currentData, currentJson)
                    }
                    if (enriched != null) {
                        currentData = enriched
                        currentJson = parseJson(currentData)
                        if (LogManager.isDebugEnabled()) {
                            LogManager.logDebug("RULE", "Rule [${rule.name}] enrich [${transform.enrich}] -> OK, dataLen=${currentData.size}, preview=${expressionEngine.truncateForLog(String(currentData))}")
                        }
                    }
                } catch (e: Exception) {
                    LogManager.logWarn("RULE", "Rule [${rule.name}] enrich [${transform.enrich}] failed: ${e.message}")
                }
            }

            // Apply filter before extract (先过滤，再提取)
            if (!skipStep && transform?.filter != null) {
                if (!expressionEngine.executeTwoPhaseFilter(transform.filter!!, currentJson, currentData, inputMessage.headers)) {
                    LogManager.logDebug("RULE", "Rule [${rule.name}] filter [${transform.filter}] -> REJECTED")
                    skipStep = true
                } else {
                    if (LogManager.isDebugEnabled()) {
                        LogManager.logDebug("RULE", "Rule [${rule.name}] filter [${transform.filter}] -> PASS, json=${expressionEngine.truncateForLog(currentJson?.toString())}, data=${expressionEngine.truncateForLog(String(currentData))}")
                    }
                    if (transform.filter!!.contains("clipboardUpdateBefore")) {
                        ClipboardHistoryDbHelper.updateLastPassedTime(String(currentData))
                    }
                }
            }

            // Apply extract + format if present (过滤通过后再提取)
            if (!skipStep && transform != null) {
                val transformed = applyTransform(transform, currentData, currentJson, inputMessage, rule.name)
                if (transformed == null) {
                    LogManager.logDebug("RULE", "Rule [${rule.name}] extract [${transform.extract}] -> null, SKIPPED")
                    skipStep = true
                } else if (transformed !== currentData) {
                    currentData = transformed
                    currentJson = parseJson(currentData)
                    if (LogManager.isDebugEnabled()) {
                        LogManager.logDebug("RULE", "Rule [${rule.name}] transform extract=[${transform.extract}], format=[${transform.format}] -> OK, dataLen=${currentData.size}, preview=${expressionEngine.truncateForLog(String(currentData))}")
                    }
                } else {
                    if (LogManager.isDebugEnabled()) {
                        LogManager.logDebug("RULE", "Rule [${rule.name}] transform extract=[${transform.extract}], format=[${transform.format}] -> unchanged")
                    }
                }
            }

            if (skipStep) {
                if (transform?.breakOnReject == true) {
                    LogManager.logDebug("RULE", "Rule [${rule.name}] breakOnReject: breaking pipeline")
                    break
                }
                continue
            }

            // Send to outputs
            step.to.forEach { outputName ->
                val output = OutputManager.getOutput(outputName)
                if (output != null) {
                    if (!ForwardService.isForwardingEnabled && output.type != OutputType.internal) {
                        LogManager.logDebug("RULE", "转发已暂停, 跳过输出: $outputName")
                        return@forEach
                    }
                    if (LogManager.isDebugEnabled()) {
                        LogManager.logDebug("RULE", "Rule [${rule.name}] output -> $outputName: sending, data=${expressionEngine.truncateForLog(String(currentData))}, headers=$inputMessage.headers")
                    }
                    val ruleCtx = buildRuleContext(rule.name, inputMessage)
                    val (outData, outHeaders) =
                        applyOutputFormat(output, currentData, inputMessage.headers, ruleCtx)
                    val item =
                        QueueItem(
                            data = outData,
                            metadata = mapOf("rule" to rule.name),
                            headers = outHeaders
                        )
                    output.send(item) { success ->
                        if (success) {
                            LogManager.logDebug("RULE", "Rule [${rule.name}] -> $outputName: OK")
                            onForwarded?.invoke()
                        } else {
                            LogManager.logWarn("RULE", "Rule [${rule.name}] -> $outputName: FAILED")
                        }
                    }
                } else {
                    LogManager.logWarn("RULE", "Rule [${rule.name}] output not found: $outputName")
                }
            }
        }
    }

    private fun buildRuleContext(ruleName: String, inputMessage: InputMessage): Map<String, String> =
        mapOf(
            "rule" to ruleName,
            "source" to inputMessage.source,
            "timestamp" to (System.currentTimeMillis() / 1000).toString(),
            "unix" to System.currentTimeMillis().toString(),
            "receivedAt" to (inputMessage.headers["X-ReceivedAt"] ?: System.currentTimeMillis().toString())
        )

    private fun applyOutputFormat(
        output: Output,
        data: ByteArray,
        headers: Map<String, String>,
        context: Map<String, String>
    ): Pair<ByteArray, Map<String, String>> {
        val steps = output.formatSteps ?: return Pair(data, headers)
        return expressionEngine.applyFormatSteps(steps, data, headers, context)
    }

    private fun applyTransform(
        transform: info.loveyu.mfca.config.TransformConfig,
        data: ByteArray,
        preParsedJson: JSONObject?,
        inputMessage: InputMessage,
        ruleName: String = ""
    ): ByteArray? {
        var currentData = data

        val json = preParsedJson ?: try {
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

        // Apply format template (string shorthand or array steps)
        transform.formatSteps?.let { steps ->
            val context =
                mapOf(
                    "rule" to ruleName,
                    "source" to inputMessage.source,
                    "timestamp" to (System.currentTimeMillis() / 1000).toString(),
                    "unix" to System.currentTimeMillis().toString(),
                    "receivedAt" to (inputMessage.headers["X-ReceivedAt"] ?: System.currentTimeMillis().toString())
                )
            val (newData, _) =
                expressionEngine.applyFormatSteps(steps, currentData, inputMessage.headers, context)
            return newData
        }
        transform.format?.let { template ->
            val context =
                mapOf(
                    "rule" to ruleName,
                    "source" to inputMessage.source,
                    "timestamp" to (System.currentTimeMillis() / 1000).toString(),
                    "unix" to System.currentTimeMillis().toString(),
                    "receivedAt" to (inputMessage.headers["X-ReceivedAt"] ?: System.currentTimeMillis().toString())
                )
            return expressionEngine.evaluateFormatTemplate(template, currentData, json, inputMessage.headers, context)
        }

        return currentData
    }

    private fun evaluateFilter(filter: String, data: ByteArray, headers: Map<String, String>?): Boolean {
        return expressionEngine.executeTwoPhaseFilter(filter, data, headers)
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
                    LogManager.logDebug("RULE", "Error handler sending to $outputName for rule [${rule.name}]")
                    output.send(item, null)
                } else {
                    LogManager.logWarn("RULE", "Error handler: output not found: $outputName for rule [${rule.name}]")
                }
            }
        }
    }

    fun getRulesForInput(inputName: String): List<RuleConfig> {
        return inputRulesMap[inputName] ?: emptyList()
    }

    fun getAllRules(): Map<String, RuleConfig> = rules.toMap()

    fun getExpressionEngine(): ExpressionEngine = expressionEngine

    /**
     * Apply enrich step: parse "type:parameter" → find enricher → call enrich()
     */
    private suspend fun applyEnrich(enrichSpec: String, data: ByteArray, preParsedJson: JSONObject?): ByteArray? {
        val colonIndex = enrichSpec.indexOf(':')
        if (colonIndex == -1) {
            LogManager.logWarn("RULE", "Invalid enrich spec: $enrichSpec (expected type:parameter)")
            return null
        }
        val type = enrichSpec.substring(0, colonIndex)
        val parameter = enrichSpec.substring(colonIndex + 1)

        val enricher = enrichers[type]
        if (enricher == null) {
            LogManager.logWarn("RULE", "Unknown enricher type: $type")
            return null
        }

        val json = preParsedJson ?: try {
            JSONObject(String(data))
        } catch (e: Exception) {
            LogManager.logWarn("RULE", "Enrich requires JSON data, got non-JSON")
            return null
        }

        val enriched = enricher.enrich(json, parameter) ?: return null
        return enriched.toString().toByteArray()
    }

    fun clearEnricherCaches() {
        enrichers.values.forEach { enricher ->
            if (enricher is GotifyIconEnricher) {
                enricher.clearCache()
            }
        }
    }

    fun shutdown() {
        scope.cancel()
        workerPool.close()
        expressionEngine.shutdown()
    }
}
