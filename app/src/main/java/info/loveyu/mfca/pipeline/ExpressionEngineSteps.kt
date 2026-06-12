package info.loveyu.mfca.pipeline

import info.loveyu.mfca.config.models.OutputFormatStep
import info.loveyu.mfca.util.LogManager
import org.json.JSONArray
import org.json.JSONObject

internal fun ExpressionEngine.evaluateDecodePipeline(expression: String, data: ByteArray): ByteArray? {
    val steps = expression.split('|').map { it.trim() }.filter { it.isNotEmpty() }
    if (steps.isEmpty()) return data

    var current: Any? = String(data, Charsets.UTF_8)

    LogManager.logDebug("RULE", "decode pipeline [$expression] start, input type=${current?.javaClass?.simpleName}, len=${(current as? String)?.length ?: -1}")

    for ((index, step) in steps.withIndex()) {
        val inputType = current?.javaClass?.simpleName ?: "null"
        val inputPreview = truncateForLog(current)
        LogManager.logDebug("RULE", "decode pipeline step [$index/$${steps.size - 1}] func=$step, input type=$inputType, value=$inputPreview")

        val result = callDecodeFunction(step, current)
        if (result == null) {
            LogManager.logWarn("RULE", "decode pipeline step [$step] returned null, aborting pipeline [$expression]")
            return null
        }
        current = result

        val outputType = current?.javaClass?.simpleName ?: "null"
        val outputPreview = truncateForLog(current)
        LogManager.logDebug("RULE", "decode pipeline step [$step] result type=$outputType, value=$outputPreview")
    }

    val finalResult = when (current) {
        is JSONObject -> current.toString().toByteArray()
        is JSONArray -> current.toString().toByteArray()
        is String -> current.toByteArray()
        is Map<*, *> -> JSONObject(current as Map<*, *>).toString().toByteArray()
        is List<*> -> JSONArray(current as List<*>).toString().toByteArray()
        else -> current?.toString()?.toByteArray() ?: return null
    }

    LogManager.logDebug("RULE", "decode pipeline [$expression] complete, output type=${current?.javaClass?.simpleName}, outputLen=${finalResult.size}")
    return finalResult
}

private fun ExpressionEngine.callDecodeFunction(name: String, input: Any?): Any? {
    val fn = builtinFunctions[name]
    if (fn == null) {
        LogManager.logWarn("RULE", "decode pipeline: unknown function [$name]")
        return null
    }
    return try {
        fn.invoke(arrayOf(input))
    } catch (e: Exception) {
        LogManager.logWarn("RULE", "decode pipeline function [$name] error: ${e.message}")
        null
    }
}

internal fun ExpressionEngine.evaluateExtractExpression(json: Any?, expression: String, headers: Map<String, String>? = null): ByteArray? {
    val data = json?.toString()?.toByteArray() ?: ByteArray(0)
    val preParsedJson = json as? JSONObject
    return evaluateExtractExpression(data, preParsedJson, expression, headers)
}

internal fun ExpressionEngine.evaluateExtractExpression(
    data: ByteArray,
    preParsedJson: JSONObject?,
    expression: String,
    headers: Map<String, String>? = null,
    context: Map<String, String> = emptyMap()
): ByteArray? {
    val dataStr = String(data)
    val json = preParsedJson ?: try {
        JSONObject(dataStr)
    } catch (_: Exception) {
        null
    }
    val trimmed = expression.trim()
    val result = evaluateExtractExpr(dataStr, json, trimmed, headers, context)
    return resultToByteArray(result)
}

private fun ExpressionEngine.evaluateExtractExpr(
    dataStr: String,
    json: JSONObject?,
    expression: String,
    headers: Map<String, String>?,
    context: Map<String, String>
): Any? {
    val trimmed = expression.trim()
    val debugEnabled = LogManager.isDebugEnabled()

    val funcMatch = FUNC_CALL_REGEX.find(trimmed)
    if (funcMatch != null) {
        val funcName = funcMatch.groupValues[1]
        val argsStr = funcMatch.groupValues[2]
        val fn = builtinFunctions[funcName]

        if (fn != null) {
            val args = parseFunctionArgs(argsStr).map { arg ->
                resolveExtractArgFull(dataStr, json, arg.trim(), headers, context)
            }.toTypedArray()

            if (debugEnabled) {
                LogManager.logDebug("EXPR", "Extract FN $funcName args=[${args.joinToString(", ") { truncateForLog(it) }}]")
            }
            val result = fn.invoke(args)
            if (debugEnabled) {
                LogManager.logDebug("EXPR", "Extract FN $funcName -> result=${truncateForLog(result)}")
            }
            return result
        }
    }

    if (trimmed == "\$raw") return dataStr

    if (trimmed == "data" || trimmed == "\$data") return dataStr

    if (trimmed.startsWith("\$headers.")) {
        return getHeaderIgnoreCase(headers, trimmed.substring(9))
    }
    if (trimmed.startsWith("headers.") && !trimmed.startsWith("\$headers.")) {
        return getHeaderIgnoreCase(headers, trimmed.substring(8))
    }

    if (trimmed.startsWith("$") && !trimmed.startsWith("\$headers.") && trimmed.length > 1) {
        val varName = trimmed.substring(1)
        context[varName]?.let { return it }
    }

    if (json != null) {
        val pathResult = resolvePathValue(trimmed, json, headers)
        if (pathResult != null) return pathResult
    }

    trimmed.toIntOrNull()?.let { return it }
    trimmed.toLongOrNull()?.let { return it }
    trimmed.toDoubleOrNull()?.let { return it }

    if (debugEnabled) {
        LogManager.logDebug("EXPR", "Extract PATH $trimmed -> null")
    }
    return null
}

private fun ExpressionEngine.resolveExtractArgFull(
    dataStr: String,
    json: JSONObject?,
    arg: String,
    headers: Map<String, String>?,
    context: Map<String, String>
): Any? {
    if (
        arg.length >= 2 &&
            ((arg.startsWith('"') && arg.endsWith('"')) ||
                (arg.startsWith('\'') && arg.endsWith('\'')))
    ) {
        return arg.substring(1, arg.length - 1)
    }

    if (FUNC_CALL_REGEX.matches(arg)) {
        return evaluateExtractExpr(dataStr, json, arg, headers, context)
    }

    if (arg == "data" || arg == "\$data") return dataStr

    if (arg.startsWith("\$headers.")) {
        return getHeaderIgnoreCase(headers, arg.substring(9))
    }
    if (arg.startsWith("headers.") && !arg.startsWith("\$headers.")) {
        return getHeaderIgnoreCase(headers, arg.substring(8))
    }

    if (arg.startsWith("$") && !arg.startsWith("\$headers.") && arg.length > 1) {
        val varName = arg.substring(1)
        context[varName]?.let { return it }
    }

    if (json != null) {
        val pathResult = resolvePathValue(arg, json, headers)
        if (pathResult != null) return pathResult
    }

    arg.toIntOrNull()?.let { return it }
    arg.toLongOrNull()?.let { return it }
    arg.toDoubleOrNull()?.let { return it }

    return null
}

internal fun ExpressionEngine.applyFormatSteps(
    steps: List<OutputFormatStep>,
    initialData: ByteArray,
    initialHeaders: Map<String, String>,
    context: Map<String, String> = emptyMap()
): Pair<ByteArray, Map<String, String>> {
    var data = initialData
    var headers = initialHeaders
    val debugEnabled = LogManager.isDebugEnabled()

    for (step in steps) {
        val target = step.target.trim()
        val template = step.template
        val dataStr = String(data)
        val json = try { JSONObject(dataStr) } catch (_: Exception) { null }

        if (debugEnabled) {
            LogManager.logDebug("EXPR", "FormatStep: target=$target, template=${truncateForLog(template)}")
        }

        if (step.raw != null) {
            when (val raw = step.raw) {
                is Map<*, *> -> {
                    if (raw.containsKey("delete")) {
                        val delVal = raw["delete"]
                        val keysToDelete = when (delVal) {
                            is List<*> -> delVal.mapNotNull { it?.toString() }
                            is String -> {
                                if (delVal.trim().startsWith("[")) {
                                    try {
                                        val arr = JSONArray(delVal)
                                        (0 until arr.length()).mapNotNull { i -> arr.optString(i) }
                                    } catch (_: Exception) { delVal.split(',').map { it.trim() } }
                                } else {
                                    delVal.split(',').map { it.trim() }
                                }
                            }
                            else -> listOfNotNull(delVal?.toString())
                        }

                        if (target == "\$data" || target == "\$delete") {
                            val obj = json
                            if (obj != null) {
                                for (k in keysToDelete) {
                                    removeJsonPath(obj, k)
                                }
                                data = obj.toString().toByteArray()
                            }
                        } else if (target == "\$header" || target.startsWith("\$header.")) {
                            for (k in keysToDelete) {
                                headers = removeHeaderIgnoreCase(headers, k)
                            }
                        }

                        if (debugEnabled) {
                            LogManager.logDebug("EXPR", "FormatStep DELETE keys=$keysToDelete")
                        }

                        continue
                    }
                }
                is List<*> -> {
                    if (target == "\$delete" || target == "\$data") {
                        val keysToDelete = raw.mapNotNull { it?.toString() }
                        val obj = json
                        if (obj != null) {
                            for (k in keysToDelete) removeJsonPath(obj, k)
                            data = obj.toString().toByteArray()
                        }
                        if (debugEnabled) {
                            LogManager.logDebug("EXPR", "FormatStep DELETE keys=$keysToDelete")
                        }
                        continue
                    }
                }
            }
        }

        when {
            target == "\$data" -> {
                val result = evaluateFormatTemplate(template, data, json, headers, context)
                data = result
                if (debugEnabled) {
                    LogManager.logDebug("EXPR", "FormatStep \$data -> result=${truncateForLog(String(data))}")
                }
            }
            target.startsWith("\$data.") -> {
                val field = target.removePrefix("\$data.")
                val result = String(evaluateFormatTemplate(template, data, json, headers, context))
                val obj = json ?: JSONObject()
                obj.put(field, parseJsonValueOrString(result))
                data = obj.toString().toByteArray()
                if (debugEnabled) {
                    LogManager.logDebug("EXPR", "FormatStep \$data.$field -> ${truncateForLog(result)}")
                }
            }
            target == "\$header" -> {
                val result = String(evaluateFormatTemplate(template, data, json, headers, context))
                headers = try {
                    val parsed = JSONObject(result)
                    val map = mutableMapOf<String, String>()
                    for (key in parsed.keys()) map[key] = parsed.getString(key)
                    map
                } catch (_: Exception) { headers }
                if (debugEnabled) {
                    LogManager.logDebug("EXPR", "FormatStep \$header -> headers=${truncateForLog(headers.toString())}")
                }
            }
            target.startsWith("\$header.") -> {
                val key = target.removePrefix("\$header.")
                val result = String(evaluateFormatTemplate(template, data, json, headers, context))
                headers = putHeaderIgnoreCase(headers, key, result)
                if (debugEnabled) {
                    LogManager.logDebug("EXPR", "FormatStep \$header.$key -> ${truncateForLog(result)}")
                }
            }
            else -> {
                LogManager.logWarn("EXPR", "Unknown format target: $target")
            }
        }
    }

    return Pair(data, headers)
}
