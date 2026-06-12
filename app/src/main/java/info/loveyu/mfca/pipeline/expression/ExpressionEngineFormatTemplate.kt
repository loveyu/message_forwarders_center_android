package info.loveyu.mfca.pipeline.expression

import info.loveyu.mfca.util.LogManager
import org.json.JSONObject

internal fun ExpressionEngine.evaluateFormatTemplate(
    template: String,
    data: ByteArray,
    headers: Map<String, String>,
    context: Map<String, String> = emptyMap()
): ByteArray {
    val dataStr = String(data)
    val json = try { JSONObject(dataStr) } catch (_: Exception) { null }
    return doEvaluateFormatTemplate(template, dataStr, json, headers, context)
}

internal fun ExpressionEngine.evaluateFormatTemplate(
    template: String,
    data: ByteArray,
    preParsedJson: JSONObject?,
    headers: Map<String, String>,
    context: Map<String, String> = emptyMap()
): ByteArray {
    val dataStr = String(data)
    val json = preParsedJson ?: try { JSONObject(dataStr) } catch (_: Exception) { null }
    return doEvaluateFormatTemplate(template, dataStr, json, headers, context)
}

private fun ExpressionEngine.doEvaluateFormatTemplate(
    template: String,
    dataStr: String,
    json: JSONObject?,
    headers: Map<String, String>,
    context: Map<String, String> = emptyMap()
): ByteArray {
    val debugEnabled = LogManager.isDebugEnabled()
    val result = StringBuilder()
    var i = 0
    while (i < template.length) {
        if (template[i] == '{') {
            if (i + 1 < template.length && template[i + 1] == '{') {
                result.append('{')
                i += 2
                continue
            }
            val closeIdx = template.indexOf('}', i + 1)
            if (closeIdx < 0) {
                result.append(template[i])
                i++
                continue
            }
            val expr = template.substring(i + 1, closeIdx).trim()
            val resolved = resolveFormatExpression(expr, dataStr, json, headers, context)
            if (debugEnabled) {
                LogManager.logDebug("EXPR", "Format: {$expr} -> ${truncateForLog(resolved)}")
            }
            result.append(resolved)
            i = closeIdx + 1
        } else {
            result.append(template[i])
            i++
        }
    }
    return result.toString().toByteArray()
}

internal fun ExpressionEngine.resolveFormatExpression(
    expr: String,
    dataStr: String,
    json: JSONObject?,
    headers: Map<String, String>,
    context: Map<String, String> = emptyMap()
): String {
    if (expr == "data") return dataStr
    if (expr == "headers") return JSONObject(headers as Map<*, *>).toString()

    if (expr.startsWith("$") && !expr.startsWith("\$headers.")) {
        val varName = expr.substring(1)
        context[varName]?.let { return it }
        context[expr]?.let { return it }
        return when (varName) {
            "timestamp" -> (System.currentTimeMillis() / 1000).toString()
            "unix" -> System.currentTimeMillis().toString()
            else -> ""
        }
    }

    val normalizedExpr =
        if (expr.startsWith("headers.") && !expr.startsWith("\$headers.")) {
            "\$$expr"
        } else {
            expr
        }

    if (normalizedExpr.startsWith("\$headers.")) {
        return getHeaderIgnoreCase(headers, normalizedExpr.substring(9)) ?: ""
    }

    val funcMatch = FUNC_CALL_REGEX.find(normalizedExpr)
    if (funcMatch != null) {
        val funcName = funcMatch.groupValues[1]
        val argsStr = funcMatch.groupValues[2]
        val fn = builtinFunctions[funcName]
        if (fn != null) {
            val args =
                if (argsStr.isBlank()) emptyArray()
                else {
                    parseFunctionArgs(argsStr)
                        .map { arg ->
                            resolveFormatArg(arg.trim(), dataStr, json, headers, context)
                        }
                        .toTypedArray()
                }
            val result = fn.invoke(args)
            return result?.toString() ?: ""
        }
    }

    if (json != null) {
        if (normalizedExpr.startsWith("data.")) {
            val extracted = extractPath(json, normalizedExpr.substring(5), headers)
            if (extracted != null && extracted != JSONObject.NULL) {
                return extracted.toString()
            }
            return ""
        }
        val extracted = extractPath(json, normalizedExpr, headers)
        if (extracted != null && extracted != JSONObject.NULL) {
            return extracted.toString()
        }
    }

    return ""
}

internal fun ExpressionEngine.resolveFormatArg(
    arg: String,
    dataStr: String,
    json: JSONObject?,
    headers: Map<String, String>,
    context: Map<String, String>
): Any? {
    val stripped =
        if (
            arg.length >= 2 &&
                ((arg.startsWith('"') && arg.endsWith('"')) ||
                    (arg.startsWith('\'') && arg.endsWith('\'')))
        ) {
            arg.substring(1, arg.length - 1)
        } else {
            arg
        }
    if (FUNC_CALL_REGEX.matches(stripped)) {
        return resolveFormatExpression(stripped, dataStr, json, headers, context)
    }

    return when {
        stripped == "data" || stripped == "\$data" -> dataStr
        stripped.startsWith("\$") && !stripped.startsWith("\$headers.") -> {
            val varName = stripped.substring(1)
            context[varName]
                ?: context[stripped]
                ?: when (varName) {
                    "timestamp" -> (System.currentTimeMillis() / 1000).toString()
                    "unix" -> System.currentTimeMillis().toString()
                    else -> null
                }
        }
        stripped.startsWith("headers.") -> getHeaderIgnoreCase(headers, stripped.substring(8))
        stripped.startsWith("\$headers.") -> getHeaderIgnoreCase(headers, stripped.substring(9))
        json != null -> {
            val effectivePath = if (stripped.startsWith("data.")) {
                stripped.substring(5)
            } else {
                stripped
            }
            val pathResult = extractPath(json, effectivePath, headers)
            if (pathResult != null) pathResult
            else {
                stripped.toIntOrNull()
                    ?: stripped.toLongOrNull()
                    ?: stripped.toDoubleOrNull()
            }
        }
        else ->
            stripped.toIntOrNull()
                ?: stripped.toLongOrNull()
                ?: stripped.toDoubleOrNull()
    }
}

internal fun ExpressionEngine.evaluateFormatTemplateWithExtras(
    template: String,
    data: ByteArray,
    headers: Map<String, String>,
    context: Map<String, String> = emptyMap(),
    callArgs: List<Any?> = emptyList(),
    callVars: Map<String, Any?> = emptyMap()
): String {
    val dataStr = String(data)
    val json =
        try {
            JSONObject(dataStr)
        } catch (_: Exception) {
            null
        }
    val result = StringBuilder()
    var i = 0
    while (i < template.length) {
        if (template[i] == '{') {
            if (i + 1 < template.length && template[i + 1] == '{') {
                result.append('{')
                i += 2
                continue
            }
            val closeIdx = template.indexOf('}', i + 1)
            if (closeIdx < 0) {
                result.append(template[i])
                i++
                continue
            }
            val expr = template.substring(i + 1, closeIdx).trim()
            val resolved =
                resolveFormatExpressionWithExtras(
                    expr,
                    dataStr,
                    json,
                    headers,
                    context,
                    callArgs,
                    callVars,
                )
            result.append(resolved)
            i = closeIdx + 1
        } else {
            result.append(template[i])
            i++
        }
    }
    return result.toString()
}

private val ARGS_INDEX_REGEX = Regex("""^args\[(\d+)\](?:\.(.+))?$""")

private fun ExpressionEngine.resolveFormatExpressionWithExtras(
    expr: String,
    dataStr: String,
    json: JSONObject?,
    headers: Map<String, String>,
    context: Map<String, String>,
    callArgs: List<Any?>,
    callVars: Map<String, Any?>
): String {
    val argsMatch = ARGS_INDEX_REGEX.find(expr)
    if (argsMatch != null) {
        val idx = argsMatch.groupValues[1].toIntOrNull() ?: return ""
        val path = argsMatch.groupValues[2]
        val arg = callArgs.getOrNull(idx) ?: return ""
        return if (path.isBlank()) anyValueToString(arg)
        else anyValueToString(extractFromAny(arg, path))
    }

    val effectiveExpr = if (expr == "response") "\$response" else expr

    if (effectiveExpr.startsWith("\$")) {
        val varKey = effectiveExpr.substring(1)
        val dotIdx = varKey.indexOf('.')
        val baseName = if (dotIdx >= 0) varKey.substring(0, dotIdx) else varKey
        val subPath = if (dotIdx >= 0) varKey.substring(dotIdx + 1) else ""
        if (callVars.containsKey(baseName)) {
            val varValue = callVars[baseName]
            return if (subPath.isBlank()) anyValueToString(varValue)
            else anyValueToString(extractFromAny(varValue, subPath))
        }
    } else {
        val dotIdx = effectiveExpr.indexOf('.')
        val baseName = if (dotIdx >= 0) effectiveExpr.substring(0, dotIdx) else effectiveExpr
        val subPath = if (dotIdx >= 0) effectiveExpr.substring(dotIdx + 1) else ""
        if (callVars.containsKey(baseName)) {
            val varValue = callVars[baseName]
            return if (subPath.isBlank()) anyValueToString(varValue)
            else anyValueToString(extractFromAny(varValue, subPath))
        }
    }

    return resolveFormatExpression(expr, dataStr, json, headers, context)
}
