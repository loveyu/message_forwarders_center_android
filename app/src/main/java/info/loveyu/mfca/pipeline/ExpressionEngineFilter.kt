package info.loveyu.mfca.pipeline

import info.loveyu.mfca.util.LogManager
import org.json.JSONObject

internal fun ExpressionEngine.compileFilter(expression: String): CompiledFilter {
    return compiledFilters.getOrPut(expression) {
        try {
            CompiledFilter(expression, parseFilterExpression(expression), null)
        } catch (e: Exception) {
            LogManager.logWarn("EXPR", "Failed to compile filter: $expression - ${e.message}")
            CompiledFilter(expression, null, e)
        }
    }
}

private fun ExpressionEngine.parseFilterExpression(expression: String): ParsedFilter {
    val tokens = tokenize(expression)
    return parseExpression(tokens)
}

internal fun ExpressionEngine.executeFilter(expression: String, data: ByteArray, headers: Map<String, String>? = null): Boolean {
    val compiled = compileFilter(expression)
    if (compiled.error != null) return true

    val json = try {
        JSONObject(String(data))
    } catch (e: Exception) {
        null
    }

    return evaluateCompiledFilter(compiled.parsed!!, json, data, headers)
}

internal fun ExpressionEngine.executeFilter(expression: String, preParsedJson: JSONObject?, data: ByteArray, headers: Map<String, String>? = null): Boolean {
    val compiled = compileFilter(expression)
    if (compiled.error != null) return true

    val json = preParsedJson ?: try {
        JSONObject(String(data))
    } catch (e: Exception) {
        null
    }

    return evaluateCompiledFilter(compiled.parsed!!, json, data, headers)
}

internal fun ExpressionEngine.executeTwoPhaseFilter(expression: String, data: ByteArray, headers: Map<String, String>? = null): Boolean {
    if (!expression.contains("{")) {
        return executeFilter(expression, data, headers)
    }

    val dataStr = String(data)
    val json = try { JSONObject(dataStr) } catch (_: Exception) { null }
    val resolved = resolveFilterTemplate(expression, dataStr, json, headers ?: emptyMap())
    if (LogManager.isDebugEnabled()) {
        LogManager.logDebug("EXPR", "TwoPhaseFilter: original=${truncateForLog(expression)}, resolved=${truncateForLog(resolved)}")
    }
    return executeFilter(resolved, json, data, headers)
}

internal fun ExpressionEngine.executeTwoPhaseFilter(expression: String, preParsedJson: JSONObject?, data: ByteArray, headers: Map<String, String>? = null): Boolean {
    if (!expression.contains("{")) {
        return executeFilter(expression, preParsedJson, data, headers)
    }

    val dataStr = String(data)
    val json = preParsedJson ?: try { JSONObject(dataStr) } catch (_: Exception) { null }
    val resolved = resolveFilterTemplate(expression, dataStr, json, headers ?: emptyMap())
    if (LogManager.isDebugEnabled()) {
        LogManager.logDebug("EXPR", "TwoPhaseFilter: original=${truncateForLog(expression)}, resolved=${truncateForLog(resolved)}")
    }
    return executeFilter(resolved, json, data, headers)
}

internal fun ExpressionEngine.resolveFilterTemplate(
    expression: String,
    dataStr: String,
    json: JSONObject?,
    headers: Map<String, String>
): String {
    val result = StringBuilder()
    var i = 0
    while (i < expression.length) {
        if (expression[i] == '{') {
            if (i + 1 < expression.length && expression[i + 1] == '{') {
                result.append('{')
                i += 2
                continue
            }
            val closeIdx = expression.indexOf('}', i + 1)
            if (closeIdx < 0) {
                result.append(expression[i])
                i++
                continue
            }
            val expr = expression.substring(i + 1, closeIdx).trim()
            val resolved = resolveFormatExpression(expr, dataStr, json, headers)
            val resolvedDouble = resolved.toDoubleOrNull()
            val prevChar = if (result.isNotEmpty()) result[result.length - 1] else ' '
            val nextIdx = closeIdx + 1
            val nextChar = if (nextIdx < expression.length) expression[nextIdx] else ' '
            val isQuoted = (prevChar == '"' && nextChar == '"') || (prevChar == '\'' && nextChar == '\'')
            if (isQuoted) {
                result.append(resolved.replace("\"", "\\\""))
                result.append(nextChar)
                i = closeIdx + 2
            } else if (resolvedDouble != null) {
                result.append(resolved)
                i = closeIdx + 1
            } else {
                result.append("\"").append(resolved.replace("\"", "\\\"")).append("\"")
                i = closeIdx + 1
            }
        } else {
            result.append(expression[i])
            i++
        }
    }
    return result.toString()
}

internal fun ExpressionEngine.resolvePathValue(path: String, json: JSONObject?, headers: Map<String, String>?): Any? {
    when {
        path.startsWith("\$headers.") -> return getHeaderIgnoreCase(headers, path.substring(9))
        path.startsWith("headers.") -> return getHeaderIgnoreCase(headers, path.substring(8))
    }
    if (json == null) return null
    if (path.startsWith("data.")) {
        return extractPath(json, path.substring(5), headers)
    }
    return extractPath(json, path, headers)
}

internal fun ExpressionEngine.evaluateCompiledFilter(filter: ParsedFilter, json: JSONObject?, data: ByteArray, headers: Map<String, String>?): Boolean {
    val debugEnabled = LogManager.isDebugEnabled()
    return when (filter.type) {
        ParsedNodeType.CONSTANT -> filter.constantValue == true
        ParsedNodeType.PATH -> {
            val path = filter.path ?: ""
            val isHeadersPath = path.startsWith("headers.") || path.startsWith("\$headers.")
            if (json == null && !isHeadersPath) return true
            val extracted = resolvePathValue(path, json, headers)
            val result = extracted != null && extracted != JSONObject.NULL
            if (debugEnabled) {
                LogManager.logDebug("EXPR", "Filter PATH [${filter.path}] -> extracted=${truncateForLog(extracted)}, result=$result")
            }
            result
        }
        ParsedNodeType.COMPARISON -> {
            val path = filter.path ?: ""
            val isHeadersPath = path.startsWith("headers.") || path.startsWith("\$headers.")
            if (json == null && !isHeadersPath) return true
            val extracted = resolvePathValue(path, json, headers)
            val value = filter.value ?: ""

            val result = when (filter.operator) {
                "==" -> extracted?.toString() == value
                "!=" -> extracted?.toString() != value
                ">" -> (extracted as? Number)?.toDouble()?.let { it > (value.toDoubleOrNull() ?: 0.0) } ?: false
                "<" -> (extracted as? Number)?.toDouble()?.let { it < (value.toDoubleOrNull() ?: 0.0) } ?: false
                ">=" -> (extracted as? Number)?.toDouble()?.let { it >= (value.toDoubleOrNull() ?: 0.0) } ?: false
                "<=" -> (extracted as? Number)?.toDouble()?.let { it <= (value.toDoubleOrNull() ?: 0.0) } ?: false
                else -> true
            }
            if (debugEnabled) {
                LogManager.logDebug("EXPR", "Filter CMP [${filter.path}] ${filter.operator} [${filter.value}] -> left=${truncateForLog(extracted)}, result=$result")
            }
            result
        }
        ParsedNodeType.LITERAL_COMPARISON -> {
            val left = filter.path ?: ""
            val right = filter.value ?: ""
            val leftNum = left.toDoubleOrNull()
            val rightNum = right.toDoubleOrNull()

            val result = when (filter.operator) {
                "==" -> left == right
                "!=" -> left != right
                ">" -> if (leftNum != null && rightNum != null) leftNum > rightNum else left > right
                "<" -> if (leftNum != null && rightNum != null) leftNum < rightNum else left < right
                ">=" -> if (leftNum != null && rightNum != null) leftNum >= rightNum else left >= right
                "<=" -> if (leftNum != null && rightNum != null) leftNum <= rightNum else left <= right
                else -> true
            }
            if (debugEnabled) {
                LogManager.logDebug("EXPR", "Filter LITERAL_CMP [$left] ${filter.operator} [$right] -> result=$result")
            }
            result
        }
        ParsedNodeType.AND -> {
            val left = filter.left?.let { evaluateCompiledFilter(it, json, data, headers) } ?: true
            val right = filter.right?.let { evaluateCompiledFilter(it, json, data, headers) } ?: true
            val result = left && right
            if (debugEnabled) {
                LogManager.logDebug("EXPR", "Filter AND -> left=$left, right=$right, result=$result")
            }
            result
        }
        ParsedNodeType.OR -> {
            val left = filter.left?.let { evaluateCompiledFilter(it, json, data, headers) } ?: false
            val right = filter.right?.let { evaluateCompiledFilter(it, json, data, headers) } ?: false
            val result = left || right
            if (debugEnabled) {
                LogManager.logDebug("EXPR", "Filter OR -> left=$left, right=$right, result=$result")
            }
            result
        }
        ParsedNodeType.NOT -> {
            val operand = filter.left?.let { evaluateCompiledFilter(it, json, data, headers) } ?: true
            val result = !operand
            if (debugEnabled) {
                LogManager.logDebug("EXPR", "Filter NOT -> operand=$operand, result=$result")
            }
            result
        }
        ParsedNodeType.FUNCTION -> {
            val rawFn = rawDataFunctions[filter.path]
            if (rawFn != null) {
                val evaluatedArgs = (filter.args ?: emptyList()).map { arg ->
                    parseRawArg(arg)
                }.toTypedArray()
                val fnResult = rawFn.invoke(data, evaluatedArgs)
                val result = fnResult == true || (fnResult is Number && fnResult.toLong() != 0L)
                if (debugEnabled) {
                    LogManager.logDebug("EXPR", "Filter RAW_FN [${filter.path}](${filter.args?.joinToString(",") ?: ""}) -> result=${truncateForLog(fnResult)}")
                }
                return result
            }
            if (json == null) return true
            val fnResult = evaluateFunction(filter.path ?: "", filter.args ?: emptyList(), json, headers)
            if (debugEnabled) {
                LogManager.logDebug("EXPR", "Filter FN [${filter.path}](${filter.args?.joinToString(",") ?: ""}) -> result=$fnResult")
            }
            fnResult
        }
    }
}

internal fun ExpressionEngine.evaluateFunction(name: String, args: List<String>, json: JSONObject, headers: Map<String, String>?): Boolean {
    val fn = builtinFunctions[name] ?: return true
    val evaluatedArgs = args.map { arg ->
        resolvePathValue(arg, json, headers)
    }.toTypedArray()

    val result = fn.invoke(evaluatedArgs)
    return result == true || (result is Number && result.toLong() != 0L)
}

internal fun ExpressionEngine.precompileExpressions(expressions: List<String>) {
    expressions.forEach { compileFilter(it) }
}
