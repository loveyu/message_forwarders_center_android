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

internal fun tokenize(expr: String): List<FilterToken> {
    val tokens = mutableListOf<FilterToken>()
    var i = 0

    while (i < expr.length) {
        val c = expr[i]

        when {
            c.isWhitespace() -> i++
            c == '(' || c == ')' -> {
                tokens.add(FilterToken(FilterTokenType.PAREN, c.toString()))
                i++
            }
            c == ',' -> {
                tokens.add(FilterToken(FilterTokenType.COMMA, ","))
                i++
            }
            c == '"' || c == '\'' -> {
                val end = expr.indexOf(c, i + 1)
                if (end > i) {
                    tokens.add(FilterToken(FilterTokenType.STRING, expr.substring(i + 1, end)))
                    i = end + 1
                } else {
                    i++
                }
            }
            expr.substring(i).startsWith(">=") -> {
                tokens.add(FilterToken(FilterTokenType.OPERATOR, ">="))
                i += 2
            }
            expr.substring(i).startsWith("<=") -> {
                tokens.add(FilterToken(FilterTokenType.OPERATOR, "<="))
                i += 2
            }
            expr.substring(i).startsWith("==") -> {
                tokens.add(FilterToken(FilterTokenType.OPERATOR, "=="))
                i += 2
            }
            expr.substring(i).startsWith("!=") -> {
                tokens.add(FilterToken(FilterTokenType.OPERATOR, "!="))
                i += 2
            }
            c == '>' || c == '<' -> {
                tokens.add(FilterToken(FilterTokenType.OPERATOR, c.toString()))
                i++
            }
            expr.substring(i).startsWith("&&") -> {
                tokens.add(FilterToken(FilterTokenType.LOGICAL, "&&"))
                i += 2
            }
            expr.substring(i).startsWith("||") -> {
                tokens.add(FilterToken(FilterTokenType.LOGICAL, "||"))
                i += 2
            }
            c.isLetter() || c == '_' || c == '$' || c == '.' || c == '[' || c == ']' -> {
                val start = i
                while (i < expr.length && (expr[i].isLetterOrDigit() || expr[i] == '_' || expr[i] == '$' || expr[i] == '.' || expr[i] == '[' || expr[i] == ']' || expr[i] == '@')) {
                    i++
                }
                val word = expr.substring(start, i)
                when {
                    word == "and" -> tokens.add(FilterToken(FilterTokenType.LOGICAL, "&&"))
                    word == "or" -> tokens.add(FilterToken(FilterTokenType.LOGICAL, "||"))
                    word == "not" -> tokens.add(FilterToken(FilterTokenType.NOT, "not"))
                    word in listOf("true", "false", "null") -> tokens.add(FilterToken(FilterTokenType.BOOLEAN, word))
                    else -> tokens.add(FilterToken(FilterTokenType.IDENT, word))
                }
            }
            c.isDigit() -> {
                val start = i
                while (i < expr.length && (expr[i].isDigit() || expr[i] == '.')) {
                    i++
                }
                tokens.add(FilterToken(FilterTokenType.NUMBER, expr.substring(start, i)))
            }
            c == '-' && i + 1 < expr.length && expr[i + 1].isDigit() -> {
                val prev = tokens.lastOrNull()
                if (prev == null || prev.type != FilterTokenType.IDENT && prev.type != FilterTokenType.NUMBER && prev.type != FilterTokenType.STRING && prev.type != FilterTokenType.BOOLEAN) {
                    val start = i
                    i++
                    while (i < expr.length && (expr[i].isDigit() || expr[i] == '.')) {
                        i++
                    }
                    tokens.add(FilterToken(FilterTokenType.NUMBER, expr.substring(start, i)))
                } else {
                    i++
                }
            }
            else -> i++
        }
    }

    return tokens
}

internal fun parseExpression(tokens: List<FilterToken>): ParsedFilter {
    if (tokens.isEmpty()) return ParsedFilter(ParsedNodeType.CONSTANT, true)

    val result = parseOr(tokens, mutableListOf())
    return result.first
}

internal fun parseOr(tokens: List<FilterToken>, accumulated: MutableList<FilterToken>): Pair<ParsedFilter, List<FilterToken>> {
    var (left, remaining) = parseAnd(tokens, accumulated)

    while (remaining.isNotEmpty() && remaining.first().type == FilterTokenType.LOGICAL && remaining.first().value == "||") {
        remaining = remaining.drop(1)
        val (right, newRemaining) = parseAnd(remaining, mutableListOf())
        left = ParsedFilter(type = ParsedNodeType.OR, left = left, right = right)
        remaining = newRemaining
    }

    return Pair(left, remaining)
}

internal fun parseAnd(tokens: List<FilterToken>, accumulated: MutableList<FilterToken>): Pair<ParsedFilter, List<FilterToken>> {
    var (left, remaining) = parsePrimary(tokens, accumulated)

    while (remaining.isNotEmpty() && remaining.first().type == FilterTokenType.LOGICAL && remaining.first().value == "&&") {
        remaining = remaining.drop(1)
        val (right, newRemaining) = parsePrimary(remaining, mutableListOf())
        left = ParsedFilter(type = ParsedNodeType.AND, left = left, right = right)
        remaining = newRemaining
    }

    return Pair(left, remaining)
}

internal fun parsePrimary(tokens: List<FilterToken>, accumulated: MutableList<FilterToken>): Pair<ParsedFilter, List<FilterToken>> {
    if (tokens.isEmpty()) return Pair(ParsedFilter(ParsedNodeType.CONSTANT, true), emptyList())

    val token = tokens.first()
    val remaining = tokens.drop(1)

    return when {
        token.type == FilterTokenType.PAREN && token.value == "(" -> {
            val (expr, rest) = parseOr(remaining, mutableListOf())
            if (rest.isNotEmpty() && rest.first().type == FilterTokenType.PAREN && rest.first().value == ")") {
                Pair(expr, rest.drop(1))
            } else {
                Pair(expr, remaining)
            }
        }
        token.type == FilterTokenType.NOT -> {
            val (operand, rest) = parsePrimary(remaining, mutableListOf())
            Pair(ParsedFilter(ParsedNodeType.NOT, left = operand), rest)
        }
        token.type == FilterTokenType.IDENT -> {
            parseComparison(token.value, remaining)
        }
        token.type == FilterTokenType.NUMBER -> {
            parseLiteralComparison(token.value, remaining)
        }
        token.type == FilterTokenType.STRING -> {
            parseLiteralComparison(token.value, remaining)
        }
        token.type == FilterTokenType.BOOLEAN -> {
            Pair(ParsedFilter(ParsedNodeType.CONSTANT, token.value.toBoolean()), remaining)
        }
        else -> Pair(ParsedFilter(ParsedNodeType.CONSTANT, true), remaining)
    }
}

internal fun parseComparison(path: String, tokens: List<FilterToken>): Pair<ParsedFilter, List<FilterToken>> {
    if (tokens.isEmpty()) {
        return Pair(ParsedFilter(ParsedNodeType.PATH, path = path), tokens)
    }

    val op = tokens.first()
    if (op.type == FilterTokenType.OPERATOR) {
        val remaining = tokens.drop(1)
        if (remaining.isNotEmpty()) {
            val value = remaining.first()
            val valueStr = when (value.type) {
                FilterTokenType.STRING -> value.value
                FilterTokenType.NUMBER -> value.value
                FilterTokenType.BOOLEAN -> value.value
                FilterTokenType.IDENT -> value.value
                else -> value.value
            }
            return Pair(
                ParsedFilter(
                    ParsedNodeType.COMPARISON,
                    path = path,
                    operator = op.value,
                    value = valueStr
                ),
                remaining.drop(1)
            )
        }
    }

    if (tokens.isNotEmpty() && tokens.first().type == FilterTokenType.PAREN && tokens.first().value == "(") {
        return parseFunctionCall(path, tokens)
    }

    return Pair(ParsedFilter(ParsedNodeType.PATH, path = path), tokens)
}

internal fun parseLiteralComparison(literalValue: String, tokens: List<FilterToken>): Pair<ParsedFilter, List<FilterToken>> {
    if (tokens.isEmpty()) {
        val numVal = literalValue.toDoubleOrNull()
        return if (numVal != null) {
            Pair(ParsedFilter(ParsedNodeType.CONSTANT, numVal != 0.0), tokens)
        } else {
            Pair(ParsedFilter(ParsedNodeType.CONSTANT, literalValue.isNotEmpty()), tokens)
        }
    }

    val op = tokens.first()
    if (op.type == FilterTokenType.OPERATOR) {
        val remaining = tokens.drop(1)
        if (remaining.isNotEmpty()) {
            val value = remaining.first()
            val valueStr = when (value.type) {
                FilterTokenType.STRING -> value.value
                FilterTokenType.NUMBER -> value.value
                FilterTokenType.BOOLEAN -> value.value
                FilterTokenType.IDENT -> value.value
                else -> value.value
            }
            return Pair(
                ParsedFilter(
                    ParsedNodeType.LITERAL_COMPARISON,
                    path = literalValue,
                    operator = op.value,
                    value = valueStr
                ),
                remaining.drop(1)
            )
        }
    }

    val numVal = literalValue.toDoubleOrNull()
    return if (numVal != null) {
        Pair(ParsedFilter(ParsedNodeType.CONSTANT, numVal != 0.0), tokens)
    } else {
        Pair(ParsedFilter(ParsedNodeType.CONSTANT, literalValue.isNotEmpty()), tokens)
    }
}

internal fun parseFunctionCall(name: String, tokens: List<FilterToken>): Pair<ParsedFilter, List<FilterToken>> {
    var remaining = tokens.drop(1)
    val args = mutableListOf<String>()

    while (remaining.isNotEmpty() && !(remaining.first().type == FilterTokenType.PAREN && remaining.first().value == ")")) {
        if (remaining.first().type == FilterTokenType.IDENT) {
            args.add(remaining.first().value)
        } else if (remaining.first().type == FilterTokenType.STRING || remaining.first().type == FilterTokenType.NUMBER) {
            args.add(remaining.first().value)
        }
        remaining = remaining.drop(1)
        if (remaining.isNotEmpty() && remaining.first().type == FilterTokenType.COMMA) {
            remaining = remaining.drop(1)
        }
    }

    if (remaining.isNotEmpty() && remaining.first().type == FilterTokenType.PAREN && remaining.first().value == ")") {
        remaining = remaining.drop(1)
    }

    return Pair(
        ParsedFilter(
            ParsedNodeType.FUNCTION,
            path = name,
            args = args
        ),
        remaining
    )
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
