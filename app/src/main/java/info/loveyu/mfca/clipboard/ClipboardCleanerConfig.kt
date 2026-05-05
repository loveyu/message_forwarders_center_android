package info.loveyu.mfca.clipboard

import android.content.Context
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.io.File

object ClipboardCleanerConfig {

    private val defaultPasswordPatterns = listOf(
        "(?i)(password|passwd|pwd|密码)\\s*[=：:]\\s*\\S+".toRegex(),
        "(?i)(password|passwd|pwd|密码)\\s+is\\s+\\S+".toRegex(),
        "(?i)密码[是为：:\\s]+\\S+".toRegex()
    )

    private val defaultVerificationCodePatterns = listOf(
        "验证码[是为：:\\s]+\\d{4,8}".toRegex(),
        "(?i)(verification|verify|code|验证码)[\\s:：]+\\d{4,8}".toRegex(),
        "^\\d{4,8}$".toRegex()
    )

    fun loadPasswordPatterns(context: Context): List<Regex> {
        return loadPatterns(context, "passwordPatterns") { defaultPasswordPatterns }
    }

    fun loadVerificationCodePatterns(context: Context): List<Regex> {
        return loadPatterns(context, "verificationCodePatterns") { defaultVerificationCodePatterns }
    }

    @Suppress("UNCHECKED_CAST")
    private fun loadPatterns(
        context: Context,
        field: String,
        defaults: () -> List<Regex>
    ): List<Regex> {
        val file = File(context.filesDir, "clipboard_cleaner.yaml")
        if (!file.exists()) return defaults()
        return try {
            val yaml = Load(LoadSettings.builder().build())
            val data = yaml.loadFromString(file.readText()) as? Map<String, Any>
                ?: return defaults()
            val rawList = data[field] as? List<String> ?: return defaults()
            if (rawList.isEmpty()) defaults()
            else rawList.mapNotNull { runCatching { it.toRegex() }.getOrNull() }
        } catch (_: Exception) {
            defaults()
        }
    }
}
