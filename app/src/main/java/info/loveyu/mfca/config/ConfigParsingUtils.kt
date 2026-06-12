package info.loveyu.mfca.config

internal fun parseStringOrList(value: Any?): List<String> {
    return when (value) {
        is String -> if (value.isBlank()) emptyList() else listOf(value)
        is List<*> -> value.mapNotNull { it as? String }
        else -> emptyList()
    }
}

internal fun parseLinkRole(role: String?): LinkRole {
    return when (role?.lowercase()) {
        "consumer" -> LinkRole.consumer
        "producer" -> LinkRole.producer
        else -> LinkRole.consumer
    }
}

internal fun parseStringMap(value: Any?): Map<String, String> {
    return (value as? Map<*, *>)
        ?.entries
        ?.mapNotNull { e ->
            val k = e.key as? String ?: return@mapNotNull null
            val v = e.value?.toString() ?: return@mapNotNull null
            k to v
        }
        ?.toMap() ?: emptyMap()
}
