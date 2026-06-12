package info.loveyu.mfca.pipeline

import info.loveyu.mfca.input.InputMessage
import org.json.JSONObject

internal fun parseJson(data: ByteArray): JSONObject? {
    return try {
        JSONObject(String(data))
    } catch (_: Exception) {
        null
    }
}

internal fun buildRuleContext(ruleName: String, inputMessage: InputMessage): Map<String, String> =
    mapOf(
        "rule" to ruleName,
        "source" to inputMessage.source,
        "timestamp" to (System.currentTimeMillis() / 1000).toString(),
        "unix" to System.currentTimeMillis().toString(),
        "receivedAt" to (inputMessage.headers["X-ReceivedAt"] ?: System.currentTimeMillis().toString())
    )

internal fun detectMedia(data: ByteArray, type: String): Boolean {
    return when (type.lowercase()) {
        "image" -> isImage(data)
        "json" -> isJson(data)
        "text" -> isText(data)
        else -> true
    }
}

internal fun isImage(data: ByteArray): Boolean {
    if (data.size < 4) return false

    if (data[0] == 0x89.toByte() && data[1] == 0x50.toByte() &&
        data[2] == 0x4E.toByte() && data[3] == 0x47.toByte()
    ) {
        return true
    }

    if (data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte()) {
        return true
    }

    if (data[0] == 0x47.toByte() && data[1] == 0x49.toByte() &&
        data[2] == 0x46.toByte()
    ) {
        return true
    }

    if (data[0] == 0x42.toByte() && data[1] == 0x4D.toByte()) {
        return true
    }

    if (data.size >= 12 &&
        data[0] == 0x52.toByte() && data[1] == 0x49.toByte() &&
        data[2] == 0x46.toByte() && data[3] == 0x46.toByte() &&
        data[8] == 0x57.toByte() && data[9] == 0x45.toByte() &&
        data[10] == 0x42.toByte() && data[11] == 0x50.toByte()
    ) {
        return true
    }

    return false
}

internal fun isJson(data: ByteArray): Boolean {
    try {
        val str = String(data.take(100).toByteArray()).trim()
        return str.startsWith("{") || str.startsWith("[")
    } catch (e: Exception) {
        return false
    }
}

internal fun isText(data: ByteArray): Boolean {
    if (data.isEmpty()) return true
    var printable = 0
    for (b in data.take(100)) {
        val byte = b.toInt() and 0xFF
        if (byte in 32..126 || byte in 9..10 || byte == 13) {
            printable++
        }
    }
    return printable > (data.size.coerceAtMost(100)) * 0.85
}

internal fun resolveCallArgs(
    argNames: List<String>,
    data: ByteArray,
    headers: Map<String, String>,
    callVars: Map<String, Any?>
): List<Any?> =
    argNames.map { name ->
        when {
            name == "data" -> String(data)
            name == "headers" -> headers
            callVars.containsKey(name) -> callVars[name]
            else -> name
        }
    }

internal fun tryParseJson(str: String): Any? {
    if (str.isBlank()) return null
    return try {
        JSONObject(str)
    } catch (_: Exception) {
        try {
            org.json.JSONArray(str)
        } catch (_: Exception) {
            null
        }
    }
}

internal fun toMap(value: Any?): Map<String, Any?>? {
    if (value == null) return null
    if (value is JSONObject) {
        return value.keys().asSequence().associateWith { key -> value.opt(key) }
    }
    if (value is Map<*, *>) {
        @Suppress("UNCHECKED_CAST")
        return value as? Map<String, Any?>
    }
    return null
}
