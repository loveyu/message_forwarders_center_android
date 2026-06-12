package info.loveyu.mfca.pipeline.core

import android.content.Context
import info.loveyu.mfca.pipeline.enrich.Enricher
import info.loveyu.mfca.pipeline.enrich.GotifyIconEnricher
import info.loveyu.mfca.pipeline.expression.*
import android.provider.Settings
import info.loveyu.mfca.clipboard.ClipboardHistoryDbHelper
import info.loveyu.mfca.config.models.AppConfig
import info.loveyu.mfca.config.models.CallConfig
import info.loveyu.mfca.config.models.RuleConfig
import info.loveyu.mfca.input.InputMessage
import info.loveyu.mfca.output.Output
import info.loveyu.mfca.output.OutputManager
import info.loveyu.mfca.output.OutputType
import info.loveyu.mfca.service.ForwardService
import info.loveyu.mfca.util.LogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RuleEngine(
    private val config: AppConfig,
    private val context: Context? = null,
    private val onForwarded: (() -> Unit)? = null
) {
    private val rules = mutableMapOf<String, RuleConfig>()
    private val inputRulesMap = ConcurrentHashMap<String, MutableList<RuleConfig>>()
    private val callConfigs = ConcurrentHashMap<String, CallConfig>()

    internal val expressionEngine = ExpressionEngine()

    private val workerPool = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(workerPool + SupervisorJob())

    internal val enrichers = ConcurrentHashMap<String, Enricher>()

    private val callHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    internal val callHandler = RuleEngineCallHandler(callConfigs, callHttpClient, expressionEngine)
    internal val outputDispatcher = RuleEngineOutputDispatcher(
        dispatcherLookup = { name -> info.loveyu.mfca.output.OutputManager.getOutputPluginDispatcher(name) }
    )

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

        val gotifyIconEnricher = GotifyIconEnricher(context)
        enrichers[gotifyIconEnricher.type] = gotifyIconEnricher

        context?.let { ctx ->
            val appCtx = ctx.applicationContext

            expressionEngine.clipboardUpdateBeforeFn = { text ->
                ClipboardHistoryDbHelper.getSecondsSinceLastUpdate(appCtx, text)
            }

            expressionEngine.registerRawDataFunction(
                "clipboardNew",
                RawDataFunction("clipboardNew") { data, args ->
                    val seconds = args.getOrNull(0)
                    val maxAgeMs = when (seconds) {
                        is Long -> seconds * 1000L
                        is Double -> (seconds * 1000).toLong()
                        is String -> seconds.toLongOrNull()?.times(1000L) ?: 10_000L
                        else -> 10_000L
                    }
                    val text = String(data)
                    val sec = expressionEngine.clipboardUpdateBeforeFn?.invoke(text) ?: -1L
                    sec < 0 || sec * 1000 > maxAgeMs
                }
            )

            expressionEngine.deviceIdValue =
                Settings.Secure.getString(appCtx.contentResolver, Settings.Secure.ANDROID_ID) ?: ""
        }

        precompileExpressions()
    }

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

    private suspend fun processRule(rule: RuleConfig, inputMessage: InputMessage) {
        LogManager.logDebug("RULE", "Processing rule: ${rule.name} (${rule.pipeline.size} steps)")

        var currentData = inputMessage.data
        var currentHeaders = inputMessage.headers
        var currentJson: JSONObject? = parseJson(currentData)

        for (step in rule.pipeline) {
            val transform = step.transform
            var skipStep = false

            if (!skipStep && transform?.call != null) {
                try {
                    val (newData, newHeaders) =
                        callHandler.executeCallSteps(
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

            if (!skipStep && transform?.detect != null) {
                if (!detectMedia(currentData, transform.detect)) {
                    LogManager.logDebug("RULE", "Rule [${rule.name}] detect [${transform.detect}] -> SKIPPED")
                    skipStep = true
                } else if (LogManager.isDebugEnabled()) {
                    LogManager.logDebug("RULE", "Rule [${rule.name}] detect [${transform.detect}] -> PASS, dataLen=${currentData.size}")
                }
            }

            if (!skipStep && transform?.enrich != null) {
                try {
                    val enriched = applyEnrich(transform.enrich, currentData, currentJson, enrichers)
                    if (enriched != null) {
                        currentData = enriched
                        currentJson = parseJson(currentData)
                        if (LogManager.isDebugEnabled()) {
                            LogManager.logDebug("RULE", "Rule [${rule.name}] enrich [${transform.enrich}] -> OK, dataLen=${currentData.size}, preview=${truncateForLog(String(currentData))}")
                        }
                    }
                } catch (e: Exception) {
                    LogManager.logWarn("RULE", "Rule [${rule.name}] enrich [${transform.enrich}] failed: ${e.message}")
                }
            }

            if (!skipStep && transform?.filter != null) {
                val passed = withContext(Dispatchers.Default) {
                    expressionEngine.executeTwoPhaseFilter(transform.filter!!, currentJson, currentData, currentHeaders)
                }
                if (!passed) {
                    LogManager.logDebug("RULE", "Rule [${rule.name}] filter [${transform.filter}] -> REJECTED")
                    skipStep = true
                } else {
                    if (LogManager.isDebugEnabled()) {
                        LogManager.logDebug("RULE", "Rule [${rule.name}] filter [${transform.filter}] -> PASS, json=${truncateForLog(currentJson?.toString())}, data=${truncateForLog(String(currentData))}")
                    }
                    if (transform.filter!!.contains("clipboardUpdateBefore")) {
                        ClipboardHistoryDbHelper.updateLastPassedTime(String(currentData))
                    }
                }
            }

            if (!skipStep && transform != null) {
                val msgForTransform = inputMessage.copy(headers = currentHeaders)
                val transformed = applyTransform(transform, currentData, currentJson, msgForTransform, expressionEngine, rule.name)
                if (transformed == null) {
                    LogManager.logDebug("RULE", "Rule [${rule.name}] extract [${transform.extract}] -> null, SKIPPED")
                    skipStep = true
                } else if (transformed !== currentData) {
                    currentData = transformed
                    currentJson = parseJson(currentData)
                    if (LogManager.isDebugEnabled()) {
                        LogManager.logDebug("RULE", "Rule [${rule.name}] transform extract=[${transform.extract}], format=[${transform.format}] -> OK, dataLen=${currentData.size}, preview=${truncateForLog(String(currentData))}")
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

            step.to.forEach { outputName ->
                val output = OutputManager.getOutput(outputName)
                if (output != null) {
                    if (!ForwardService.isForwardingEnabled && output.type != OutputType.internal) {
                        LogManager.logDebug("RULE", "转发已暂停, 跳过输出: $outputName")
                        return@forEach
                    }
                    if (!output.isAvailable()) {
                        LogManager.logInfo("RULE", "Rule [${rule.name}] output -> $outputName: SKIPPED, output unavailable")
                        return@forEach
                    }
                    if (LogManager.isDebugEnabled()) {
                        LogManager.logDebug("RULE", "Rule [${rule.name}] output -> $outputName: sending, dataLen=${currentData.size}, headers=${truncateForLog(currentHeaders.toString())}")
                    }
                    val effectiveMsg = inputMessage.copy(headers = currentHeaders)
                    val ruleCtx = buildRuleContext(rule.name, effectiveMsg)
                    val (outData, outHeaders) =
                        applyOutputFormat(expressionEngine, output, currentData, currentHeaders, ruleCtx)
                    outputDispatcher.dispatchToOutput(output, outputName, outData, outHeaders, rule.name, inputMessage.source, onForwarded, inputMessage.isDeadLetter)
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

            if (!skipStep && transform?.call != null) {
                try {
                    val (newData, newHeaders) =
                        callHandler.executeCallStepsSync(
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

            if (!skipStep && transform?.detect != null) {
                if (!detectMedia(currentData, transform.detect)) {
                    LogManager.logDebug("RULE", "Rule [${rule.name}] detect [${transform.detect}] -> SKIPPED")
                    skipStep = true
                } else if (LogManager.isDebugEnabled()) {
                    LogManager.logDebug("RULE", "Rule [${rule.name}] detect [${transform.detect}] -> PASS, dataLen=${currentData.size}")
                }
            }

            if (!skipStep && transform?.enrich != null) {
                try {
                    val enriched = kotlinx.coroutines.runBlocking {
                        applyEnrich(transform.enrich, currentData, currentJson, enrichers)
                    }
                    if (enriched != null) {
                        currentData = enriched
                        currentJson = parseJson(currentData)
                        if (LogManager.isDebugEnabled()) {
                            LogManager.logDebug("RULE", "Rule [${rule.name}] enrich [${transform.enrich}] -> OK, dataLen=${currentData.size}, preview=${truncateForLog(String(currentData))}")
                        }
                    }
                } catch (e: Exception) {
                    LogManager.logWarn("RULE", "Rule [${rule.name}] enrich [${transform.enrich}] failed: ${e.message}")
                }
            }

            if (!skipStep && transform?.filter != null) {
                if (!expressionEngine.executeTwoPhaseFilter(transform.filter!!, currentJson, currentData, currentHeaders)) {
                    LogManager.logDebug("RULE", "Rule [${rule.name}] filter [${transform.filter}] -> REJECTED")
                    skipStep = true
                } else {
                    if (LogManager.isDebugEnabled()) {
                        LogManager.logDebug("RULE", "Rule [${rule.name}] filter [${transform.filter}] -> PASS, json=${truncateForLog(currentJson?.toString())}, data=${truncateForLog(String(currentData))}")
                    }
                    if (transform.filter!!.contains("clipboardUpdateBefore")) {
                        ClipboardHistoryDbHelper.updateLastPassedTime(String(currentData))
                    }
                }
            }

            if (!skipStep && transform != null) {
                val msgForTransform = inputMessage.copy(headers = currentHeaders)
                val transformed = applyTransform(transform, currentData, currentJson, msgForTransform, expressionEngine, rule.name)
                if (transformed == null) {
                    LogManager.logDebug("RULE", "Rule [${rule.name}] extract [${transform.extract}] -> null, SKIPPED")
                    skipStep = true
                } else if (transformed !== currentData) {
                    currentData = transformed
                    currentJson = parseJson(currentData)
                    if (LogManager.isDebugEnabled()) {
                        LogManager.logDebug("RULE", "Rule [${rule.name}] transform extract=[${transform.extract}], format=[${transform.format}] -> OK, dataLen=${currentData.size}, preview=${truncateForLog(String(currentData))}")
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

            step.to.forEach { outputName ->
                val output = OutputManager.getOutput(outputName)
                if (output != null) {
                    if (!ForwardService.isForwardingEnabled && output.type != OutputType.internal) {
                        LogManager.logDebug("RULE", "转发已暂停, 跳过输出: $outputName")
                        return@forEach
                    }
                    if (!output.isAvailable()) {
                        LogManager.logInfo("RULE", "Rule [${rule.name}] output -> $outputName: SKIPPED, output unavailable")
                        return@forEach
                    }
                    if (LogManager.isDebugEnabled()) {
                        LogManager.logDebug("RULE", "Rule [${rule.name}] output -> $outputName: sending, dataLen=${currentData.size}, headers=${truncateForLog(currentHeaders.toString())}")
                    }
                    val effectiveMsg = inputMessage.copy(headers = currentHeaders)
                    val ruleCtx = buildRuleContext(rule.name, effectiveMsg)
                    val (outData, outHeaders) =
                        applyOutputFormat(expressionEngine, output, currentData, currentHeaders, ruleCtx)
                    outputDispatcher.dispatchToOutput(output, outputName, outData, outHeaders, rule.name, inputMessage.source, onForwarded, inputMessage.isDeadLetter)
                } else {
                    LogManager.logWarn("RULE", "Rule [${rule.name}] output not found: $outputName")
                }
            }
        }
    }

    private fun handleError(rule: RuleConfig, inputMessage: InputMessage, error: Exception) {
        rule.onError?.forEach { step ->
            step.to.forEach { outputName ->
                val output = OutputManager.getOutput(outputName)
                if (output != null) {
                    val item = info.loveyu.mfca.queue.QueueItem(
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

internal fun applyOutputFormat(
    expressionEngine: ExpressionEngine,
    output: Output,
    data: ByteArray,
    headers: Map<String, String>,
    context: Map<String, String>
): Pair<ByteArray, Map<String, String>> {
    val steps = output.formatSteps ?: return Pair(data, headers)
    return expressionEngine.applyFormatSteps(steps, data, headers, context)
}
