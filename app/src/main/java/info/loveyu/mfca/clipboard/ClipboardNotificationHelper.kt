package info.loveyu.mfca.clipboard

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object ClipboardNotificationHelper {
    private const val CHANNEL_ID = "clipboard_pin"
    const val NOTIFICATION_ID_BASE = 10000

    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "剪贴板置顶",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "置顶的剪贴板内容"
            setShowBadge(false)
        }
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    fun pinToNotification(
        context: Context,
        record: ClipboardRecord,
        pendingIntent: PendingIntent
    ) {
        ensureChannel(context)
        val notificationId = NOTIFICATION_ID_BASE + record.id.toInt()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(
                "剪贴板: ${record.content.take(30).replace('\n', ' ')}"
            )
            .setContentText(record.content)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(record.content)
            )
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        NotificationManagerCompat.from(context).notify(notificationId, notification)
        return
    }

    fun unpinNotification(context: Context, notificationId: Int) {
        NotificationManagerCompat.from(context).cancel(notificationId)
    }

    fun getNotificationId(recordId: Long): Int = NOTIFICATION_ID_BASE + recordId.toInt()
}
