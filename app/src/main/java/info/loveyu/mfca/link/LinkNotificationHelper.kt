package info.loveyu.mfca.link

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import info.loveyu.mfca.ui.main.MainActivity
import info.loveyu.mfca.R
import info.loveyu.mfca.service.ForwardService
import info.loveyu.mfca.util.LogManager

internal const val LINK_ERROR_NOTIFICATION_BASE = 2000

internal object LinkNotificationHelper {
    fun showLinkError(ctx: Context, linkId: String, notifiedErrorLinks: MutableSet<String>) {
        synchronized(notifiedErrorLinks) {
            if (linkId in notifiedErrorLinks) return
            notifiedErrorLinks.add(linkId)
        }

        val notificationId = LINK_ERROR_NOTIFICATION_BASE + Math.abs(linkId.hashCode() % 1000)
        val contentIntent = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = android.app.Notification.Builder(ctx, ForwardService.LINK_ERROR_CHANNEL_ID)
            .setContentTitle(ctx.getString(R.string.link_error_title))
            .setContentText(linkId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setVisibility(android.app.Notification.VISIBILITY_PUBLIC)
            .build()

        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify("link_error_$linkId", notificationId, notification)
        LogManager.logDebug("LINK", "Posted error notification for $linkId")
    }

    fun showLinkRecovered(ctx: Context, linkId: String, notifiedErrorLinks: MutableSet<String>) {
        synchronized(notifiedErrorLinks) {
            if (linkId !in notifiedErrorLinks) return
            notifiedErrorLinks.remove(linkId)
        }

        val notificationId = LINK_ERROR_NOTIFICATION_BASE + Math.abs(linkId.hashCode() % 1000)
        val contentIntent = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = android.app.Notification.Builder(ctx, ForwardService.LINK_ERROR_CHANNEL_ID)
            .setContentTitle(ctx.getString(R.string.link_recovered_title))
            .setContentText(linkId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(contentIntent)
            .setOngoing(false)
            .setTimeoutAfter(5_000L)
            .setVisibility(android.app.Notification.VISIBILITY_PUBLIC)
            .build()

        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify("link_error_$linkId", notificationId, notification)
        LogManager.logDebug("LINK", "Posted recovered notification for $linkId")
    }

    fun cancelAll(ctx: Context, notifiedErrorLinks: MutableSet<String>) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        synchronized(notifiedErrorLinks) {
            notifiedErrorLinks.forEach { linkId ->
                val notificationId = LINK_ERROR_NOTIFICATION_BASE + Math.abs(linkId.hashCode() % 1000)
                nm.cancel("link_error_$linkId", notificationId)
            }
            notifiedErrorLinks.clear()
        }
    }
}
