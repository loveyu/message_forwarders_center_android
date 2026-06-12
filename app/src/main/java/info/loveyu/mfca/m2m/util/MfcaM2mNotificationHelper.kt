package info.loveyu.mfca.m2m.util

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import info.loveyu.mfca.MainActivity
import info.loveyu.mfca.m2m.service.MfcaM2mService
import info.loveyu.mfca.service.ForwardService
import info.loveyu.mfca.service.buildNotificationText

internal object MfcaM2mNotificationHelper {

    fun buildNotification(context: Context): Notification {
        val contentIntent = PendingIntent.getActivity(
            context,
            ForwardService.NOTIFICATION_ID,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(context, ForwardService.CHANNEL_ID)
            .setContentTitle(ForwardService.buildNotificationText())
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(contentIntent)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
    }

    fun updateNotification(service: MfcaM2mService) {
        ForwardService.refreshNotification()
        if (!ForwardService.isServiceAlive()) {
            val manager = service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(ForwardService.NOTIFICATION_ID, buildNotification(service))
        }
    }

    fun startVpnForeground(service: MfcaM2mService) {
        val notification = buildNotification(service)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            service.startForeground(
                ForwardService.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            service.startForeground(ForwardService.NOTIFICATION_ID, notification)
        }
        ForwardService.refreshNotification()
    }
}
