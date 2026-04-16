package info.loveyu.mfca.notification

import android.content.Context
import info.loveyu.mfca.util.LogManager

/**
 * 通知历史自动清理
 * 在 ForwardService 的 tick 中定期调用
 */
object NotifyHistoryCleanup {

    fun onTick(context: Context) {
        try {
            val dbHelper = NotifyHistoryDbHelper(context)
            val deleted = dbHelper.cleanupOldRecords(NotifyHistoryDbHelper.DEFAULT_MAX_RECORDS)
            if (deleted > 0) {
                LogManager.logDebug("NOTIFY_HISTORY", "Auto cleaned up $deleted old records")
            }
        } catch (e: Exception) {
            LogManager.logError("NOTIFY_HISTORY", "Cleanup failed: ${e.message}")
        }
    }
}
