package info.loveyu.mfca.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import info.loveyu.mfca.ui.main.MainActivity
import info.loveyu.mfca.R
import info.loveyu.mfca.service.buildNotificationText
import info.loveyu.mfca.ui.floating.InputMethodFloatingActivity
import info.loveyu.mfca.ui.floating.StatusFloatingActivity

internal class ForwardServiceNotificationDelegate(
    private val service: ForwardService
) {
    private var lastNotificationStats: String? = null

    fun invalidateStatsCache() {
        lastNotificationStats = null
    }

    fun createNotificationChannels() {
        val manager = service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val channel = NotificationChannel(
            ForwardService.CHANNEL_ID,
            service.getString(R.string.service_notification_channel),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "前台服务状态通知"
            enableVibration(false)
            setSound(null, null)
            setLockscreenVisibility(Notification.VISIBILITY_PUBLIC)
        }
        manager.createNotificationChannel(channel)

        val linkErrorChannel = NotificationChannel(
            ForwardService.LINK_ERROR_CHANNEL_ID,
            service.getString(R.string.link_error_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            group = ForwardService.LINK_ERROR_GROUP_ID
            setLockscreenVisibility(Notification.VISIBILITY_PUBLIC)
        }
        manager.createNotificationChannelGroup(
            NotificationChannelGroup(
                ForwardService.LINK_ERROR_GROUP_ID,
                service.getString(R.string.link_error_channel_name)
            )
        )
        manager.createNotificationChannel(linkErrorChannel)
    }

    fun createNotification(): Notification {
        val contentIntent = Intent(service, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            service, 0, contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = Notification.Builder(service, ForwardService.CHANNEL_ID)
            .setContentTitle(buildStatsText())
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)

        if (ForwardService.isRunning) {
            val statusIntent = Intent(service, StatusFloatingActivity::class.java)
            val statusPendingIntent = PendingIntent.getActivity(
                service, 1, statusIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(service, R.drawable.ic_notification_receive),
                    service.getString(R.string.notification_action_status),
                    statusPendingIntent
                ).build()
            )
        }

        if (ForwardService.currentConfig?.quickSettings?.inputMethodSwitcher != false) {
            val inputMethodIntent = Intent(service, InputMethodFloatingActivity::class.java)
            val inputMethodPendingIntent = PendingIntent.getActivity(
                service, 3, inputMethodIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(service, R.drawable.ic_tile_input_method),
                    service.getString(R.string.notification_action_input_method),
                    inputMethodPendingIntent
                ).build()
            )
        }

        return builder.build()
    }

    fun updateNotification() {
        val statsText = buildStatsText()
        val manager = service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val isNotificationPresent = manager.activeNotifications.any { statusBarNotification ->
            statusBarNotification.id == ForwardService.NOTIFICATION_ID && statusBarNotification.tag == null
        }
        if (statsText == lastNotificationStats && isNotificationPresent) return
        lastNotificationStats = statsText
        manager.notify(ForwardService.NOTIFICATION_ID, createNotification())
    }

    private fun buildStatsText(): String {
        return ForwardService.buildNotificationText()
    }
}
