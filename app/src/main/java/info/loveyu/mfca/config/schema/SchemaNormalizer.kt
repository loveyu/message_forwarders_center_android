package info.loveyu.mfca.config.schema

object SchemaNormalizer {

    fun normalize(schema: ObjectNodeDef, data: Map<String, Any>?): Map<String, Any> {
        if (data == null) return emptyMap()
        return normalizeObject(schema, data)
    }

    private fun normalizeObject(
        schema: ObjectNodeDef,
        data: Map<String, Any>,
    ): Map<String, Any> {
        val result = data.toMutableMap()
        for (child in schema.children) {
            val value = result[child.name] ?: continue
            result[child.name] = normalizeValue(child, value)
        }
        return result
    }

    private fun normalizeValue(schema: NodeDef, value: Any): Any {
        return when (schema) {
            is ObjectNodeDef -> normalizeObjectValue(schema, value)
            is ListNodeDef -> normalizeListValue(schema, value)
            is MapNodeDef -> normalizeMapValue(schema, value)
            else -> value
        }
    }

    private fun normalizeObjectValue(schema: ObjectNodeDef, value: Any): Any {
        return when {
            value is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                normalizeObject(schema, value as Map<String, Any>)
            }
            schema.scalarKey != null -> {
                normalizeObject(schema, mapOf(schema.scalarKey to value))
            }
            else -> value
        }
    }

    private fun normalizeListValue(schema: ListNodeDef, value: Any): Any {
        val list = value as? List<*> ?: return value
        return list.mapNotNull { item ->
            if (item != null) normalizeValue(schema.itemSchema, item) else null
        }
    }

    private fun normalizeMapValue(schema: MapNodeDef, value: Any): Any {
        val map = value as? Map<*, *> ?: return value
        return buildMap {
            map.forEach { (k, v) ->
                if (k != null && v != null) {
                    put(k.toString(), normalizeValue(schema.valueSchema, v))
                }
            }
        }
    }
}
