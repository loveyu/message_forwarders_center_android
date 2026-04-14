package info.loveyu.mfca.pipeline

import info.loveyu.mfca.util.LogManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 表达式引擎
 * 支持 GJSON 风格路径提取和表达式求值
 *
 * GJSON 路径语法:
 * - basic: data.name -> json["data"]["name"]
 * - array: data.items[0] -> json["data"]["items"][0]
 * - wildcards: data.items[*].name -> all items' name
 * - modifiers: @this, @keys, @values, @len
 *
 * 表达式语法:
 * - 比较: data.x > 10, data.y == "hello"
 * - 逻辑: data.x > 0 && data.y < 100
 * - 函数: contains(data.y, "test"), length(data.items) > 0
 */
class ExpressionEngine {

    // 预编译表达式缓存
    private val compiledFilters = ConcurrentHashMap<String, CompiledFilter>()

    // 内置函数
    private val builtinFunctions = ConcurrentHashMap<String, BuiltinFunction>()

    init {
        registerBuiltinFunctions()
    }

    /**
     * 注册内置函数
     */
    private fun registerBuiltinFunctions() {
        // 字符串函数
        builtinFunctions["contains"] = BuiltinFunction("contains", 2) { args ->
            val str = args.getOrNull(0)?.toString() ?: ""
            val sub = args.getOrNull(1)?.toString() ?: ""
            str.contains(sub)
        }

        builtinFunctions["startsWith"] = BuiltinFunction("startsWith", 2) { args ->
            val str = args.getOrNull(0)?.toString() ?: ""
            val prefix = args.getOrNull(1)?.toString() ?: ""
            str.startsWith(prefix)
        }

        builtinFunctions["endsWith"] = BuiltinFunction("endsWith", 2) { args ->
            val str = args.getOrNull(0)?.toString() ?: ""
            val suffix = args.getOrNull(1)?.toString() ?: ""
            str.endsWith(suffix)
        }

        builtinFunctions["length"] = BuiltinFunction("length", 1) { args ->
            when (val v = args.getOrNull(0)) {
                is String -> v.length.toLong()
                is JSONArray -> v.length().toLong()
                is JSONObject -> v.length().toLong()
                is Collection<*> -> v.size.toLong()
                is Map<*, *> -> v.size.toLong()
                else -> 0L
            }
        }

        builtinFunctions["toUpperCase"] = BuiltinFunction("toUpperCase", 1) { args ->
            args.getOrNull(0)?.toString()?.uppercase() ?: ""
        }

        builtinFunctions["toLowerCase"] = BuiltinFunction("toLowerCase", 1) { args ->
            args.getOrNull(0)?.toString()?.lowercase() ?: ""
        }

        builtinFunctions["trim"] = BuiltinFunction("trim", 1) { args ->
            args.getOrNull(0)?.toString()?.trim() ?: ""
        }

        builtinFunctions["base64Decode"] = BuiltinFunction("base64Decode", 1) { args ->
            val str = args.getOrNull(0)?.toString() ?: ""
            try {
                android.util.Base64.decode(str, android.util.Base64.DEFAULT).toString(Charsets.UTF_8)
            } catch (e: Exception) {
                str
            }
        }

        builtinFunctions["replace"] = BuiltinFunction("replace", 3) { args ->
            val str = args.getOrNull(0)?.toString() ?: ""
            val target = args.getOrNull(1)?.toString() ?: ""
            val replacement = args.getOrNull(2)?.toString() ?: ""
            str.replace(target, replacement)
        }

        builtinFunctions["substring"] = BuiltinFunction("substring", 3) { args ->
            val str = args.getOrNull(0)?.toString() ?: ""
            val start = (args.getOrNull(1) as? Number)?.toInt() ?: 0
            val end = (args.getOrNull(2) as? Number)?.toInt() ?: str.length
            str.substring(start, end.coerceAtMost(str.length))
        }

        // 数学函数
        builtinFunctions["abs"] = BuiltinFunction("abs", 1) { args ->
            val num = args.getOrNull(0) as? Number ?: return@BuiltinFunction 0L
            kotlin.math.abs(num.toDouble())
        }

        builtinFunctions["ceil"] = BuiltinFunction("ceil", 1) { args ->
            val num = args.getOrNull(0) as? Number ?: return@BuiltinFunction 0L
            kotlin.math.ceil(num.toDouble())
        }

        builtinFunctions["floor"] = BuiltinFunction("floor", 1) { args ->
            val num = args.getOrNull(0) as? Number ?: return@BuiltinFunction 0L
            kotlin.math.floor(num.toDouble())
        }

        builtinFunctions["round"] = BuiltinFunction("round", 1) { args ->
            val num = args.getOrNull(0) as? Number ?: return@BuiltinFunction 0L
            kotlin.math.round(num.toDouble())
        }

        builtinFunctions["max"] = BuiltinFunction("max", 2) { args ->
            val a = (args.getOrNull(0) as? Number)?.toDouble() ?: return@BuiltinFunction 0L
            val b = (args.getOrNull(1) as? Number)?.toDouble() ?: return@BuiltinFunction 0L
            kotlin.math.max(a, b)
        }

        builtinFunctions["min"] = BuiltinFunction("min", 2) { args ->
            val a = (args.getOrNull(0) as? Number)?.toDouble() ?: return@BuiltinFunction 0L
            val b = (args.getOrNull(1) as? Number)?.toDouble() ?: return@BuiltinFunction 0L
            kotlin.math.min(a, b)
        }

        // JSON 函数
        builtinFunctions["size"] = BuiltinFunction("size", 1) { args ->
            when (val v = args.getOrNull(0)) {
                is JSONArray -> v.length().toLong()
                is JSONObject -> v.length().toLong()
                is Collection<*> -> v.size.toLong()
                is String -> v.length.toLong()
                is Map<*, *> -> v.size.toLong()
                else -> 0L
            }
        }

        builtinFunctions["has"] = BuiltinFunction("has", 2) { args ->
            val json = args.getOrNull(0) as? JSONObject ?: return@BuiltinFunction false
            val key = args.getOrNull(1)?.toString() ?: return@BuiltinFunction false
            json.has(key)
        }

        builtinFunctions["keys"] = BuiltinFunction("keys", 1) { args ->
            val json = args.getOrNull(0) as? JSONObject ?: return@BuiltinFunction emptyList<Any>()
            json.keys().asSequence().toList()
        }

        builtinFunctions["values"] = BuiltinFunction("values", 1) { args ->
            val json = args.getOrNull(0) as? JSONObject ?: return@BuiltinFunction emptyList<Any>()
            json.keys().asSequence().map { json.opt(it) }.toList()
        }

        // 类型检查函数
        builtinFunctions["isString"] = BuiltinFunction("isString", 1) { args ->
            args.getOrNull(0) is String
        }

        builtinFunctions["isNumber"] = BuiltinFunction("isNumber", 1) { args ->
            args.getOrNull(0) is Number
        }

        builtinFunctions["isBool"] = BuiltinFunction("isBool", 1) { args ->
            args.getOrNull(0) is Boolean
        }

        builtinFunctions["isArray"] = BuiltinFunction("isArray", 1) { args ->
            args.getOrNull(0) is JSONArray
        }

        builtinFunctions["isObject"] = BuiltinFunction("isObject", 1) { args ->
            args.getOrNull(0) is JSONObject
        }

        builtinFunctions["isNull"] = BuiltinFunction("isNull", 1) { args ->
            args.getOrNull(0) == null
        }
    }

    /**
     * 解析 GJSON 路径并从 JSON 中提取值
     *
     * 支持的语法:
     * - data.name - 获取对象属性
     * - data.items[0] - 获取数组元素
     * - data.items[*].name - 通配符获取所有元素属性
     * - data.@keys - 获取所有键
     * - data.@values - 获取所有值
     * - data.@len - 获取长度
     * - data.@this - 当前对象
     * - $headers.mqtt_topic - 获取 headers 中的 mqtt_topic
     */
    fun extractPath(json: Any?, path: String, headers: Map<String, String>? = null): Any? {
        if (path.isEmpty() || json == null) return json
        if (path == "\$" || path == "@this") return json

        val trimmedPath = path.trim()

        // 处理 $raw 特殊情况
        if (trimmedPath == "\$raw") {
            return when (json) {
                is JSONObject -> json.toString()
                is JSONArray -> json.toString()
                else -> json.toString()
            }
        }

        // 处理 $headers.* 访问
        if (trimmedPath.startsWith("\$headers.")) {
            val headerKey = trimmedPath.substring(9)
            return headers?.get(headerKey)
        }

        return when (json) {
            is JSONObject -> extractFromObject(json, trimmedPath)
            is JSONArray -> extractFromArray(json, trimmedPath)
            else -> null
        }
    }

    private fun extractFromObject(json: JSONObject, path: String): Any? {
        // 处理修饰符
        if (path.startsWith("@")) {
            return handleModifier(json, path)
        }

        // 检查通配符路径
        if (path.contains("[*]") || path.contains("[*].")) {
            return extractWildcard(json, path)
        }

        // 普通路径解析
        val parts = parsePathParts(path)
        var current: Any? = json

        for (part in parts) {
            current = when {
                part.isArrayAccess -> {
                    val obj = if (part.objectPath.isEmpty()) current else navigateObject(current, part.objectPath)
                    val arr = obj as? JSONArray ?: return null
                    arr.opt(part.arrayIndex ?: 0)
                }
                else -> {
                    navigateObject(current, part.path)
                }
            }
            if (current == null) break
        }

        return current
    }

    private fun extractFromArray(json: JSONArray, path: String): Any? {
        // 处理修饰符
        if (path.startsWith("@")) {
            return handleModifier(json, path)
        }

        // 检查通配符路径
        if (path == "[*]") {
            return (0 until json.length()).map { json.opt(it) }
        }

        if (path.startsWith("[*].")) {
            val subPath = path.substring(4)
            return (0 until json.length()).mapNotNull { i ->
                extractPath(json.opt(i), subPath)
            }
        }

        // 解析数组访问
        val arrayMatch = Regex("""\[(\d+)\](.*)""").find(path)
        if (arrayMatch != null) {
            val index = arrayMatch.groupValues[1].toIntOrNull() ?: return null
            val remaining = arrayMatch.groupValues[2]
            val element = json.opt(index) ?: return null
            return if (remaining.isEmpty()) element else extractPath(element, remaining)
        }

        return null
    }

    private fun handleModifier(json: Any?, modifier: String): Any? {
        return when (modifier) {
            "@keys" -> {
                when (json) {
                    is JSONObject -> json.keys().asSequence().toList()
                    is JSONArray -> (0 until json.length()).map { it.toString() }
                    else -> emptyList<Any>()
                }
            }
            "@values" -> {
                when (json) {
                    is JSONObject -> json.keys().asSequence().map { json.opt(it) }.toList()
                    is JSONArray -> (0 until json.length()).map { json.opt(it) }
                    else -> emptyList<Any>()
                }
            }
            "@len", "@length" -> {
                when (json) {
                    is JSONObject -> json.length().toLong()
                    is JSONArray -> json.length().toLong()
                    is String -> json.length.toLong()
                    is Collection<*> -> json.size.toLong()
                    is Map<*, *> -> json.size.toLong()
                    else -> 0L
                }
            }
            "@this" -> json
            else -> null
        }
    }

    private fun extractWildcard(json: JSONObject, path: String): List<Any?> {
        val result = mutableListOf<Any?>()
        val parts = path.split("[*]", limit = 2)
        if (parts.size != 2) return result

        val basePath = parts[0].trimEnd('.')
        val remaining = parts[1].trimStart('.')

        // 获取基础路径指向的数组
        val array = if (basePath.isEmpty()) {
            null
        } else {
            extractFromObject(json, basePath) as? JSONArray
        }

        if (array != null) {
            for (i in 0 until array.length()) {
                val element = array.opt(i)
                if (remaining.isEmpty()) {
                    result.add(element)
                } else {
                    result.add(extractPath(element, remaining))
                }
            }
        }

        return result
    }

    private fun parsePathParts(path: String): List<PathPart> {
        val parts = mutableListOf<PathPart>()
        var remaining = path

        while (remaining.isNotEmpty()) {
            // 检查数组访问
            val arrayMatch = Regex("""\.?(\w+)?\[(\d+)\]""").find(remaining)
            if (arrayMatch != null) {
                val objectPath = arrayMatch.groupValues[1]
                val arrayIndex = arrayMatch.groupValues[2].toIntOrNull()
                val after = remaining.substring(arrayMatch.range.last + 1)

                parts.add(PathPart(
                    path = objectPath,
                    isArrayAccess = true,
                    arrayIndex = arrayIndex,
                    objectPath = objectPath
                ))

                remaining = after.trimStart('.')
            } else {
                // 普通属性访问
                val dotIndex = remaining.indexOf('.')
                if (dotIndex < 0) {
                    parts.add(PathPart(path = remaining, isArrayAccess = false))
                    break
                } else {
                    parts.add(PathPart(path = remaining.substring(0, dotIndex), isArrayAccess = false))
                    remaining = remaining.substring(dotIndex + 1)
                }
            }
        }

        return parts
    }

    private fun navigateObject(obj: Any?, path: String): Any? {
        if (path.isEmpty()) return obj
        if (obj !is JSONObject) return null
        return obj.opt(path)
    }

    /**
     * 预编译过滤器表达式
     */
    fun compileFilter(expression: String): CompiledFilter {
        return compiledFilters.getOrPut(expression) {
            try {
                CompiledFilter(expression, parseFilterExpression(expression), null)
            } catch (e: Exception) {
                LogManager.logWarn("EXPR", "Failed to compile filter: $expression - ${e.message}")
                CompiledFilter(expression, null, e)
            }
        }
    }

    /**
     * 解析过滤器表达式为可执行结构
     */
    private fun parseFilterExpression(expression: String): ParsedFilter {
        val tokens = tokenize(expression)
        return parseExpression(tokens)
    }

    private fun tokenize(expr: String): List<FilterToken> {
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
                    if (word in listOf("true", "false", "null")) {
                        tokens.add(FilterToken(FilterTokenType.BOOLEAN, word))
                    } else {
                        tokens.add(FilterToken(FilterTokenType.IDENT, word))
                    }
                }
                else -> i++
            }
        }

        return tokens
    }

    private fun parseExpression(tokens: List<FilterToken>): ParsedFilter {
        if (tokens.isEmpty()) return ParsedFilter(ParsedNodeType.CONSTANT, true)

        val result = parseOr(tokens, mutableListOf())
        return result.first
    }

    private fun parseOr(tokens: List<FilterToken>, accumulated: MutableList<FilterToken>): Pair<ParsedFilter, List<FilterToken>> {
        var (left, remaining) = parseAnd(tokens, accumulated)

        while (remaining.isNotEmpty() && remaining.first().type == FilterTokenType.LOGICAL && remaining.first().value == "||") {
            remaining = remaining.drop(1)
            val (right, newRemaining) = parseAnd(remaining, mutableListOf())
            left = ParsedFilter(type = ParsedNodeType.OR, left = left, right = right)
            remaining = newRemaining
        }

        return Pair(left, remaining)
    }

    private fun parseAnd(tokens: List<FilterToken>, accumulated: MutableList<FilterToken>): Pair<ParsedFilter, List<FilterToken>> {
        var (left, remaining) = parsePrimary(tokens, accumulated)

        while (remaining.isNotEmpty() && remaining.first().type == FilterTokenType.LOGICAL && remaining.first().value == "&&") {
            remaining = remaining.drop(1)
            val (right, newRemaining) = parsePrimary(remaining, mutableListOf())
            left = ParsedFilter(type = ParsedNodeType.AND, left = left, right = right)
            remaining = newRemaining
        }

        return Pair(left, remaining)
    }

    private fun parsePrimary(tokens: List<FilterToken>, accumulated: MutableList<FilterToken>): Pair<ParsedFilter, List<FilterToken>> {
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
            token.type == FilterTokenType.IDENT -> {
                parseComparison(token.value, remaining)
            }
            token.type == FilterTokenType.BOOLEAN -> {
                Pair(ParsedFilter(ParsedNodeType.CONSTANT, token.value.toBoolean()), remaining)
            }
            else -> Pair(ParsedFilter(ParsedNodeType.CONSTANT, true), remaining)
        }
    }

    private fun parseComparison(path: String, tokens: List<FilterToken>): Pair<ParsedFilter, List<FilterToken>> {
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

        // 检查函数调用
        if (tokens.isNotEmpty() && tokens.first().type == FilterTokenType.PAREN && tokens.first().value == "(") {
            return parseFunctionCall(path, tokens)
        }

        return Pair(ParsedFilter(ParsedNodeType.PATH, path = path), tokens)
    }

    private fun parseFunctionCall(name: String, tokens: List<FilterToken>): Pair<ParsedFilter, List<FilterToken>> {
        // name is function name, tokens starts with (
        var remaining = tokens.drop(1) // skip (
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

    /**
     * 执行过滤器
     * @param expression 过滤器表达式
     * @param data 消息数据
     * @param headers 可选的 Headers Map，用于 $headers.mqtt_topic 等访问
     */
    fun executeFilter(expression: String, data: ByteArray, headers: Map<String, String>? = null): Boolean {
        val json = try {
            JSONObject(String(data))
        } catch (e: Exception) {
            return true // 非 JSON 数据默认通过
        }

        val compiled = compileFilter(expression)
        if (compiled.error != null) return true

        return evaluateCompiledFilter(compiled.parsed!!, json, headers)
    }

    private fun evaluateCompiledFilter(filter: ParsedFilter, json: JSONObject, headers: Map<String, String>?): Boolean {
        return when (filter.type) {
            ParsedNodeType.CONSTANT -> filter.constantValue == true
            ParsedNodeType.PATH -> {
                val extracted = extractPath(json, filter.path ?: "", headers)
                extracted != null && extracted != JSONObject.NULL
            }
            ParsedNodeType.COMPARISON -> {
                val extracted = extractPath(json, filter.path ?: "", headers)
                val value = filter.value ?: ""

                when (filter.operator) {
                    "==" -> extracted?.toString() == value
                    "!=" -> extracted?.toString() != value
                    ">" -> (extracted as? Number)?.toDouble()?.let { it > (value.toDoubleOrNull() ?: 0.0) } ?: false
                    "<" -> (extracted as? Number)?.toDouble()?.let { it < (value.toDoubleOrNull() ?: 0.0) } ?: false
                    ">=" -> (extracted as? Number)?.toDouble()?.let { it >= (value.toDoubleOrNull() ?: 0.0) } ?: false
                    "<=" -> (extracted as? Number)?.toDouble()?.let { it <= (value.toDoubleOrNull() ?: 0.0) } ?: false
                    else -> true
                }
            }
            ParsedNodeType.AND -> {
                val left = filter.left?.let { evaluateCompiledFilter(it, json, headers) } ?: true
                val right = filter.right?.let { evaluateCompiledFilter(it, json, headers) } ?: true
                left && right
            }
            ParsedNodeType.OR -> {
                val left = filter.left?.let { evaluateCompiledFilter(it, json, headers) } ?: false
                val right = filter.right?.let { evaluateCompiledFilter(it, json, headers) } ?: false
                left || right
            }
            ParsedNodeType.FUNCTION -> {
                evaluateFunction(filter.path ?: "", filter.args ?: emptyList(), json, headers)
            }
            else -> true
        }
    }

    private fun evaluateFunction(name: String, args: List<String>, json: JSONObject, headers: Map<String, String>?): Boolean {
        val fn = builtinFunctions[name] ?: return true
        val evaluatedArgs = args.map { arg ->
            extractPath(json, arg, headers)
        }.toTypedArray()

        val result = fn.invoke(evaluatedArgs)
        return result == true || (result is Number && result.toLong() != 0L)
    }

    /**
     * 预编译并缓存
     */
    fun precompileExpressions(expressions: List<String>) {
        expressions.forEach { compileFilter(it) }
    }

    /**
     * 执行 GJSON 路径提取
     */
    fun extract(json: Any?, path: String): Any? {
        return extractPath(json, path)
    }

    /**
     * 提取并转换数据
     */
    fun extractAndTransform(json: Any?, path: String): ByteArray? {
        val extracted = extractPath(json, path) ?: return null

        return when (extracted) {
            is String -> extracted.toByteArray()
            is Number -> extracted.toString().toByteArray()
            is Boolean -> extracted.toString().toByteArray()
            is JSONArray -> extracted.toString().toByteArray()
            is JSONObject -> extracted.toString().toByteArray()
            is List<*> -> extracted.toString().toByteArray()
            else -> extracted.toString().toByteArray()
        }
    }

    /**
     * 评估提取表达式，支持函数调用
     * 例如: base64Decode(content), toUpperCase(name)
     */
    fun evaluateExtractExpression(json: Any?, expression: String, headers: Map<String, String>? = null): ByteArray? {
        val trimmed = expression.trim()

        // 检查是否是函数调用
        val funcMatch = Regex("""^(\w+)\((.*)\)$""").find(trimmed)
        if (funcMatch != null) {
            val funcName = funcMatch.groupValues[1]
            val argsStr = funcMatch.groupValues[2]
            val fn = builtinFunctions[funcName]

            if (fn != null) {
                // 解析参数
                val args = parseFunctionArgs(argsStr).map { arg ->
                    extractPath(json, arg.trim(), headers)
                }.toTypedArray()

                val result = fn.invoke(args)
                return when (result) {
                    is String -> result.toByteArray()
                    is Number -> result.toString().toByteArray()
                    is Boolean -> result.toString().toByteArray()
                    null -> null
                    else -> result.toString().toByteArray()
                }
            }
        }

        // 否则作为普通路径处理
        return extractAndTransform(json, trimmed)
    }

    private fun parseFunctionArgs(argsStr: String): List<String> {
        if (argsStr.isEmpty()) return emptyList()

        val args = mutableListOf<String>()
        var current = StringBuilder()
        var depth = 0
        var inQuote = false
        var quoteChar = ' '

        for (c in argsStr) {
            when {
                (c == '"' || c == '\'') && !inQuote -> {
                    inQuote = true
                    quoteChar = c
                    current.append(c)
                }
                c == quoteChar && inQuote -> {
                    inQuote = false
                    current.append(c)
                }
                c == '(' && !inQuote -> depth++
                c == ')' && !inQuote -> depth--
                c == ',' && depth == 0 && !inQuote -> {
                    args.add(current.toString())
                    current = StringBuilder()
                }
                else -> current.append(c)
            }
        }
        if (current.isNotEmpty()) {
            args.add(current.toString())
        }
        return args
    }

    /**
     * 获取所有内置函数
     */
    fun getBuiltinFunctions(): Map<String, BuiltinFunction> = builtinFunctions.toMap()

    /**
     * 注册自定义函数
     */
    fun registerCustomFunction(name: String, fn: BuiltinFunction) {
        builtinFunctions[name] = fn
    }

    /**
     * 关闭引擎，释放资源
     */
    fun shutdown() {
        compiledFilters.clear()
    }

    enum class FilterTokenType {
        IDENT, STRING, NUMBER, BOOLEAN, OPERATOR, LOGICAL, PAREN, COMMA
    }

    data class FilterToken(
        val type: FilterTokenType,
        val value: String
    )

    enum class ParsedNodeType {
        CONSTANT, PATH, COMPARISON, AND, OR, FUNCTION
    }

    data class ParsedFilter(
        val type: ParsedNodeType,
        val constantValue: Boolean? = null,
        val path: String? = null,
        val operator: String? = null,
        val value: String? = null,
        val left: ParsedFilter? = null,
        val right: ParsedFilter? = null,
        val args: List<String>? = null
    )

    data class PathPart(
        val path: String,
        val isArrayAccess: Boolean,
        val arrayIndex: Int? = null,
        val objectPath: String = ""
    )

    data class CompiledFilter(
        val source: String,
        val parsed: ParsedFilter?,
        val error: Exception?
    )

    data class BuiltinFunction(
        val name: String,
        val paramCount: Int,
        val handler: (Array<Any?>) -> Any?
    ) {
        fun invoke(args: Array<Any?>): Any? {
            return try {
                handler(args)
            } catch (e: Exception) {
                LogManager.logWarn("EXPR", "Function $name error: ${e.message}")
                null
            }
        }
    }

    /**
     * 评估格式化模板，解析 {expression} 占位符
     *
     * 支持的占位符:
     * - {data}         -> 原始数据字符串
     * - {headers}      -> headers JSON 字符串
     * - {$headers.X}   -> 特定 header 值
     * - {path.to.key}  -> GJSON 路径提取
     * - {func(arg)}    -> 内置函数调用
     *
     * {{ 转义为 {
     */
    fun evaluateFormatTemplate(
        template: String,
        data: ByteArray,
        headers: Map<String, String>
    ): ByteArray {
        val dataStr = String(data)
        val json = try { JSONObject(dataStr) } catch (_: Exception) { null }

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
                result.append(resolveFormatExpression(expr, dataStr, json, headers))
                i = closeIdx + 1
            } else {
                result.append(template[i])
                i++
            }
        }
        return result.toString().toByteArray()
    }

    private fun resolveFormatExpression(
        expr: String,
        dataStr: String,
        json: JSONObject?,
        headers: Map<String, String>
    ): String {
        if (expr == "data") return dataStr
        if (expr == "headers") return JSONObject(headers).toString()

        // headers.X -> $headers.X
        val normalizedExpr = if (expr.startsWith("headers.") && !expr.startsWith("\$headers.")) {
            "\$$expr"
        } else {
            expr
        }

        // $headers.X
        if (normalizedExpr.startsWith("\$headers.")) {
            return headers[normalizedExpr.substring(9)] ?: ""
        }

        // 函数调用 (需要 JSON)
        val funcMatch = Regex("""^(\w+)\((.*)\)$""").find(normalizedExpr)
        if (funcMatch != null && json != null) {
            val funcResult = evaluateExtractExpression(json, normalizedExpr, headers)
            return funcResult?.let { String(it) } ?: ""
        }

        // GJSON 路径提取 (需要 JSON)
        if (json != null) {
            val extracted = extractPath(json, normalizedExpr, headers)
            if (extracted != null && extracted != JSONObject.NULL) {
                return extracted.toString()
            }
        }

        return ""
    }
}
