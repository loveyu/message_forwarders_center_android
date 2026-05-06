package info.loveyu.mfca.config.schema

object SchemaProcessor {

    /**
     * Normalizes and validates [rawData] against [schema].
     *
     * [onWarning] is called for each deprecation warning or non-fatal issue. Throws
     * [SchemaValidationException] if any errors are found.
     *
     * Returns the normalized data map.
     */
    fun process(
        schema: ObjectNodeDef,
        rawData: Map<String, Any>?,
        onWarning: (SchemaError) -> Unit = {},
    ): Map<String, Any> {
        val normalized = SchemaNormalizer.normalize(schema, rawData)
        val allDiagnostics = SchemaValidator.validate(schema, normalized)

        allDiagnostics
            .filter { it.severity == SchemaError.Severity.WARNING }
            .forEach { onWarning(it) }

        val errors = allDiagnostics.filter { it.severity == SchemaError.Severity.ERROR }
        if (errors.isNotEmpty()) {
            val message = buildString {
                appendLine("Config schema validation failed with ${errors.size} error(s):")
                errors.forEach { appendLine("  - $it") }
            }
            throw SchemaValidationException(errors, message.trimEnd())
        }

        return normalized
    }
}
