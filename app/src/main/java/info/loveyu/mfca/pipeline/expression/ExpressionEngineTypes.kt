package info.loveyu.mfca.pipeline.expression

import info.loveyu.mfca.util.LogManager
import org.json.JSONArray
import org.json.JSONObject

internal val ARRAY_ACCESS_REGEX = Regex("""\[(\d+)\](.*)""")
internal val PATH_PART_REGEX = Regex("""\.?(\w+)?\[(\d+)\]""")
internal val FUNC_CALL_REGEX = Regex("""^(\w+)\((.*)\)$""")
private val ARGS_INDEX_REGEX = Regex("""^args\[(\d+)\](?:\.(.+))?$""")

internal fun getHeaderIgnoreCase(headers: Map<String, String>?, key: String): String? {
    if (headers == null) return null
    return headers.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value
}

internal fun putHeaderIgnoreCase(headers: Map<String, String>, key: String, value: String): Map<String, String> {
    val mutableHeaders = linkedMapOf<String, String>()
    headers.forEach { (existingKey, existingValue) ->
        if (!existingKey.equals(key, ignoreCase = true)) {
            mutableHeaders[existingKey] = existingValue
        }
    }
    mutableHeaders[key] = value
    return mutableHeaders
}

internal fun removeHeaderIgnoreCase(headers: Map<String, String>, key: String): Map<String, String> {
    val mutableHeaders = linkedMapOf<String, String>()
    headers.forEach { (existingKey, existingValue) ->
        if (!existingKey.equals(key, ignoreCase = true)) {
            mutableHeaders[existingKey] = existingValue
        }
    }
    return mutableHeaders
}

internal fun truncateForLog(value: Any?, maxLen: Int = 200): String {
    if (value == null) return "null"
    val s = value.toString()
    return if (s.length > maxLen) s.substring(0, maxLen) + "..." else s
}

internal fun parseFunctionArgs(argsStr: String): List<String> {
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
            c == '(' && !inQuote -> {
                depth++
                current.append(c)
            }
            c == ')' && !inQuote -> {
                depth--
                current.append(c)
            }
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

internal fun anyValueToString(value: Any?): String {
    if (value == null) return ""
    return when (value) {
        is Map<*, *> -> JSONObject(value as Map<*, *>).toString()
        is List<*> -> {
            val arr = JSONArray()
            value.forEach { arr.put(it) }
            arr.toString()
        }
        is JSONArray -> value.toString()
        is JSONObject -> value.toString()
        else -> value.toString()
    }
}

internal fun parseJsonValueOrString(s: String): Any {
    val trimmed = s.trim()
    return when {
        trimmed == "null" -> JSONObject.NULL
        trimmed == "true" -> true
        trimmed == "false" -> false
        trimmed.startsWith("{") -> runCatching { JSONObject(trimmed) }.getOrDefault(s)
        trimmed.startsWith("[") -> runCatching { JSONArray(trimmed) }.getOrDefault(s)
        else -> trimmed.toLongOrNull() ?: trimmed.toDoubleOrNull() ?: s
    }
}

internal fun jsonToMap(obj: JSONObject): Map<String, Any?> {
    val map = mutableMapOf<String, Any?>()
    for (key in obj.keys()) {
        map[key] = convertJsonValue(obj.opt(key))
    }
    return map
}

internal fun jsonToList(arr: JSONArray): List<Any?> {
    return (0 until arr.length()).map { convertJsonValue(arr.opt(it)) }
}

internal fun convertJsonValue(value: Any?): Any? {
    return when (value) {
        is JSONObject -> jsonToMap(value)
        is JSONArray -> jsonToList(value)
        JSONObject.NULL -> null
        else -> value
    }
}

internal fun removeJsonPath(obj: JSONObject, path: String) {
    val parts = path.split(".")
    if (parts.size == 1) {
        obj.remove(parts[0])
        return
    }
    var current: Any? = obj
    for (i in 0 until parts.size - 1) {
        current = when (current) {
            is JSONObject -> current.opt(parts[i])
            else -> return
        } ?: return
    }
    val lastKey = parts.last()
    when (current) {
        is JSONObject -> current.remove(lastKey)
    }
}

internal fun parseRawArg(arg: String): Any? {
    return arg.toLongOrNull() ?: arg.toDoubleOrNull() ?: arg
}

internal fun resultToByteArray(result: Any?): ByteArray? {
    return when (result) {
        is String -> result.toByteArray()
        is Number -> result.toString().toByteArray()
        is Boolean -> result.toString().toByteArray()
        is Map<*, *> -> JSONObject(result).toString().toByteArray()
        is List<*> -> JSONArray(result).toString().toByteArray()
        null -> null
        else -> result.toString().toByteArray()
    }
}

enum class FilterTokenType {
    IDENT, STRING, NUMBER, BOOLEAN, OPERATOR, LOGICAL, NOT, PAREN, COMMA
}

data class FilterToken(
    val type: FilterTokenType,
    val value: String
)

enum class ParsedNodeType {
    CONSTANT, PATH, COMPARISON, LITERAL_COMPARISON, AND, OR, NOT, FUNCTION
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

data class RawDataFunction(
    val name: String,
    val handler: (ByteArray, Array<Any?>) -> Any?
) {
    fun invoke(data: ByteArray, args: Array<Any?>): Any? {
        return try {
            handler(data, args)
        } catch (e: Exception) {
            LogManager.logWarn("EXPR", "RawDataFunction $name error: ${e.message}")
            null
        }
    }
}
