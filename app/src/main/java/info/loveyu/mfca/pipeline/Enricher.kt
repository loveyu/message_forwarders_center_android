package info.loveyu.mfca.pipeline

/**
 * Enricher 接口
 * 用于在 pipeline 中对消息数据进行丰富（如注入图标 URL）
 */
interface Enricher {
    val type: String

    /**
     * 对消息数据进行丰富处理
     * @param json 原始 JSON 数据
     * @param parameter enricher 参数（如 linkId）
     * @return 丰富后的 JSON 数据，失败返回 null
     */
    suspend fun enrich(json: org.json.JSONObject, parameter: String): org.json.JSONObject?
}
