package info.loveyu.mfca.pipeline

import android.content.Context
import android.provider.Settings
import info.loveyu.mfca.clipboard.ClipboardHistoryDbHelper
import info.loveyu.mfca.config.AppConfig
import info.loveyu.mfca.config.CallConfig
import info.loveyu.mfca.config.RuleConfig
import info.loveyu.mfca.input.InputMessage
import info.loveyu.mfca.output.FanOut
import info.loveyu.mfca.output.Output
import info.loveyu.mfca.output.OutputManager
import info.loveyu.mfca.output.OutputType
import info.loveyu.mfca.queue.QueueItem
import info.loveyu.mfca.queue.QueueManager
import info.loveyu.mfca.service.ForwardService
import info.loveyu.mfca.util.LogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
    private val callConfigs = ConcurrentHashMap<String, CallConfig>()

    // 表达式引擎
    private val expressionEngine = ExpressionEngine()

    // Worker 线程池
    private val workerPool = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(workerPool + SupervisorJob())

    // Enrichers
    private val enrichers = ConcurrentHashMap<String, Enricher>()

    // 共享 OkHttpClient，用于 call 资源的 HTTP 请求
    private val callHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    init {
        config.rules.forEach { rule ->
            rules[rule.name] = rule
            val fromValues = rule.froms
            fromValues.forEach { from ->
                inputRulesMap.getOrPut(from) { mutableListOf() }.add(rule)
            }
        }
        config.calls.forEach { call ->
            callConfigs[call.name] = call
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
     * 处理死信消息。使用 deadLetter.pipeline 配置构建合成规则，同步执行。
     * 死信消息标记 isDeadLetter=true，不允许二次入队。
     */
    fun processDeadLetter(message: InputMessage) {
        val dlConfig = config.deadLetter
        if (!dlConfig.enabled || dlConfig.pipeline.isEmpty()) {
            LogManager.logDebug("RULE", "Dead letter pipeline not configured, dropping message")
            return
        }

        val syntheticRule =
            RuleConfig(
                name = "_deadLetter",
                froms = listOf(message.source),
                pipeline = dlConfig.pipeline
            )

        LogManager.logDebug("RULE", "Processing dead letter message from=${message.source}")
        try {
            processRuleSync(syntheticRule, message)
        } catch (e: Exception) {
            LogManager.logWarn("RULE", "Dead letter pipeline error: ${e.message}")
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
        var currentHeaders = inputMessage.headers
        // 跟踪已解析的 JSONObject，数据不变时复用
        var currentJson: JSONObject? = parseJson(currentData)

        for (step in rule.pipeline) {
            val transform = step.transform
            var skipStep = false

            // Execute call steps first (before other transforms)
            if (!skipStep && transform?.call != null) {
                try {
                    val (newData, newHeaders) =
                        executeCallSteps(
                            transform.call,
                            currentData,
                            currentHeaders,
                            buildRuleContext(rule.name, inputMessage.copy(headers = currentHeaders)),
                            rule.name,
                        )
                    if (newData !== currentData) {
                        currentData = newData
                        currentJson = parseJson(currentData)
                    }
                    currentHeaders = newHeaders
                } catch (e: Exception) {
                    LogManager.logWarn("RULE", "Rule [${rule.name}] call steps failed: ${e.message}")
                }
            }

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
                    expressionEngine.executeTwoPhaseFilter(transform.filter!!, currentJson, currentData, currentHeaders)
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
                val msgForTransform = inputMessage.copy(headers = currentHeaders)
                val transformed = applyTransform(transform, currentData, currentJson, msgForTransform, rule.name)
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
                    // Skip unavailable outputs (e.g. link not connected)
                    if (!output.isAvailable()) {
                        LogManager.logInfo("RULE", "Rule [${rule.name}] output -> $outputName: SKIPPED, output unavailable")
                        return@forEach
                    }
                    if (LogManager.isDebugEnabled()) {
                        LogManager.logDebug("RULE", "Rule [${rule.name}] output -> $outputName: sending, dataLen=${currentData.size}, headers=${expressionEngine.truncateForLog(currentHeaders.toString())}")
                    }
                    val effectiveMsg = inputMessage.copy(headers = currentHeaders)
                    val ruleCtx = buildRuleContext(rule.name, effectiveMsg)
                    val (outData, outHeaders) =
                        applyOutputFormat(output, currentData, currentHeaders, ruleCtx)
                    dispatchToOutput(output, outputName, outData, outHeaders, rule.name, inputMessage.source, onForwarded, inputMessage.isDeadLetter)
                } else {
                    LogManager.logWarn("RULE", "Rule [${rule.name}] output not found: $outputName")
                }
            }
        }
    }

    private fun processRuleSync(rule: RuleConfig, inputMessage: InputMessage) {
        LogManager.logDebug("RULE", "Processing rule (sync): ${rule.name} (${rule.pipeline.size} steps)")

        var currentData = inputMessage.data
        var currentHeaders = inputMessage.headers
        var currentJson: JSONObject? = parseJson(currentData)

        for (step in rule.pipeline) {
            val transform = step.transform
            var skipStep = false

            // Execute call steps first (before other transforms)
            if (!skipStep && transform?.call != null) {
                try {
                    val (newData, newHeaders) =
                        executeCallStepsSync(
                            transform.call,
                            currentData,
                            currentHeaders,
                            buildRuleContext(rule.name, inputMessage.copy(headers = currentHeaders)),
                            rule.name,
                        )
                    if (newData !== currentData) {
                        currentData = newData
                        currentJson = parseJson(currentData)
                    }
                    currentHeaders = newHeaders
                } catch (e: Exception) {
                    LogManager.logWarn("RULE", "Rule [${rule.name}] call steps failed: ${e.message}")
                }
            }

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
                if (!expressionEngine.executeTwoPhaseFilter(transform.filter!!, currentJson, currentData, currentHeaders)) {
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
                val msgForTransform = inputMessage.copy(headers = currentHeaders)
                val transformed = applyTransform(transform, currentData, currentJson, msgForTransform, rule.name)
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
                    // Skip unavailable outputs (e.g. link not connected)
                    if (!output.isAvailable()) {
                        LogManager.logInfo("RULE", "Rule [${rule.name}] output -> $outputName: SKIPPED, output unavailable")
                        return@forEach
                    }
                    if (LogManager.isDebugEnabled()) {
                        LogManager.logDebug("RULE", "Rule [${rule.name}] output -> $outputName: sending, dataLen=${currentData.size}, headers=${expressionEngine.truncateForLog(currentHeaders.toString())}")
                    }
                    val effectiveMsg = inputMessage.copy(headers = currentHeaders)
                    val ruleCtx = buildRuleContext(rule.name, effectiveMsg)
                    val (outData, outHeaders) =
                        applyOutputFormat(output, currentData, currentHeaders, ruleCtx)
                    dispatchToOutput(output, outputName, outData, outHeaders, rule.name, inputMessage.source, onForwarded, inputMessage.isDeadLetter)
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

    /**
     * Dispatch formatted output data to a single target output.
     * Handles FanOut with per-sub-target queuing, single-output queuing, and direct send.
     * If isDeadLetter is true, queueRef is skipped and the item is marked as dead letter.
     */
    private fun dispatchToOutput(
        output: Output,
        outputName: String,
        outData: ByteArray,
        outHeaders: Map<String, String>,
        ruleName: String,
        source: String,
        onForwarded: (() -> Unit)?,
        isDeadLetter: Boolean = false
    ) {
        val queueRef = if (isDeadLetter) null else output.queueRef

        // FanOut with no top-level queue: enqueue per sub-target independently
        if (output is FanOut && queueRef == null) {
            val subTargets = output.subTargets()
            if (subTargets.any { it.queueRef != null && !isDeadLetter }) {
                subTargets.forEach { subTarget ->
                    val subQueueRef = if (isDeadLetter) null else subTarget.queueRef
                    if (subQueueRef != null) {
                        val queue = QueueManager.getQueue(subQueueRef.name)
                        if (queue != null) {
                            val queueItem = QueueItem(
                                data = outData,
                                metadata = mapOf("rule" to ruleName, "source" to source, "outputName" to subTarget.name),
                                headers = outHeaders,
                                nextAttemptAt = System.currentTimeMillis() + subQueueRef.delay.millis,
                                isDeadLetter = isDeadLetter
                            )
                            if (queue.enqueue(queueItem)) {
                                LogManager.logDebug("RULE", "Rule [$ruleName] -> ${subTarget.name}: enqueued to ${subQueueRef.name}")
                                onForwarded?.invoke()
                            } else {
                                LogManager.logWarn("RULE", "Rule [$ruleName] -> ${subTarget.name}: failed to enqueue to ${subQueueRef.name}")
                            }
                        } else {
                            LogManager.logWarn("RULE", "Rule [$ruleName] -> ${subTarget.name}: queue not found: ${subQueueRef.name}")
                        }
                    } else {
                        val item = QueueItem(
                            data = outData,
                            metadata = mapOf("rule" to ruleName, "source" to source, "outputName" to subTarget.name),
                            headers = outHeaders,
                            isDeadLetter = isDeadLetter
                        )
                        subTarget.send(item) { success ->
                            if (success) {
                                LogManager.logDebug("RULE", "Rule [$ruleName] -> ${subTarget.name}: OK")
                                onForwarded?.invoke()
                            } else {
                                LogManager.logWarn("RULE", "Rule [$ruleName] -> ${subTarget.name}: FAILED")
                            }
                        }
                    }
                }
                return
            }
        }

        if (queueRef != null) {
            val queue = QueueManager.getQueue(queueRef.name)
            if (queue != null) {
                val queueItem = QueueItem(
                    data = outData,
                    metadata = mapOf("rule" to ruleName, "source" to source, "outputName" to outputName),
                    headers = outHeaders,
                    nextAttemptAt = System.currentTimeMillis() + queueRef.delay.millis,
                    isDeadLetter = isDeadLetter
                )
                if (queue.enqueue(queueItem)) {
                    LogManager.logDebug("RULE", "Rule [$ruleName] -> $outputName: enqueued to ${queueRef.name}")
                    onForwarded?.invoke()
                } else {
                    LogManager.logWarn("RULE", "Rule [$ruleName] -> $outputName: failed to enqueue to ${queueRef.name}")
                }
            } else {
                LogManager.logWarn("RULE", "Rule [$ruleName] -> $outputName: queue not found: ${queueRef.name}")
            }
        } else {
            val item = QueueItem(
                data = outData,
                metadata = mapOf("rule" to ruleName, "source" to source, "outputName" to outputName),
                headers = outHeaders,
                isDeadLetter = isDeadLetter
            )
            output.send(item) { success ->
                if (success) {
                    LogManager.logDebug("RULE", "Rule [$ruleName] -> $outputName: OK")
                    onForwarded?.invoke()
                } else {
                    LogManager.logWarn("RULE", "Rule [$ruleName] -> $outputName: FAILED")
                }
            }
        }
    }

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
        } catch (_: Exception) {
            null
        }

        // Extract using GJSON path or function expression
        if (transform.extract != null) {
            val context = buildRuleContext(ruleName, inputMessage)
            val extracted = expressionEngine.evaluateExtractExpression(
                data, json, transform.extract, inputMessage.headers, context
            )
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

    // ==================== Call Step Execution ====================

    /**
     * 执行 transform.call 列表中的所有调用步骤（suspend 版本）。
     * 返回更新后的 (data, headers) 对。
     */
    private suspend fun executeCallSteps(
        callSteps: List<Map<String, String>>,
        data: ByteArray,
        headers: Map<String, String>,
        context: Map<String, String>,
        ruleName: String
    ): Pair<ByteArray, Map<String, String>> {
        var currentData = data
        var currentHeaders = headers
        val callVars = mutableMapOf<String, Any?>()

        for (step in callSteps) {
            for ((varName, callExpr) in step) {
                try {
                    val result =
                        executeCallExpression(callExpr, currentData, currentHeaders, context, callVars, ruleName)
                    callVars[varName] = result
                    // If result is a map with data/headers keys, override pipeline vars
                    val resultMap = toMap(result)
                    if (resultMap != null) {
                        if (resultMap.containsKey("data")) {
                            val newData = resultMap["data"]
                            currentData = expressionEngine.anyValueToString(newData).toByteArray()
                        }
                        if (resultMap.containsKey("headers")) {
                            val newHeaders = resultMap["headers"]
                            if (newHeaders is Map<*, *>) {
                                currentHeaders =
                                    newHeaders.entries
                                        .mapNotNull { e ->
                                            val k = e.key?.toString() ?: return@mapNotNull null
                                            val v = e.value?.toString() ?: return@mapNotNull null
                                            k to v
                                        }
                                        .toMap()
                            }
                        }
                    }
                    LogManager.logDebug("RULE", "Rule [$ruleName] call [$varName = $callExpr] -> OK")
                } catch (e: Exception) {
                    LogManager.logWarn("RULE", "Rule [$ruleName] call [$varName = $callExpr] failed: ${e.message}")
                    callVars[varName] = null
                }
            }
        }
        return currentData to currentHeaders
    }

    /**
     * 同步版本的 call 步骤执行（processRuleSync 使用）。
     */
    private fun executeCallStepsSync(
        callSteps: List<Map<String, String>>,
        data: ByteArray,
        headers: Map<String, String>,
        context: Map<String, String>,
        ruleName: String
    ): Pair<ByteArray, Map<String, String>> {
        var currentData = data
        var currentHeaders = headers
        val callVars = mutableMapOf<String, Any?>()

        for (step in callSteps) {
            for ((varName, callExpr) in step) {
                try {
                    val result =
                        executeCallExpressionSync(callExpr, currentData, currentHeaders, context, callVars, ruleName)
                    callVars[varName] = result
                    val resultMap = toMap(result)
                    if (resultMap != null) {
                        if (resultMap.containsKey("data")) {
                            currentData = expressionEngine.anyValueToString(resultMap["data"]).toByteArray()
                        }
                        if (resultMap.containsKey("headers")) {
                            val newHeaders = resultMap["headers"]
                            if (newHeaders is Map<*, *>) {
                                currentHeaders =
                                    newHeaders.entries
                                        .mapNotNull { e ->
                                            val k = e.key?.toString() ?: return@mapNotNull null
                                            val v = e.value?.toString() ?: return@mapNotNull null
                                            k to v
                                        }
                                        .toMap()
                            }
                        }
                    }
                    LogManager.logDebug("RULE", "Rule [$ruleName] call [$varName = $callExpr] -> OK")
                } catch (e: Exception) {
                    LogManager.logWarn("RULE", "Rule [$ruleName] call [$varName = $callExpr] failed: ${e.message}")
                    callVars[varName] = null
                }
            }
        }
        return currentData to currentHeaders
    }

    /**
     * 解析并执行一个 call 表达式，如 "resource-upload(headers, data)"。
     */
    private suspend fun executeCallExpression(
        callExpr: String,
        data: ByteArray,
        headers: Map<String, String>,
        context: Map<String, String>,
        callVars: Map<String, Any?>,
        ruleName: String
    ): Any? {
        val match = ExpressionEngine.FUNC_CALL_REGEX.find(callExpr.trim())
            ?: throw IllegalArgumentException("Invalid call expression: $callExpr")
        val callName = match.groupValues[1]
        val argsStr = match.groupValues[2]
        val argNames = expressionEngine.parseFunctionArgs(argsStr).map { it.trim() }
        val resolvedArgs = resolveCallArgs(argNames, data, headers, callVars)

        val callConfig = callConfigs[callName]
            ?: throw IllegalArgumentException("Unknown call resource: $callName")

        return withContext(Dispatchers.IO) {
            executeHttpCall(callConfig, resolvedArgs, data, headers, context, callVars, ruleName)
        }
    }

    /** 同步版本 */
    private fun executeCallExpressionSync(
        callExpr: String,
        data: ByteArray,
        headers: Map<String, String>,
        context: Map<String, String>,
        callVars: Map<String, Any?>,
        ruleName: String
    ): Any? {
        val match = ExpressionEngine.FUNC_CALL_REGEX.find(callExpr.trim())
            ?: throw IllegalArgumentException("Invalid call expression: $callExpr")
        val callName = match.groupValues[1]
        val argsStr = match.groupValues[2]
        val argNames = expressionEngine.parseFunctionArgs(argsStr).map { it.trim() }
        val resolvedArgs = resolveCallArgs(argNames, data, headers, callVars)

        val callConfig = callConfigs[callName]
            ?: throw IllegalArgumentException("Unknown call resource: $callName")

        return executeHttpCallSync(callConfig, resolvedArgs, data, headers, context, callVars, ruleName)
    }

    /** 将调用参数名解析为具体值 */
    private fun resolveCallArgs(
        argNames: List<String>,
        data: ByteArray,
        headers: Map<String, String>,
        callVars: Map<String, Any?>
    ): List<Any?> =
        argNames.map { name ->
            when {
                name == "data" -> String(data)
                name == "headers" -> headers
                callVars.containsKey(name) -> callVars[name]
                else -> name // treat as string literal
            }
        }

    /**
     * 执行 HTTP call 资源请求（suspend 版本）。
     * url/headers/body 模板中可使用 {args[N]} 等，response 模板处理响应。
     */
    private suspend fun executeHttpCall(
        config: CallConfig,
        args: List<Any?>,
        data: ByteArray,
        headers: Map<String, String>,
        context: Map<String, String>,
        callVars: Map<String, Any?>,
        ruleName: String
    ): Any? {
        val maxAttempts = config.retry?.maxAttempts ?: 1
        var lastException: Exception? = null
        repeat(maxAttempts) { attempt ->
            try {
                val result = doHttpCall(config, args, data, headers, context, callVars)
                return result
            } catch (e: Exception) {
                lastException = e
                if (attempt < maxAttempts - 1) {
                    val interval = config.retry?.interval?.millis ?: 1000L
                    LogManager.logDebug("RULE", "Rule [$ruleName] call [${config.name}] retry ${attempt + 1}/$maxAttempts after ${interval}ms")
                    delay(interval)
                }
            }
        }
        throw lastException ?: IllegalStateException("HTTP call failed after $maxAttempts attempts")
    }

    /** 同步版本 */
    private fun executeHttpCallSync(
        config: CallConfig,
        args: List<Any?>,
        data: ByteArray,
        headers: Map<String, String>,
        context: Map<String, String>,
        callVars: Map<String, Any?>,
        ruleName: String
    ): Any? {
        val maxAttempts = config.retry?.maxAttempts ?: 1
        var lastException: Exception? = null
        repeat(maxAttempts) { attempt ->
            try {
                return doHttpCall(config, args, data, headers, context, callVars)
            } catch (e: Exception) {
                lastException = e
                if (attempt < maxAttempts - 1) {
                    val interval = config.retry?.interval?.millis ?: 1000L
                    Thread.sleep(interval)
                }
            }
        }
        throw lastException ?: IllegalStateException("HTTP call failed after $maxAttempts attempts")
    }

    /**
     * 实际发起 HTTP 请求（OkHttp 同步调用，可在 IO 线程或 runBlocking 中使用）。
     */
    private fun doHttpCall(
        config: CallConfig,
        args: List<Any?>,
        data: ByteArray,
        headers: Map<String, String>,
        context: Map<String, String>,
        callVars: Map<String, Any?>
    ): Any? {
        val timeoutMillis = config.timeout.millis

        // Build a per-request client with the configured timeout
        val client = callHttpClient.newBuilder()
            .connectTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .build()

        // Evaluate URL template
        val url = expressionEngine.evaluateFormatTemplateWithExtras(
            config.url, data, headers, context, args, callVars
        )

        // Evaluate request headers
        val requestHeaders = config.headers.mapValues { (_, v) ->
            expressionEngine.evaluateFormatTemplateWithExtras(v, data, headers, context, args, callVars)
        }

        // Evaluate body template (defaults to current data if not set)
        val bodyStr = if (config.body != null) {
            expressionEngine.evaluateFormatTemplateWithExtras(config.body, data, headers, context, args, callVars)
        } else {
            String(data)
        }
        val contentType = requestHeaders["content-type"]
            ?: requestHeaders["Content-Type"]
            ?: "application/octet-stream"
        val requestBody = bodyStr.toRequestBody(contentType.toMediaType())

        val requestBuilder = Request.Builder().url(url)
        requestHeaders.forEach { (k, v) -> requestBuilder.header(k, v) }
        requestBuilder.method(config.method, if (config.method == "GET" || config.method == "HEAD") null else requestBody)

        client.newCall(requestBuilder.build()).execute().use { response ->
            val responseCode = response.code
            val responseBody = response.body?.string() ?: ""
            val responseHeaders =
                response.headers.names().associate { name ->
                    name to (response.headers[name] ?: "")
                }
            LogManager.logDebug(
                "RULE",
                "Call [${config.name}] ${config.method} $url -> $responseCode, bodyLen=${responseBody.length}"
            )
            return evaluateResponseTemplate(config.response, responseBody, responseHeaders, responseCode, data, headers, context, args, callVars)
        }
    }

    /**
     * 处理 response 模板，生成 call 调用的最终结果值。
     * 如果 response 为 null，返回原始响应体字符串。
     * 模板中 {response} 代表响应体，可返回字符串、map 或 list。
     */
    private fun evaluateResponseTemplate(
        template: String?,
        responseBody: String,
        responseHeaders: Map<String, String>,
        responseCode: Int,
        data: ByteArray,
        headers: Map<String, String>,
        context: Map<String, String>,
        args: List<Any?>,
        callVars: Map<String, Any?>
    ): Any? {
        if (template == null) {
            return tryParseJson(responseBody) ?: responseBody
        }
        val extendedContext =
            context + mapOf(
                "responseCode" to responseCode.toString(),
                "response" to responseBody,
            )
        val extendedCallVars = callVars + mapOf("response" to (tryParseJson(responseBody) ?: responseBody))
        val responseHeaders2 = headers + responseHeaders.mapKeys { "response.${it.key}" }
        val evaluated = expressionEngine.evaluateFormatTemplateWithExtras(
            template, data, responseHeaders2, extendedContext, args, extendedCallVars
        )
        return tryParseJson(evaluated) ?: evaluated
    }

    private fun tryParseJson(str: String): Any? {
        if (str.isBlank()) return null
        return try {
            JSONObject(str)
        } catch (_: Exception) {
            try {
                org.json.JSONArray(str)
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun toMap(value: Any?): Map<String, Any?>? {
        if (value == null) return null
        if (value is JSONObject) {
            return value.keys().asSequence().associateWith { key -> value.opt(key) }
        }
        if (value is Map<*, *>) {
            @Suppress("UNCHECKED_CAST")
            return value as? Map<String, Any?>
        }
        return null
    }

    // ==================== Enrich ====================

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
