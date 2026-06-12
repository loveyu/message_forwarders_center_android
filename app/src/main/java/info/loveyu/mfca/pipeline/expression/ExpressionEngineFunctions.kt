package info.loveyu.mfca.pipeline.expression

import info.loveyu.mfca.util.LogManager
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

internal fun ExpressionEngine.registerBuiltinFunctions() {
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
                is Map<*, *> -> {
                    val obj = JSONObject()
                    arg.forEach { (k, v) -> obj.put(k?.toString() ?: "", v) }
                    obj
                }
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
            SimpleDateFormat(format, Locale.getDefault()).format(Date())
        } catch (_: Exception) {
            System.currentTimeMillis().toString()
        }
    }

    builtinFunctions["uuidv4"] = BuiltinFunction("uuidv4", 0) { _ ->
        UUID.randomUUID().toString()
    }

    builtinFunctions["uuid"] = builtinFunctions["uuidv4"]!!

    builtinFunctions["uuidv3"] = BuiltinFunction("uuidv3", 2) { args ->
        val nsStr = args.getOrNull(0)?.toString()?.trim('"', '\'') ?: "dns"
        val name = args.getOrNull(1)?.toString() ?: ""
        uuidFromHash("MD5", 3, nsStr, name)
    }

    builtinFunctions["uuidv5"] = BuiltinFunction("uuidv5", 2) { args ->
        val nsStr = args.getOrNull(0)?.toString()?.trim('"', '\'') ?: "dns"
        val name = args.getOrNull(1)?.toString() ?: ""
        uuidFromHash("SHA-1", 5, nsStr, name)
    }

    builtinFunctions["uuidv7"] = BuiltinFunction("uuidv7", 0) { _ ->
        val ms = System.currentTimeMillis()
        val rng = SecureRandom()
        val hi = (ms shl 16) or (7L shl 12) or (rng.nextLong() and 0x0FFFL)
        val lo = (rng.nextLong() and 0x3FFFFFFFFFFFFFFFL) or (2L shl 62)
        UUID(hi, lo).toString()
    }

    builtinFunctions["randStr"] = BuiltinFunction("randStr", 1) { args ->
        val n = (args.getOrNull(0) as? Number)?.toInt()?.coerceAtLeast(0) ?: 16
        val charset = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val rng = SecureRandom()
        (1..n).map { charset[rng.nextInt(charset.length)] }.joinToString("")
    }

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
            SimpleDateFormat(format, Locale.getDefault()).format(Date(ms))
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
        val key = args.getOrNull(1)?.toString() ?: return@BuiltinFunction false
        when (val v = args.getOrNull(0)) {
            is JSONObject -> v.has(key)
            is Map<*, *> -> v.containsKey(key)
            else -> false
        }
    }

    builtinFunctions["keys"] = BuiltinFunction("keys", 1) { args ->
        when (val v = args.getOrNull(0)) {
            is JSONObject -> v.keys().asSequence().toList()
            is Map<*, *> -> v.keys.toList()
            else -> emptyList<Any>()
        }
    }

    builtinFunctions["values"] = BuiltinFunction("values", 1) { args ->
        when (val v = args.getOrNull(0)) {
            is JSONObject -> v.keys().asSequence().map { v.opt(it) }.toList()
            is Map<*, *> -> v.values.toList()
            else -> emptyList<Any>()
        }
    }

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
        args.getOrNull(0)?.let { it is JSONArray || it is List<*> } == true
    }

    builtinFunctions["isObject"] = BuiltinFunction("isObject", 1) { args ->
        args.getOrNull(0)?.let { it is JSONObject || it is Map<*, *> } == true
    }

    builtinFunctions["isNull"] = BuiltinFunction("isNull", 1) { args ->
        args.getOrNull(0) == null
    }

    builtinFunctions["clipboardUpdateBefore"] = BuiltinFunction("clipboardUpdateBefore", 1) { args ->
        val text = args.getOrNull(0)?.toString() ?: ""
        clipboardUpdateBeforeFn?.invoke(text) ?: -1L
    }

    builtinFunctions["jsonDecode"] = BuiltinFunction("jsonDecode", 1) { args ->
        val str = args.getOrNull(0)?.toString() ?: return@BuiltinFunction null
        val trimmed = str.trim()
        try {
            when {
                trimmed.startsWith("\"") -> {
                    org.json.JSONTokener(trimmed).nextValue()?.toString() ?: str
                }
                trimmed.startsWith("{") -> jsonToMap(JSONObject(trimmed))
                trimmed.startsWith("[") -> jsonToList(JSONArray(trimmed))
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

private fun uuidFromHash(algorithm: String, version: Int, nsStr: String, name: String): String {
    val nsUuid = when (nsStr.lowercase()) {
        "dns"  -> UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8")
        "url"  -> UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8")
        "oid"  -> UUID.fromString("6ba7b812-9dad-11d1-80b4-00c04fd430c8")
        "x500" -> UUID.fromString("6ba7b814-9dad-11d1-80b4-00c04fd430c8")
        else   -> runCatching { UUID.fromString(nsStr) }
            .getOrDefault(UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8"))
    }
    val nsBytes = ByteArray(16).also { buf ->
        val msb = nsUuid.mostSignificantBits
        val lsb = nsUuid.leastSignificantBits
        for (i in 0..7) buf[i] = ((msb ushr (56 - i * 8)) and 0xFF).toByte()
        for (i in 0..7) buf[i + 8] = ((lsb ushr (56 - i * 8)) and 0xFF).toByte()
    }
    val nameBytes = name.toByteArray(Charsets.UTF_8)
    val digest = java.security.MessageDigest.getInstance(algorithm)
    digest.update(nsBytes)
    val hash = digest.digest(nameBytes)
    hash[6] = ((hash[6].toInt() and 0x0F) or (version shl 4)).toByte()
    hash[8] = ((hash[8].toInt() and 0x3F) or 0x80).toByte()
    val msb = (0..7).fold(0L) { acc, i -> (acc shl 8) or (hash[i].toLong() and 0xFF) }
    val lsb = (8..15).fold(0L) { acc, i -> (acc shl 8) or (hash[i].toLong() and 0xFF) }
    return UUID(msb, lsb).toString()
}
