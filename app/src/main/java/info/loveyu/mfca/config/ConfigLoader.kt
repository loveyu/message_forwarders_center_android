package info.loveyu.mfca.config

import info.loveyu.mfca.config.models.AppConfig
import info.loveyu.mfca.config.schema.AppConfigSchema
import info.loveyu.mfca.config.schema.SchemaProcessor
import info.loveyu.mfca.config.schema.SchemaValidationException
import info.loveyu.mfca.util.LogManager
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.io.File

object ConfigLoader {

    private val yaml = Load(LoadSettings.builder().build())

    fun loadConfig(yamlString: String): AppConfig {
        return try {
            val data =
                yaml.loadFromString(yamlString) as? Map<String, Any>
                    ?: throw IllegalArgumentException("Invalid YAML format")

            SchemaProcessor.process(AppConfigSchema.schema, data) { warning ->
                LogManager.logWarn("ConfigSchema", warning.toString())
            }

            AppConfig(
                version = data["version"] as? String ?: "",
                plugin = parsePlugin(data["plugin"]),
                scheduler = parseScheduler(data["scheduler"]),
                geo = parseGeo(data["geo"]),
                links = parseLinks(data["links"]),
                inputs = InputConfigParser.parse(data["inputs"]),
                queues = parseQueues(data["queues"]),
                outputs = parseOutputs(data["outputs"]),
                calls = parseCalls(data["call"]),
                rules = parseRules(data["rules"]),
                deadLetter = parseDeadLetter(data["deadLetter"]),
                quickSettings = parseQuickSettings(data["quickSettings"]),
            )
        } catch (e: SchemaValidationException) {
            throw ConfigLoadException("Config schema validation failed: ${e.message}", e)
        } catch (e: Exception) {
            throw ConfigLoadException("Failed to parse config: ${e.message}", e)
        }
    }

    fun loadFromFile(path: String): AppConfig {
        val file = File(path)
        if (!file.exists()) {
            throw ConfigLoadException("Config file not found: $path")
        }
        return loadConfig(file.readText())
    }
}

class ConfigLoadException(message: String, cause: Throwable? = null) : Exception(message, cause)
