package info.loveyu.mfca.pipeline.expression

import org.json.JSONArray
import org.json.JSONObject

internal fun ExpressionEngine.extractPath(json: Any?, path: String, headers: Map<String, String>? = null): Any? {
    if (path.isEmpty() || json == null) return json
    if (path == "\$" || path == "@this") return json

    val trimmedPath = path.trim()

    if (trimmedPath == "\$raw") {
        return when (json) {
            is JSONObject -> json.toString()
            is JSONArray -> json.toString()
            is Map<*, *> -> JSONObject(json).toString()
            is List<*> -> JSONArray(json).toString()
            else -> json.toString()
        }
    }

    if (trimmedPath.startsWith("\$headers.")) {
        val headerKey = trimmedPath.substring(9)
        return getHeaderIgnoreCase(headers, headerKey)
    }

    return when (json) {
        is JSONObject -> extractFromObject(json, trimmedPath)
        is JSONArray -> extractFromArray(json, trimmedPath)
        is Map<*, *> -> extractFromMap(json, trimmedPath)
        is List<*> -> extractFromList(json, trimmedPath)
        else -> null
    }
}

internal fun ExpressionEngine.extractFromObject(json: JSONObject, path: String): Any? {
    if (path.startsWith("@")) {
        return handleModifier(json, path)
    }

    if (path.contains("[*]") || path.contains("[*].")) {
        return extractWildcard(json, path)
    }

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

internal fun ExpressionEngine.extractFromArray(json: JSONArray, path: String): Any? {
    if (path.startsWith("@")) {
        return handleModifier(json, path)
    }

    if (path == "[*]") {
        return (0 until json.length()).map { json.opt(it) }
    }

    if (path.startsWith("[*].")) {
        val subPath = path.substring(4)
        return (0 until json.length()).mapNotNull { i ->
            extractPath(json.opt(i), subPath)
        }
    }

    val arrayMatch = ARRAY_ACCESS_REGEX.find(path)
    if (arrayMatch != null) {
        val index = arrayMatch.groupValues[1].toIntOrNull() ?: return null
        val remaining = arrayMatch.groupValues[2]
        val element = json.opt(index) ?: return null
        return if (remaining.isEmpty()) element else extractPath(element, remaining)
    }

    return null
}

internal fun ExpressionEngine.extractFromMap(json: Map<*, *>, path: String): Any? {
    if (path.startsWith("@")) {
        return handleModifier(json, path)
    }

    if (path.contains("[*]") || path.contains("[*].")) {
        return extractWildcardFromMap(json, path)
    }

    val parts = parsePathParts(path)
    var current: Any? = json

    for (part in parts) {
        current = when {
            part.isArrayAccess -> {
                val obj = if (part.objectPath.isEmpty()) current else navigateMap(current, part.objectPath)
                val arr = obj as? List<*> ?: return null
                arr.getOrNull(part.arrayIndex ?: 0)
            }
            else -> {
                navigateMap(current, part.path)
            }
        }
        if (current == null) break
    }

    return current
}

internal fun ExpressionEngine.extractFromList(json: List<*>, path: String): Any? {
    if (path.startsWith("@")) {
        return handleModifier(json, path)
    }

    if (path == "[*]") {
        return json.toList()
    }

    if (path.startsWith("[*].")) {
        val subPath = path.substring(4)
        return json.mapNotNull { extractPath(it, subPath) }
    }

    val arrayMatch = ARRAY_ACCESS_REGEX.find(path)
    if (arrayMatch != null) {
        val index = arrayMatch.groupValues[1].toIntOrNull() ?: return null
        val remaining = arrayMatch.groupValues[2]
        val element = json.getOrNull(index) ?: return null
        return if (remaining.isEmpty()) element else extractPath(element, remaining)
    }

    return null
}

private fun ExpressionEngine.navigateMap(obj: Any?, path: String): Any? {
    if (path.isEmpty()) return obj
    if (obj is Map<*, *>) return obj[path]
    return null
}

internal fun ExpressionEngine.extractWildcardFromMap(json: Map<*, *>, path: String): List<Any?> {
    val result = mutableListOf<Any?>()
    val parts = path.split("[*]", limit = 2)
    if (parts.size != 2) return result

    val basePath = parts[0].trimEnd('.')
    val remaining = parts[1].trimStart('.')

    val array = if (basePath.isEmpty()) {
        null
    } else {
        extractFromMap(json, basePath) as? List<*>
    }

    if (array != null) {
        for (element in array) {
            if (remaining.isEmpty()) {
                result.add(element)
            } else {
                result.add(extractPath(element, remaining))
            }
        }
    }

    return result
}

internal fun ExpressionEngine.handleModifier(json: Any?, modifier: String): Any? {
    return when (modifier) {
        "@keys" -> {
            when (json) {
                is JSONObject -> json.keys().asSequence().toList()
                is JSONArray -> (0 until json.length()).map { it.toString() }
                is Map<*, *> -> json.keys.toList()
                is List<*> -> json.indices.map { it.toString() }
                else -> emptyList<Any>()
            }
        }
        "@values" -> {
            when (json) {
                is JSONObject -> json.keys().asSequence().map { json.opt(it) }.toList()
                is JSONArray -> (0 until json.length()).map { json.opt(it) }
                is Map<*, *> -> json.values.toList()
                is List<*> -> json.toList()
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

internal fun ExpressionEngine.extractWildcard(json: JSONObject, path: String): List<Any?> {
    val result = mutableListOf<Any?>()
    val parts = path.split("[*]", limit = 2)
    if (parts.size != 2) return result

    val basePath = parts[0].trimEnd('.')
    val remaining = parts[1].trimStart('.')

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

internal fun parsePathParts(path: String): List<PathPart> {
    val parts = mutableListOf<PathPart>()
    var remaining = path

    while (remaining.isNotEmpty()) {
        val arrayMatch = PATH_PART_REGEX.find(remaining)
        if (arrayMatch != null) {
            val objectPath = arrayMatch.groupValues[1]
            val arrayIndex = arrayMatch.groupValues[2].toIntOrNull()
            val after = remaining.substring(arrayMatch.range.last + 1)

            parts.add(
                PathPart(
                    path = objectPath,
                    isArrayAccess = true,
                    arrayIndex = arrayIndex,
                    objectPath = objectPath
                )
            )

            remaining = after.trimStart('.')
        } else {
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

private fun ExpressionEngine.navigateObject(obj: Any?, path: String): Any? {
    if (path.isEmpty()) return obj
    if (obj !is JSONObject) return null
    return obj.opt(path)
}

internal fun ExpressionEngine.extractFromAny(value: Any?, path: String): Any? {
    if (value == null || path.isBlank()) return value
    val json =
        when (value) {
            is JSONObject -> value
            is Map<*, *> ->
                try {
                    JSONObject(value as Map<*, *>)
                } catch (_: Exception) {
                    null
                }
            is String ->
                try {
                    JSONObject(value)
                } catch (_: Exception) {
                    null
                }
            else -> null
        }
    return if (json != null) extractPath(json, path, emptyMap()) else null
}

internal fun ExpressionEngine.extractFromAnyValue(value: Any?, subPath: String): Any? {
    return when (value) {
        is JSONObject -> if (subPath.isBlank()) value else extractPath(value, subPath)
        is Map<*, *> -> {
            val obj = try { JSONObject(value as Map<*, *>) } catch (_: Exception) { null }
            if (obj != null) if (subPath.isBlank()) obj else extractPath(obj, subPath) else value
        }
        else -> value
    }
}

internal fun ExpressionEngine.extract(json: Any?, path: String): Any? {
    return extractPath(json, path)
}

internal fun ExpressionEngine.extractAndTransform(json: Any?, path: String): ByteArray? {
    val extracted = extractPath(json, path) ?: return null

    return when (extracted) {
        is String -> extracted.toByteArray()
        is Number -> extracted.toString().toByteArray()
        is Boolean -> extracted.toString().toByteArray()
        is JSONArray -> extracted.toString().toByteArray()
        is JSONObject -> extracted.toString().toByteArray()
        is Map<*, *> -> JSONObject(extracted).toString().toByteArray()
        is List<*> -> JSONArray(extracted).toString().toByteArray()
        else -> extracted.toString().toByteArray()
    }
}
