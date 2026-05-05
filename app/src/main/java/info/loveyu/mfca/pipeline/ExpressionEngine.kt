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

    companion object {
        // 预编译正则，避免热路径每次重新编译
        private val ARRAY_ACCESS_REGEX = Regex("""\[(\d+)\](.*)""")
        private val PATH_PART_REGEX = Regex("""\.?(\w+)?\[(\d+)\]""")
        private val FUNC_CALL_REGEX = Regex("""^(\w+)\((.*)\)$""")
    }

    // 预编译表达式缓存
    private val compiledFilters = ConcurrentHashMap<String, CompiledFilter>()

    // 设备 ID（由外部设置，如 RuleEngine 通过 Android Settings.Secure 获取）
    var deviceIdValue: String = ""

    // 内置函数
    private val builtinFunctions = ConcurrentHashMap<String, BuiltinFunction>()

    // 原始数据函数（可访问原始字节数据，适用于非 JSON 数据场景）
    private val rawDataFunctions = ConcurrentHashMap<String, RawDataFunction>()

    // 剪贴板更新时间检查函数（由外部设置，如 RuleEngine 通过 Android Context 注入）
    var clipboardUpdateBeforeFn: ((String) -> Long)? = null

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

        builtinFunctions["base64Encode"] = BuiltinFunction("base64Encode", 1) { args ->
            val str = args.getOrNull(0)?.toString() ?: ""
            android.util.Base64.encodeToString(str.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
        }

        builtinFunctions["urlEncode"] = BuiltinFunction("urlEncode", 1) { args ->
            val str = args.getOrNull(0)?.toString() ?: ""
            try {
                java.net.URLEncoder.encode(str, "UTF-8")
            } catch (_: Exception) {
                str
            }
        }

        builtinFunctions["jsonEncode"] = BuiltinFunction("jsonEncode", 1) { args ->
            val str = args.getOrNull(0)?.toString() ?: ""
            JSONObject.quote(str)
        }

        builtinFunctions["httpBuildQuery"] = BuiltinFunction("httpBuildQuery", 1) { args ->
            val arg = args.getOrNull(0) ?: return@BuiltinFunction ""
            val json =
                when (arg) {
                    is JSONObject -> arg
                    is String ->
                        try {
                            JSONObject(arg)
                        } catch (_: Exception) {
                            null
                        }
                    else -> null
                }
            if (json == null) {
                return@BuiltinFunction try {
                    java.net.URLEncoder.encode(arg.toString(), "UTF-8")
                } catch (_: Exception) {
                    arg.toString()
                }
            }
            json
                .keys()
                .asSequence()
                .map { key ->
                    val v = json.opt(key)?.toString() ?: ""
                    try {
                        "${java.net.URLEncoder.encode(key, "UTF-8")}=${java.net.URLEncoder.encode(v, "UTF-8")}"
                    } catch (_: Exception) {
                        "$key=$v"
                    }
                }
                .joinToString("&")
        }

        builtinFunctions["now"] = BuiltinFunction("now", -1) { args ->
            val precision = (args.getOrNull(0) as? Number)?.toInt() ?: 0
            val ms = System.currentTimeMillis()
            if (precision <= 0) {
                ms / 1000L
            } else {
                val secs = ms / 1000L
                val millis = ms % 1000
                val fracStr = millis.toString().padStart(3, '0').padEnd(precision, '0').take(precision)
                "$secs.$fracStr"
            }
        }

        builtinFunctions["nowMs"] = BuiltinFunction("nowMs", 0) { _ -> System.currentTimeMillis() }

        builtinFunctions["nowDate"] = BuiltinFunction("nowDate", 1) { args ->
            val format = args.getOrNull(0)?.toString()?.trim('"', '\'') ?: "yyyy-MM-dd HH:mm:ss"
            try {
                java.text.SimpleDateFormat(format, java.util.Locale.getDefault()).format(java.util.Date())
            } catch (_: Exception) {
                System.currentTimeMillis().toString()
            }
        }

        builtinFunctions["uuidv4"] = BuiltinFunction("uuidv4", 0) { _ ->
            java.util.UUID.randomUUID().toString()
        }

        // uuid() is an alias for uuidv4()
        builtinFunctions["uuid"] = builtinFunctions["uuidv4"]!!

        // UUID v3: name-based, MD5 hash (RFC 4122)
        // uuidv3(namespace, name) — namespace: "dns"|"url"|"oid"|"x500" or UUID string
        builtinFunctions["uuidv3"] = BuiltinFunction("uuidv3", 2) { args ->
            val nsStr = args.getOrNull(0)?.toString()?.trim('"', '\'') ?: "dns"
            val name = args.getOrNull(1)?.toString() ?: ""
            uuidFromHash("MD5", 3, nsStr, name)
        }

        // UUID v5: name-based, SHA-1 hash (RFC 4122)
        // uuidv5(namespace, name) — namespace: "dns"|"url"|"oid"|"x500" or UUID string
        builtinFunctions["uuidv5"] = BuiltinFunction("uuidv5", 2) { args ->
            val nsStr = args.getOrNull(0)?.toString()?.trim('"', '\'') ?: "dns"
            val name = args.getOrNull(1)?.toString() ?: ""
            uuidFromHash("SHA-1", 5, nsStr, name)
        }

        // UUID v7: time-ordered, monotonic (draft-ietf-uuidrev-rfc4122bis)
        // Format: 48-bit ms timestamp | 4-bit version(7) | 12-bit random | 2-bit variant | 62-bit random
        builtinFunctions["uuidv7"] = BuiltinFunction("uuidv7", 0) { _ ->
            val ms = System.currentTimeMillis()
            val rng = java.security.SecureRandom()
            val hi = (ms shl 16) or (7L shl 12) or (rng.nextLong() and 0x0FFFL)
            val lo = (rng.nextLong() and 0x3FFFFFFFFFFFFFFFL) or (2L shl 62)
            java.util.UUID(hi, lo).toString()
        }

        builtinFunctions["randStr"] = BuiltinFunction("randStr", 1) { args ->
            val n = (args.getOrNull(0) as? Number)?.toInt()?.coerceAtLeast(0) ?: 16
            val charset = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
            val rng = java.security.SecureRandom()
            (1..n).map { charset[rng.nextInt(charset.length)] }.joinToString("")
        }

        // localIp() — 返回当前设备主要的 IPv4 地址（不需要 Context）
        builtinFunctions["localIp"] = BuiltinFunction("localIp", 0) { _ ->
            try {
                val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
                var result: String? = null
                outer@ while (interfaces.hasMoreElements()) {
                    val ni = interfaces.nextElement()
                    if (!ni.isUp || ni.isLoopback) continue
                    val addrs = ni.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                            result = addr.hostAddress
                            break@outer
                        }
                    }
                }
                result ?: ""
            } catch (_: Exception) { "" }
        }

        // localIps() — 返回所有 IPv4 地址，逗号分隔
        builtinFunctions["localIps"] = BuiltinFunction("localIps", 0) { _ ->
            try {
                val results = mutableListOf<String>()
                val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val ni = interfaces.nextElement()
                    if (!ni.isUp || ni.isLoopback) continue
                    val addrs = ni.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                            addr.hostAddress?.let { results.add(it) }
                        }
                    }
                }
                results.joinToString(",")
            } catch (_: Exception) { "" }
        }

        builtinFunctions["deviceId"] = BuiltinFunction("deviceId", 0) { _ ->
            deviceIdValue
        }

        builtinFunctions["msToDate"] = BuiltinFunction("msToDate", 2) { args ->
            val ms = args.getOrNull(0)?.toString()?.toLongOrNull() ?: System.currentTimeMillis()
            val format = args.getOrNull(1)?.toString()?.trim('"', '\'') ?: "yyyy-MM-dd HH:mm:ss"
            try {
                java.text.SimpleDateFormat(format, java.util.Locale.getDefault()).format(java.util.Date(ms))
            } catch (_: Exception) {
                ms.toString()
            }
        }

        builtinFunctions["msToSec"] = BuiltinFunction("msToSec", -1) { args ->
            val ms = args.getOrNull(0)?.toString()?.toLongOrNull() ?: System.currentTimeMillis()
            val precision = (args.getOrNull(1) as? Number)?.toInt() ?: 3
            if (precision <= 0) {
                ms / 1000L
            } else {
                val secs = ms / 1000L
                val millis = ms % 1000
                val fracStr = millis.toString().padStart(3, '0').padEnd(precision, '0').take(precision)
                "$secs.$fracStr"
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

        // 剪贴板更新时间检查: clipboardUpdateBefore(text) -> 距上次更新的秒数，不存在则返回 -1
        builtinFunctions["clipboardUpdateBefore"] = BuiltinFunction("clipboardUpdateBefore", 1) { args ->
            val text = args.getOrNull(0)?.toString() ?: ""
            clipboardUpdateBeforeFn?.invoke(text) ?: -1L
        }

        // Decode/Encode 函数
        builtinFunctions["jsonDecode"] = BuiltinFunction("jsonDecode", 1) { args ->
            val str = args.getOrNull(0)?.toString() ?: return@BuiltinFunction null
            val trimmed = str.trim()
            try {
                when {
                    trimmed.startsWith("\"") -> {
                        // JSON string literal — use JSONTokener to properly unquote and unescape
                        org.json.JSONTokener(trimmed).nextValue()?.toString() ?: str
                    }
                    trimmed.startsWith("{") -> JSONObject(trimmed)
                    trimmed.startsWith("[") -> JSONArray(trimmed)
                    trimmed == "true" -> true
                    trimmed == "false" -> false
                    trimmed == "null" -> null
                    trimmed.toLongOrNull() != null -> trimmed.toLongOrNull()
                    trimmed.toDoubleOrNull() != null -> trimmed.toDoubleOrNull()
                    else -> str
                }
            } catch (e: Exception) {
                LogManager.logWarn("EXPR", "jsonDecode error: ${e.message}")
                str
            }
        }

        builtinFunctions["yamlDecode"] = BuiltinFunction("yamlDecode", 1) { args ->
            val str = args.getOrNull(0)?.toString() ?: return@BuiltinFunction null
            try {
                val yamlLoad = org.snakeyaml.engine.v2.api.Load(
                    org.snakeyaml.engine.v2.api.LoadSettings.builder().build()
                )
                yamlLoad.loadFromString(str) ?: str
            } catch (e: Exception) {
                LogManager.logWarn("EXPR", "yamlDecode error: ${e.message}")
                str
            }
        }

        builtinFunctions["yamlEncode"] = BuiltinFunction("yamlEncode", 1) { args ->
            val obj = args.getOrNull(0) ?: return@BuiltinFunction ""
            try {
                val yamlDump = org.snakeyaml.engine.v2.api.Dump(
                    org.snakeyaml.engine.v2.api.DumpSettings.builder().build()
                )
                yamlDump.dumpToString(obj)
            } catch (e: Exception) {
                LogManager.logWarn("EXPR", "yamlEncode error: ${e.message}")
                obj.toString()
            }
        }

        builtinFunctions["gzEncode"] = BuiltinFunction("gzEncode", 1) { args ->
            val str = args.getOrNull(0)?.toString() ?: return@BuiltinFunction ""
            try {
                val bos = java.io.ByteArrayOutputStream()
                java.util.zip.GZIPOutputStream(bos).use { gzip ->
                    gzip.write(str.toByteArray(Charsets.UTF_8))
                }
                android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP)
            } catch (e: Exception) {
                LogManager.logWarn("EXPR", "gzEncode error: ${e.message}")
                ""
            }
        }

        builtinFunctions["gzDecode"] = BuiltinFunction("gzDecode", 1) { args ->
            val str = args.getOrNull(0)?.toString() ?: return@BuiltinFunction ""
            try {
                val compressed = android.util.Base64.decode(str, android.util.Base64.NO_WRAP)
                java.io.ByteArrayInputStream(compressed).use { bis ->
                    java.util.zip.GZIPInputStream(bis).use { gzip ->
                        gzip.readBytes().toString(Charsets.UTF_8)
                    }
                }
            } catch (e: Exception) {
                LogManager.logWarn("EXPR", "gzDecode error: ${e.message}")
                ""
            }
        }

        builtinFunctions["gzipEncode"] = builtinFunctions["gzEncode"]!!
        builtinFunctions["gzipDecode"] = builtinFunctions["gzDecode"]!!
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
        val arrayMatch = ARRAY_ACCESS_REGEX.find(path)
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
            val arrayMatch = PATH_PART_REGEX.find(remaining)
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
                    // Negative number: only when '-' precedes a digit and is not preceded by IDENT/NUMBER
                    val prev = tokens.lastOrNull()
                    if (prev == null || prev.type != FilterTokenType.IDENT && prev.type != FilterTokenType.NUMBER && prev.type != FilterTokenType.STRING && prev.type != FilterTokenType.BOOLEAN) {
                        val start = i
                        i++ // skip '-'
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

    /**
     * 解析字面量值比较（用于两阶段过滤器 Phase 2）
     * 左侧是字面量值（数字或字符串），不需要 JSON 路径提取
     */
    private fun parseLiteralComparison(literalValue: String, tokens: List<FilterToken>): Pair<ParsedFilter, List<FilterToken>> {
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

        // No operator following — treat as truthy check
        val numVal = literalValue.toDoubleOrNull()
        return if (numVal != null) {
            Pair(ParsedFilter(ParsedNodeType.CONSTANT, numVal != 0.0), tokens)
        } else {
            Pair(ParsedFilter(ParsedNodeType.CONSTANT, literalValue.isNotEmpty()), tokens)
        }
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
     * 执行过滤器（从原始字节解析 JSON）
     */
    fun executeFilter(expression: String, data: ByteArray, headers: Map<String, String>? = null): Boolean {
        val compiled = compileFilter(expression)
        if (compiled.error != null) return true

        val json = try {
            JSONObject(String(data))
        } catch (e: Exception) {
            null // 非 JSON 数据，对原始数据函数仍可正常求值
        }

        return evaluateCompiledFilter(compiled.parsed!!, json, data, headers)
    }

    /**
     * 执行过滤器（复用已解析的 JSONObject，避免重复解析）
     */
    fun executeFilter(expression: String, preParsedJson: JSONObject?, data: ByteArray, headers: Map<String, String>? = null): Boolean {
        val compiled = compileFilter(expression)
        if (compiled.error != null) return true

        val json = preParsedJson ?: try {
            JSONObject(String(data))
        } catch (e: Exception) {
            null // 非 JSON 数据，对原始数据函数仍可正常求值
        }

        return evaluateCompiledFilter(compiled.parsed!!, json, data, headers)
    }

    /**
     * 两阶段过滤器执行：
     * Phase 1: 解析 {…} 模板占位符为字面值（复用模板引擎）
     * Phase 2: 对解析后的纯布尔表达式求值（无变量访问）
     *
     * 不含 {…} 的表达式走原有的 executeFilter() 路径（向后兼容）
     */
    fun executeTwoPhaseFilter(expression: String, data: ByteArray, headers: Map<String, String>? = null): Boolean {
        if (!expression.contains("{")) {
            return executeFilter(expression, data, headers)
        }

        val dataStr = String(data)
        val json = try { JSONObject(dataStr) } catch (_: Exception) { null }
        val resolved = resolveFilterTemplate(expression, dataStr, json, headers ?: emptyMap())
        return executeFilter(resolved, json, data, headers)
    }

    /**
     * 两阶段过滤器执行（复用已解析的 JSONObject）
     */
    fun executeTwoPhaseFilter(expression: String, preParsedJson: JSONObject?, data: ByteArray, headers: Map<String, String>? = null): Boolean {
        if (!expression.contains("{")) {
            return executeFilter(expression, preParsedJson, data, headers)
        }

        val dataStr = String(data)
        val json = preParsedJson ?: try { JSONObject(dataStr) } catch (_: Exception) { null }
        val resolved = resolveFilterTemplate(expression, dataStr, json, headers ?: emptyMap())
        return executeFilter(resolved, json, data, headers)
    }

    /**
     * 解析过滤模板中的 {…} 占位符，返回纯文本表达式
     * 复用 resolveFormatExpression() 逻辑，{{ 转义为 {
     */
    private fun resolveFilterTemplate(
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
                // Check if the {…} block is wrapped in quotes (e.g. "{type}")
                val prevChar = if (result.isNotEmpty()) result[result.length - 1] else ' '
                val nextIdx = closeIdx + 1
                val nextChar = if (nextIdx < expression.length) expression[nextIdx] else ' '
                val isQuoted = (prevChar == '"' && nextChar == '"') || (prevChar == '\'' && nextChar == '\'')
                if (isQuoted) {
                    // Inside quotes — insert raw value, keep surrounding quotes
                    result.append(resolved.replace("\"", "\\\""))
                    // Append the closing quote and skip past it
                    result.append(nextChar)
                    i = closeIdx + 2
                } else if (resolvedDouble != null) {
                    // Numeric value — no quotes needed
                    result.append(resolved)
                    i = closeIdx + 1
                } else {
                    // String value not in quotes — auto-quote for Phase 2
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

    private fun evaluateCompiledFilter(filter: ParsedFilter, json: JSONObject?, data: ByteArray, headers: Map<String, String>?): Boolean {
        return when (filter.type) {
            ParsedNodeType.CONSTANT -> filter.constantValue == true
            ParsedNodeType.PATH -> {
                if (json == null) return true // 非 JSON 数据默认通过
                val extracted = extractPath(json, filter.path ?: "", headers)
                extracted != null && extracted != JSONObject.NULL
            }
            ParsedNodeType.COMPARISON -> {
                if (json == null) return true // 非 JSON 数据默认通过
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
            ParsedNodeType.LITERAL_COMPARISON -> {
                val left = filter.path ?: ""
                val right = filter.value ?: ""
                val leftNum = left.toDoubleOrNull()
                val rightNum = right.toDoubleOrNull()

                when (filter.operator) {
                    "==" -> left == right
                    "!=" -> left != right
                    ">" -> if (leftNum != null && rightNum != null) leftNum > rightNum else left > right
                    "<" -> if (leftNum != null && rightNum != null) leftNum < rightNum else left < right
                    ">=" -> if (leftNum != null && rightNum != null) leftNum >= rightNum else left >= right
                    "<=" -> if (leftNum != null && rightNum != null) leftNum <= rightNum else left <= right
                    else -> true
                }
            }
            ParsedNodeType.AND -> {
                val left = filter.left?.let { evaluateCompiledFilter(it, json, data, headers) } ?: true
                val right = filter.right?.let { evaluateCompiledFilter(it, json, data, headers) } ?: true
                left && right
            }
            ParsedNodeType.OR -> {
                val left = filter.left?.let { evaluateCompiledFilter(it, json, data, headers) } ?: false
                val right = filter.right?.let { evaluateCompiledFilter(it, json, data, headers) } ?: false
                left || right
            }
            ParsedNodeType.NOT -> {
                val operand = filter.left?.let { evaluateCompiledFilter(it, json, data, headers) } ?: true
                !operand
            }
            ParsedNodeType.FUNCTION -> {
                // 优先检查原始数据函数（支持非 JSON 数据）
                val rawFn = rawDataFunctions[filter.path]
                if (rawFn != null) {
                    val evaluatedArgs = (filter.args ?: emptyList()).map { arg ->
                        parseRawArg(arg)
                    }.toTypedArray()
                    val result = rawFn.invoke(data, evaluatedArgs)
                    return result == true || (result is Number && result.toLong() != 0L)
                }
                if (json == null) return true // 非 JSON 数据且无原始数据函数，默认通过
                evaluateFunction(filter.path ?: "", filter.args ?: emptyList(), json, headers)
            }
        }
    }

    /**
     * 解析函数参数的字面量值（用于原始数据函数，不走 JSON 路径提取）
     * 优先尝试数值解析，否则返回字符串
     */
    private fun parseRawArg(arg: String): Any? {
        return arg.toLongOrNull() ?: arg.toDoubleOrNull() ?: arg
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
     * 评估解码管道表达式，按 `|` 分割函数链式调用。
     * 初始值为 String(data, UTF_8)，每个函数的输出作为下一个函数的输入。
     * 返回解码后的 ByteArray，失败返回 null。
     */
    fun evaluateDecodePipeline(expression: String, data: ByteArray): ByteArray? {
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

    private fun callDecodeFunction(name: String, input: Any?): Any? {
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

    private fun truncateForLog(value: Any?, maxLen: Int = 200): String {
        if (value == null) return "null"
        val s = value.toString()
        return if (s.length > maxLen) s.substring(0, maxLen) + "..." else s
    }

    /**
     * 评估提取表达式，支持函数调用
     * 例如: base64Decode(content), toUpperCase(name)
     */
    fun evaluateExtractExpression(json: Any?, expression: String, headers: Map<String, String>? = null): ByteArray? {
        val trimmed = expression.trim()

        // 检查是否是函数调用
        val funcMatch = FUNC_CALL_REGEX.find(trimmed)
        if (funcMatch != null) {
            val funcName = funcMatch.groupValues[1]
            val argsStr = funcMatch.groupValues[2]
            val fn = builtinFunctions[funcName]

            if (fn != null) {
                // 解析参数
                val args = parseFunctionArgs(argsStr).map { arg ->
                    resolveExtractArg(json, arg.trim(), headers)
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

    private fun resolveExtractArg(
        json: Any?,
        arg: String,
        headers: Map<String, String>?
    ): Any? {
        // Strip surrounding quotes for literal string args
        val stripped =
            if (
                arg.length >= 2 &&
                    ((arg.startsWith('"') && arg.endsWith('"')) ||
                        (arg.startsWith('\'') && arg.endsWith('\'')))
            ) {
                return arg.substring(1, arg.length - 1)
            } else {
                arg
            }
        // Nested function call
        if (FUNC_CALL_REGEX.matches(stripped)) {
            return evaluateExtractExpression(json, stripped, headers)?.let { String(it) }
        }
        // Try JSON path extraction first
        val pathResult = extractPath(json, stripped, headers)
        if (pathResult != null) return pathResult
        // Fallback: literal number
        stripped.toIntOrNull()?.let { return it }
        stripped.toLongOrNull()?.let { return it }
        stripped.toDoubleOrNull()?.let { return it }
        return null
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
     * 注册原始数据函数
     * 原始数据函数可访问原始字节数据，适用于对非 JSON 数据进行过滤（例如 clipboardNew）
     */
    fun registerRawDataFunction(name: String, fn: RawDataFunction) {
        rawDataFunctions[name] = fn
    }

    /**
     * UUID v3/v5 生成：对 namespace + name 进行 MD5/SHA-1 哈希，按 RFC 4122 设置版本和变体位
     */
    private fun uuidFromHash(algorithm: String, version: Int, nsStr: String, name: String): String {
        val nsUuid = when (nsStr.lowercase()) {
            "dns"  -> java.util.UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8")
            "url"  -> java.util.UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8")
            "oid"  -> java.util.UUID.fromString("6ba7b812-9dad-11d1-80b4-00c04fd430c8")
            "x500" -> java.util.UUID.fromString("6ba7b814-9dad-11d1-80b4-00c04fd430c8")
            else   -> runCatching { java.util.UUID.fromString(nsStr) }
                        .getOrDefault(java.util.UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8"))
        }
        // Encode namespace UUID as big-endian bytes
        val nsBytes = ByteArray(16).also { buf ->
            val msb = nsUuid.mostSignificantBits
            val lsb = nsUuid.leastSignificantBits
            for (i in 0..7) buf[i]     = ((msb ushr (56 - i * 8)) and 0xFF).toByte()
            for (i in 0..7) buf[i + 8] = ((lsb ushr (56 - i * 8)) and 0xFF).toByte()
        }
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val digest = java.security.MessageDigest.getInstance(algorithm)
        digest.update(nsBytes)
        val hash = digest.digest(nameBytes)
        // Set version bits
        hash[6] = ((hash[6].toInt() and 0x0F) or (version shl 4)).toByte()
        // Set variant bits (RFC 4122 variant: 10xx xxxx)
        hash[8] = ((hash[8].toInt() and 0x3F) or 0x80).toByte()
        val msb = (0..7).fold(0L) { acc, i -> (acc shl 8) or (hash[i].toLong() and 0xFF) }
        val lsb = (8..15).fold(0L) { acc, i -> (acc shl 8) or (hash[i].toLong() and 0xFF) }
        return java.util.UUID(msb, lsb).toString()
    }

    /**
     * 关闭引擎，释放资源
     */
    fun shutdown() {
        compiledFilters.clear()
    }

    /**
     * 应用格式化步骤序列，返回处理后的 (data, headers)。
     *
     * 每个步骤按顺序执行，前一步的输出作为下一步的输入。
     *
     * target 语法：
     *   $data              替换整个 data（模板结果为新 data 字符串）
     *   $data.field        设置/追加 data JSON 对象的 field 字段（data 非 JSON 时包装为对象）
     *   $header            替换全部 headers（模板必须求值为 JSON 对象字符串）
     *   $header.Key        设置/添加单个 header 键
     */
    fun applyFormatSteps(
        steps: List<info.loveyu.mfca.config.OutputFormatStep>,
        initialData: ByteArray,
        initialHeaders: Map<String, String>,
        context: Map<String, String> = emptyMap()
    ): Pair<ByteArray, Map<String, String>> {
        var data = initialData
        var headers = initialHeaders

        for (step in steps) {
            val target = step.target.trim()
            val template = step.template
            val dataStr = String(data)
            val json = try { org.json.JSONObject(dataStr) } catch (_: Exception) { null }

            // Support delete operations when raw is provided as Map/List
            if (step.raw != null) {
                when (val raw = step.raw) {
                    is Map<*, *> -> {
                        // Map form: { delete: ["a","b"] }
                        if (raw.containsKey("delete")) {
                            val delVal = raw["delete"]
                            val keysToDelete = when (delVal) {
                                is List<*> -> delVal.mapNotNull { it?.toString() }
                                is String -> {
                                    // comma separated or JSON array string
                                    if (delVal.trim().startsWith("[")) {
                                        try {
                                            val arr = org.json.JSONArray(delVal)
                                            (0 until arr.length()).mapNotNull { i -> arr.optString(i) }
                                        } catch (_: Exception) { delVal.split(',').map { it.trim() } }
                                    } else {
                                        delVal.split(',').map { it.trim() }
                                    }
                                }
                                else -> listOfNotNull(delVal?.toString())
                            }

                            if (target == "\$data" || target == "\$delete") {
                                // delete from data JSON object (top-level or nested path using dot)
                                val obj = json
                                if (obj != null) {
                                    for (k in keysToDelete) {
                                        removeJsonPath(obj, k)
                                    }
                                    data = obj.toString().toByteArray()
                                }
                            } else if (target == "\$header" || target.startsWith("\$header.")) {
                                var mutableHeaders = headers.toMutableMap()
                                for (k in keysToDelete) {
                                    mutableHeaders.remove(k)
                                }
                                headers = mutableHeaders.toMap()
                            }

                            // processed delete - continue to next step
                            continue
                        }
                    }
                    is List<*> -> {
                        // list shorthand: - $delete: ["a","b"]
                        if (target == "\$delete" || target == "\$data") {
                            val keysToDelete = raw.mapNotNull { it?.toString() }
                            val obj = json
                            if (obj != null) {
                                for (k in keysToDelete) removeJsonPath(obj, k)
                                data = obj.toString().toByteArray()
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
                }
                target.startsWith("\$data.") -> {
                    val field = target.removePrefix("\$data.")
                    val result = String(evaluateFormatTemplate(template, data, json, headers, context))
                    val obj = json ?: org.json.JSONObject()
                    obj.put(field, parseJsonValueOrString(result))
                    data = obj.toString().toByteArray()
                }
                target == "\$header" -> {
                    val result = String(evaluateFormatTemplate(template, data, json, headers, context))
                    headers = try {
                        val parsed = org.json.JSONObject(result)
                        val map = mutableMapOf<String, String>()
                        for (key in parsed.keys()) map[key] = parsed.getString(key)
                        map
                    } catch (_: Exception) { headers }
                }
                target.startsWith("\$header.") -> {
                    val key = target.removePrefix("\$header.")
                    val result = String(evaluateFormatTemplate(template, data, json, headers, context))
                    headers = headers + (key to result)
                }
                else -> {
                    LogManager.logWarn("EXPR", "Unknown format target: $target")
                }
            }
        }

        return Pair(data, headers)
    }

    /** 尝试将字符串解析为 JSON 值（对象/数组/数字/布尔/null），失败则返回原字符串 */
    private fun parseJsonValueOrString(s: String): Any {
        val trimmed = s.trim()
        return when {
            trimmed == "null" -> org.json.JSONObject.NULL
            trimmed == "true" -> true
            trimmed == "false" -> false
            trimmed.startsWith("{") -> runCatching { org.json.JSONObject(trimmed) }.getOrDefault(s)
            trimmed.startsWith("[") -> runCatching { org.json.JSONArray(trimmed) }.getOrDefault(s)
            else -> trimmed.toLongOrNull() ?: trimmed.toDoubleOrNull() ?: s
        }
    }

    /**
     * 从 JSONObject 中删除指定路径的字段。
     * 支持顶层键删除（如 "password"）和点号路径嵌套删除（如 "data.nested.field"）。
     * 路径不存在时静默跳过。
     */
    private fun removeJsonPath(obj: JSONObject, path: String) {
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

    /**
     * 原始数据函数：handler 接收原始字节数据和解析后的参数
     * 参数值优先解析为数字（Long/Double），否则作为字符串
     */
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
        headers: Map<String, String>,
        context: Map<String, String> = emptyMap()
    ): ByteArray {
        val dataStr = String(data)
        val json = try { JSONObject(dataStr) } catch (_: Exception) { null }
        return doEvaluateFormatTemplate(template, dataStr, json, headers, context)
    }

    /**
     * 格式化模板（复用已解析的 JSONObject）
     */
    fun evaluateFormatTemplate(
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

    private fun doEvaluateFormatTemplate(
        template: String,
        dataStr: String,
        json: JSONObject?,
        headers: Map<String, String>,
        context: Map<String, String> = emptyMap()
    ): ByteArray {

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
                result.append(resolveFormatExpression(expr, dataStr, json, headers, context))
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
        headers: Map<String, String>,
        context: Map<String, String> = emptyMap()
    ): String {
        if (expr == "data") return dataStr
        if (expr == "headers") return JSONObject(headers as Map<*, *>).toString()

        // Context variables: $rule, $source, $timestamp, $unix, etc. (but not $headers.X)
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

        // headers.X -> $headers.X
        val normalizedExpr =
            if (expr.startsWith("headers.") && !expr.startsWith("\$headers.")) {
                "\$$expr"
            } else {
                expr
            }

        // $headers.X
        if (normalizedExpr.startsWith("\$headers.")) {
            return headers[normalizedExpr.substring(9)] ?: ""
        }

        // Function call — works even when json is null (e.g. now(), base64Encode(data))
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

        // GJSON path extraction (needs JSON)
        if (json != null) {
            val extracted = extractPath(json, normalizedExpr, headers)
            if (extracted != null && extracted != JSONObject.NULL) {
                return extracted.toString()
            }
        }

        return ""
    }

    private fun resolveFormatArg(
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
        // Nested function call: resolve recursively
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
            stripped.startsWith("\$headers.") -> headers[stripped.substring(9)]
            json != null -> {
                val pathResult = extractPath(json, stripped, headers)
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
}
