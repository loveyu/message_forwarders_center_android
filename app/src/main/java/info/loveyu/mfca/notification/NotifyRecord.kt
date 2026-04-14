package info.loveyu.mfca.notification

/**
 * 通知历史记录数据类
 */
data class NotifyRecord(
    val id: Long = 0,
    val notifyId: Int,
    val title: String,
    val content: String,
    val rawData: String?,
    val outputName: String,
    val channel: String,
    val tag: String?,
    val group: String?,
    val iconUrl: String?,
    val sourceRule: String?,
    val sourceInput: String?,
    val popup: Boolean = false,
    val persistent: Boolean = false,
    val createdAt: Long
)
