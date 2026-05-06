package info.loveyu.mfca.config.schema

data class SchemaError(
    val path: String,
    val message: String,
    val severity: Severity = Severity.ERROR,
) {
    enum class Severity {
        ERROR,
        WARNING,
    }

    override fun toString(): String = "[${severity.name}] $path: $message"
}

class SchemaValidationException(
    val errors: List<SchemaError>,
    message: String,
) : Exception(message)
