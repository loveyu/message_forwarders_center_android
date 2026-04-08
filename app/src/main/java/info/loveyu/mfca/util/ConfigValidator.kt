package info.loveyu.mfca.util

import org.json.JSONObject

object ConfigValidator {

    data class ValidationResult(
        val isValid: Boolean,
        val errorMessage: String? = null
    )

    fun validate(jsonString: String): ValidationResult {
        return try {
            val json = JSONObject(jsonString)

            if (!json.has("port")) {
                return ValidationResult(false, "Missing required field: port")
            }

            if (!json.has("forwardTarget")) {
                return ValidationResult(false, "Missing required field: forwardTarget")
            }

            val port = json.getInt("port")
            if (port < 1 || port > 65535) {
                return ValidationResult(false, "Invalid port number: $port")
            }

            ValidationResult(true)
        } catch (e: Exception) {
            ValidationResult(false, "Invalid JSON format: ${e.message}")
        }
    }

    fun parseConfig(jsonString: String): ConfigData? {
        return try {
            val json = JSONObject(jsonString)
            ConfigData(
                port = json.getInt("port"),
                forwardTarget = json.optString("forwardTarget", ""),
                autoStart = json.optBoolean("autoStart", false),
                receivingEnabled = json.optBoolean("receivingEnabled", true),
                forwardingEnabled = json.optBoolean("forwardingEnabled", true)
            )
        } catch (e: Exception) {
            null
        }
    }

    data class ConfigData(
        val port: Int,
        val forwardTarget: String,
        val autoStart: Boolean,
        val receivingEnabled: Boolean,
        val forwardingEnabled: Boolean
    )
}
