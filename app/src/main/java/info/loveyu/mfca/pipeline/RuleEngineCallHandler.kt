package info.loveyu.mfca.pipeline

import info.loveyu.mfca.config.CallConfig
import info.loveyu.mfca.util.LogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

internal class RuleEngineCallHandler(
    private val callConfigs: ConcurrentHashMap<String, CallConfig>,
    private val callHttpClient: OkHttpClient,
    private val expressionEngine: ExpressionEngine,
) {
    suspend fun executeCallSteps(
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
                    val resultMap = toMap(result)
                    if (resultMap != null) {
                        if (resultMap.containsKey("data")) {
                            currentData = anyValueToString(resultMap["data"]).toByteArray()
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

    fun executeCallStepsSync(
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
                            currentData = anyValueToString(resultMap["data"]).toByteArray()
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

    private suspend fun executeCallExpression(
        callExpr: String,
        data: ByteArray,
        headers: Map<String, String>,
        context: Map<String, String>,
        callVars: Map<String, Any?>,
        ruleName: String
    ): Any? {
        val match = FUNC_CALL_REGEX.find(callExpr.trim())
            ?: throw IllegalArgumentException("Invalid call expression: $callExpr")
        val callName = match.groupValues[1]
        val argsStr = match.groupValues[2]
        val argNames = parseFunctionArgs(argsStr).map { it.trim() }
        val resolvedArgs = resolveCallArgs(argNames, data, headers, callVars)

        val callConfig = callConfigs[callName]
            ?: throw IllegalArgumentException("Unknown call resource: $callName")

        return withContext(Dispatchers.IO) {
            executeHttpCall(callConfig, resolvedArgs, data, headers, context, callVars, ruleName)
        }
    }

    private fun executeCallExpressionSync(
        callExpr: String,
        data: ByteArray,
        headers: Map<String, String>,
        context: Map<String, String>,
        callVars: Map<String, Any?>,
        ruleName: String
    ): Any? {
        val match = FUNC_CALL_REGEX.find(callExpr.trim())
            ?: throw IllegalArgumentException("Invalid call expression: $callExpr")
        val callName = match.groupValues[1]
        val argsStr = match.groupValues[2]
        val argNames = parseFunctionArgs(argsStr).map { it.trim() }
        val resolvedArgs = resolveCallArgs(argNames, data, headers, callVars)

        val callConfig = callConfigs[callName]
            ?: throw IllegalArgumentException("Unknown call resource: $callName")

        return executeHttpCallSync(callConfig, resolvedArgs, data, headers, context, callVars, ruleName)
    }

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
                return doHttpCall(config, args, data, headers, context, callVars)
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

    private fun doHttpCall(
        config: CallConfig,
        args: List<Any?>,
        data: ByteArray,
        headers: Map<String, String>,
        context: Map<String, String>,
        callVars: Map<String, Any?>
    ): Any? {
        val timeoutMillis = config.timeout.millis

        val client = callHttpClient.newBuilder()
            .connectTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .build()

        val url = expressionEngine.evaluateFormatTemplateWithExtras(
            config.url, data, headers, context, args, callVars
        )

        val requestHeaders = config.headers.mapValues { (_, v) ->
            expressionEngine.evaluateFormatTemplateWithExtras(v, data, headers, context, args, callVars)
        }

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
        return expressionEngine.evaluateFormatTemplateWithExtras(
            template, data, responseHeaders2, extendedContext, args, extendedCallVars
        )?.let { tryParseJson(it) ?: it }
    }
}
