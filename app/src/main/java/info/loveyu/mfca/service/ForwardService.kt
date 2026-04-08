package info.loveyu.mfca.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.util.Log
import info.loveyu.mfca.MainActivity
import info.loveyu.mfca.R
import info.loveyu.mfca.server.HttpServer
import info.loveyu.mfca.server.MessageForwarder
import info.loveyu.mfca.util.Preferences
import java.io.IOException

class ForwardService : Service() {

    companion object {
        private const val TAG = "ForwardService"
        const val CHANNEL_ID = "forward_service_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "info.loveyu.mfca.action.START"
        const val ACTION_STOP = "info.loveyu.mfca.action.STOP"
        const val ACTION_TOGGLE_RECEIVE = "info.loveyu.mfca.action.TOGGLE_RECEIVE"
        const val ACTION_TOGGLE_FORWARD = "info.loveyu.mfca.action.TOGGLE_FORWARD"

        @Volatile
        var isRunning = false
            private set

        @Volatile
        var receivedCount = 0
            private set

        @Volatile
        var forwardedCount = 0
            private set

        @Volatile
        var isReceivingEnabled = true
            private set

        @Volatile
        var isForwardingEnabled = true
            private set

        var onStatsChanged: (() -> Unit)? = null

        private var serviceInstance: ForwardService? = null

        fun refreshNotification() {
            serviceInstance?.let { service ->
                val notification = service.createNotification()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    service.startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                } else {
                    service.startForeground(NOTIFICATION_ID, notification)
                }
            }
        }
    }

    private var httpServer: HttpServer? = null
    private lateinit var preferences: Preferences

    override fun onCreate() {
        super.onCreate()
        serviceInstance = this
        preferences = Preferences(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_RECEIVE -> {
                isReceivingEnabled = !isReceivingEnabled
                preferences.receivingEnabled = isReceivingEnabled
                updateNotification()
                onStatsChanged?.invoke()
                return START_STICKY
            }
            ACTION_TOGGLE_FORWARD -> {
                isForwardingEnabled = !isForwardingEnabled
                preferences.forwardingEnabled = isForwardingEnabled
                updateNotification()
                onStatsChanged?.invoke()
                return START_STICKY
            }
        }

        isReceivingEnabled = preferences.receivingEnabled
        isForwardingEnabled = preferences.forwardingEnabled

        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        startHttpServer()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceInstance = null
        stopHttpServer()
        super.onDestroy()
    }

    private fun startHttpServer() {
        if (httpServer != null) return

        try {
            val port = preferences.port
            httpServer = HttpServer(port) { body ->
                if (!isReceivingEnabled) return@HttpServer

                receivedCount++
                onStatsChanged?.invoke()
                updateNotification()

                val target = preferences.forwardTarget
                if (target.isNotEmpty() && isForwardingEnabled) {
                    MessageForwarder.forward(target, body) { success ->
                        if (success) {
                            forwardedCount++
                            onStatsChanged?.invoke()
                            updateNotification()
                        }
                    }
                }
            }
            httpServer?.startServer()
            isRunning = true
            Log.i(TAG, "Forward service started on port $port")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to start HTTP server", e)
            stopSelf()
        }
    }

    private fun stopHttpServer() {
        httpServer?.stopServer()
        httpServer = null
        isRunning = false
        receivedCount = 0
        forwardedCount = 0
        Log.i(TAG, "Forward service stopped")
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.service_notification_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val contentIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val receiveStatus = if (isReceivingEnabled) getString(R.string.notification_receive_on)
        else getString(R.string.notification_receive_off)
        val forwardStatus = if (isForwardingEnabled) getString(R.string.notification_forward_on)
        else getString(R.string.notification_forward_off)
        val statusText = "$receiveStatus · $forwardStatus"

        // Toggle receive action
        val receiveIntent = Intent(this, ForwardService::class.java).apply {
            action = ACTION_TOGGLE_RECEIVE
        }
        val receivePendingIntent = PendingIntent.getService(
            this, 1, receiveIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val receiveTitle = if (isReceivingEnabled) getString(R.string.notification_action_stop_receive)
        else getString(R.string.notification_action_resume_receive)

        // Toggle forward action
        val forwardIntent = Intent(this, ForwardService::class.java).apply {
            action = ACTION_TOGGLE_FORWARD
        }
        val forwardPendingIntent = PendingIntent.getService(
            this, 2, forwardIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val forwardTitle = if (isForwardingEnabled) getString(R.string.notification_action_stop_forward)
        else getString(R.string.notification_action_resume_forward)

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_notification_receive),
                receiveTitle,
                receivePendingIntent
            ).build())
            .addAction(Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_notification_forward),
                forwardTitle,
                forwardPendingIntent
            ).build())
            .build()
    }

    private fun updateNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, createNotification())
    }
}
