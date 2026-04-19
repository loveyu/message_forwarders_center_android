package info.loveyu.mfca.clipboard

data class ClipboardRecord(
    val id: Long = 0,
    val contentHash: String,
    val content: String,
    val contentType: String = "text",
    val pinned: Boolean = false,
    val pinnedAt: Long? = null,
    val notificationPinned: Boolean = false,
    val notificationId: Int? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
