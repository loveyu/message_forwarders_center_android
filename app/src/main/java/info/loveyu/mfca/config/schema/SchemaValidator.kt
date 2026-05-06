package info.loveyu.mfca.config.schema

import info.loveyu.mfca.config.Duration

object SchemaValidator {

    private val DURATION_PATTERN = Regex("""^\d+(ms|s|m|h|d)$""")

    fun validate(schema: ObjectNodeDef, data: Map<String, Any>?): List<SchemaError> {
        val errors = mutableListOf<SchemaError>()
        validateObject(schema, data, "<root>", errors)
        return errors
    }

    private fun validateObject(
        schema: ObjectNodeDef,
        data: Map<String, Any>?,
        path: String,
        errors: MutableList<SchemaError>,
    ) {
        if (data == null) {
            if (schema.isRequired) {
                errors += SchemaError(path, "Required object is missing")
            }
            return
        }

        for (child in schema.children) {
            val fieldPath = if (path == "<root>") child.name else "${path}.${child.name}"
            val value = data[child.name]

            child.deprecated?.let { dep ->
                if (value != null) {
                    val msg = buildString {
                        append("'${child.name}' is deprecated")
                        dep.since?.let { append(" since $it") }
                        dep.message?.let { append(". $it") }
                        dep.replacement?.let { append(". Use '${dep.replacement}' instead") }
                    }
                    errors += SchemaError(fieldPath, msg, SchemaError.Severity.WARNING)
                }
            }

            validateNode(child, value, fieldPath, errors)
        }

        if (!schema.allowExtra) {
            val knownKeys = schema.children.map { it.name }.toSet()
            for (key in data.keys) {
                if (key !in knownKeys) {
                    val fieldPath = if (path == "<root>") key else "$path.$key"
                    errors +=
                        SchemaError(fieldPath, "Unknown field '$key'", SchemaError.Severity.WARNING)
                }
            }
        }
    }

    private fun validateNode(
        schema: NodeDef,
        value: Any?,
        path: String,
        errors: MutableList<SchemaError>,
    ) {
        when (schema) {
            is ObjectNodeDef -> validateObjectNode(schema, value, path, errors)
            is StringNodeDef -> validateString(schema, value, path, errors)
            is IntNodeDef -> validateInt(schema, value, path, errors)
            is LongNodeDef -> validateLong(schema, value, path, errors)
            is DoubleNodeDef -> validateDouble(schema, value, path, errors)
            is BooleanNodeDef -> validateBoolean(schema, value, path, errors)
            is DurationNodeDef -> validateDuration(schema, value, path, errors)
            is EnumNodeDef -> validateEnum(schema, value, path, errors)
            is ListNodeDef -> validateList(schema, value, path, errors)
            is MapNodeDef -> validateMap(schema, value, path, errors)
            is AnyNodeDef -> {
                if (value == null && schema.isRequired) {
                    errors += SchemaError(path, "Required field is missing")
                }
            }
        }
    }

    private fun validateObjectNode(
        schema: ObjectNodeDef,
        value: Any?,
        path: String,
        errors: MutableList<SchemaError>,
    ) {
        if (value == null) {
            if (schema.isRequired) {
                errors += SchemaError(path, "Required field is missing")
            }
            return
        }
        val map = value as? Map<*, *>
        if (map == null) {
            errors += SchemaError(path, "Expected object but got ${typeName(value)}")
            return
        }
        @Suppress("UNCHECKED_CAST")
        validateObject(schema, map as Map<String, Any>, path, errors)
    }

    private fun validateString(
        schema: StringNodeDef,
        value: Any?,
        path: String,
        errors: MutableList<SchemaError>,
    ) {
        if (value == null) {
            if (schema.isRequired && schema.default == null) {
                errors += SchemaError(path, "Required string field is missing")
            }
            return
        }
        val str = value as? String
        if (str == null) {
            errors += SchemaError(path, "Expected string but got ${typeName(value)}")
            return
        }
        schema.minLength?.let {
            if (str.length < it) {
                errors += SchemaError(path, "String length ${str.length} is below minimum $it")
            }
        }
        schema.maxLength?.let {
            if (str.length > it) {
                errors += SchemaError(path, "String length ${str.length} exceeds maximum $it")
            }
        }
        schema.pattern?.let {
            if (!it.containsMatchIn(str)) {
                errors += SchemaError(path, "Value does not match pattern ${it.pattern}")
            }
        }
        schema.allowedValues?.let {
            if (str !in it) {
                errors +=
                    SchemaError(path, "Value '$str' is not one of: ${it.joinToString(", ")}")
            }
        }
    }

    private fun validateInt(
        schema: IntNodeDef,
        value: Any?,
        path: String,
        errors: MutableList<SchemaError>,
    ) {
        if (value == null) {
            if (schema.isRequired && schema.default == null) {
                errors += SchemaError(path, "Required int field is missing")
            }
            return
        }
        val num = value as? Number
        if (num == null) {
            errors += SchemaError(path, "Expected int but got ${typeName(value)}")
            return
        }
        val intVal = num.toInt()
        schema.min?.let {
            if (intVal < it) errors += SchemaError(path, "Value $intVal is below minimum $it")
        }
        schema.max?.let {
            if (intVal > it) errors += SchemaError(path, "Value $intVal exceeds maximum $it")
        }
    }

    private fun validateLong(
        schema: LongNodeDef,
        value: Any?,
        path: String,
        errors: MutableList<SchemaError>,
    ) {
        if (value == null) {
            if (schema.isRequired && schema.default == null) {
                errors += SchemaError(path, "Required long field is missing")
            }
            return
        }
        val num = value as? Number
        if (num == null) {
            errors += SchemaError(path, "Expected long but got ${typeName(value)}")
            return
        }
        val longVal = num.toLong()
        schema.min?.let {
            if (longVal < it) errors += SchemaError(path, "Value $longVal is below minimum $it")
        }
        schema.max?.let {
            if (longVal > it) errors += SchemaError(path, "Value $longVal exceeds maximum $it")
        }
    }

    private fun validateDouble(
        schema: DoubleNodeDef,
        value: Any?,
        path: String,
        errors: MutableList<SchemaError>,
    ) {
        if (value == null) {
            if (schema.isRequired && schema.default == null) {
                errors += SchemaError(path, "Required double field is missing")
            }
            return
        }
        val num = value as? Number
        if (num == null) {
            errors += SchemaError(path, "Expected double but got ${typeName(value)}")
            return
        }
        val doubleVal = num.toDouble()
        schema.min?.let {
            if (doubleVal < it)
                errors += SchemaError(path, "Value $doubleVal is below minimum $it")
        }
        schema.max?.let {
            if (doubleVal > it)
                errors += SchemaError(path, "Value $doubleVal exceeds maximum $it")
        }
    }

    private fun validateBoolean(
        schema: BooleanNodeDef,
        value: Any?,
        path: String,
        errors: MutableList<SchemaError>,
    ) {
        if (value == null) {
            if (schema.isRequired && schema.default == null) {
                errors += SchemaError(path, "Required boolean field is missing")
            }
            return
        }
        if (value !is Boolean) {
            errors += SchemaError(path, "Expected boolean but got ${typeName(value)}")
        }
    }

    private fun validateDuration(
        schema: DurationNodeDef,
        value: Any?,
        path: String,
        errors: MutableList<SchemaError>,
    ) {
        if (value == null) {
            if (schema.isRequired && schema.default == null) {
                errors += SchemaError(path, "Required duration field is missing")
            }
            return
        }
        val str = value as? String
        if (str == null) {
            errors +=
                SchemaError(
                    path,
                    "Expected duration string (e.g. '10s', '5m', '2h') but got ${typeName(value)}",
                )
            return
        }
        if (!DURATION_PATTERN.matches(str)) {
            errors +=
                SchemaError(
                    path,
                    "Invalid duration format '$str'. Expected format like '10s', '5m', '2h', '1d', '500ms'",
                )
            return
        }
        val millis = Duration(str).millis
        schema.minMillis?.let {
            if (millis < it) errors += SchemaError(path, "Duration '$str' is below minimum ${it}ms")
        }
        schema.maxMillis?.let {
            if (millis > it)
                errors += SchemaError(path, "Duration '$str' exceeds maximum ${it}ms")
        }
    }

    private fun validateEnum(
        schema: EnumNodeDef,
        value: Any?,
        path: String,
        errors: MutableList<SchemaError>,
    ) {
        if (value == null) {
            if (schema.isRequired && schema.default == null) {
                errors += SchemaError(path, "Required enum field is missing")
            }
            return
        }
        val str = value.toString()
        if (str !in schema.values) {
            errors +=
                SchemaError(
                    path,
                    "Invalid value '$str'. Must be one of: ${schema.values.joinToString(", ")}",
                )
        }
    }

    private fun validateList(
        schema: ListNodeDef,
        value: Any?,
        path: String,
        errors: MutableList<SchemaError>,
    ) {
        if (value == null) {
            if (schema.isRequired) {
                errors += SchemaError(path, "Required list field is missing")
            }
            return
        }
        val list = value as? List<*>
        if (list == null) {
            errors += SchemaError(path, "Expected list but got ${typeName(value)}")
            return
        }
        schema.minSize?.let {
            if (list.size < it)
                errors += SchemaError(path, "List size ${list.size} is below minimum $it")
        }
        schema.maxSize?.let {
            if (list.size > it)
                errors += SchemaError(path, "List size ${list.size} exceeds maximum $it")
        }
        list.forEachIndexed { index, item ->
            validateNode(schema.itemSchema, item, "$path[$index]", errors)
        }
    }

    private fun validateMap(
        schema: MapNodeDef,
        value: Any?,
        path: String,
        errors: MutableList<SchemaError>,
    ) {
        if (value == null) {
            if (schema.isRequired) {
                errors += SchemaError(path, "Required map field is missing")
            }
            return
        }
        val map = value as? Map<*, *>
        if (map == null) {
            errors += SchemaError(path, "Expected map but got ${typeName(value)}")
            return
        }
        map.forEach { (key, item) ->
            validateNode(schema.valueSchema, item, "$path.$key", errors)
        }
    }

    private fun typeName(value: Any): String = value::class.simpleName ?: "unknown"
}
