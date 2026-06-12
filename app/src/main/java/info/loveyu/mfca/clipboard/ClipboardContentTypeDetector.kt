package info.loveyu.mfca.clipboard

private val htmlTagRegex = Regex("</[a-zA-Z][a-zA-Z0-9]*>")
private val markdownPatterns = listOf(
    Regex("^#{1,6}\\s"),
    Regex("\\*\\*.*\\*\\*"),
    Regex("\\[.+\\]\\(.+\\)"),
    Regex("^[-*+]\\s", RegexOption.MULTILINE),
    Regex("^>\\s", RegexOption.MULTILINE),
    Regex("^```"),
    Regex("^\\|.+.\\|")
)

internal fun detectContentType(content: String): String {
    val trimmed = content.trimStart()
    if (trimmed.startsWith('<')) {
        if (htmlTagRegex.containsMatchIn(trimmed) ||
            trimmed.startsWith("<!DOCTYPE", ignoreCase = true) ||
            trimmed.startsWith("<html", ignoreCase = true)
        ) {
            return "html"
        }
    }
    if (isJsonContent(trimmed)) return "json"
    var matchCount = 0
    for (pattern in markdownPatterns) {
        if (pattern.containsMatchIn(content)) matchCount++
    }
    if (matchCount >= 2) return "markdown"
    if (isYamlContent(trimmed)) return "yaml"
    return "text"
}

private fun isJsonContent(content: String): Boolean {
    val trimmed = content.trim()
    if (!trimmed.startsWith('{') && !trimmed.startsWith('[')) return false
    return try {
        if (trimmed.startsWith('{')) org.json.JSONObject(trimmed) else org.json.JSONArray(trimmed)
        true
    } catch (_: Exception) {
        false
    }
}

private fun isYamlContent(content: String): Boolean {
    val trimmed = content.trim()
    if (trimmed.startsWith('{') || trimmed.startsWith('[') || trimmed.startsWith('<')) return false
    val hasSeparator = trimmed.startsWith("---")
    val keyCount = Regex("^[a-zA-Z_][a-zA-Z0-9_.-]*:\\s", RegexOption.MULTILINE)
        .findAll(trimmed).count()
    return hasSeparator && keyCount >= 1 || keyCount >= 3
}
