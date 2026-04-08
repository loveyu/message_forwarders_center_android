package info.loveyu.mfca.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
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

        @Volatile
        var isRunning = false
            private set

        @Volatile
        var receivedCount = 0
            private set

        @Volatile
        var forwardedCount = 0
            private set

        var onStatsChanged: (() -> Unit)? = null
    }

    private var httpServer: HttpServer? = null
    private lateinit var preferences: Preferences

    override fun onCreate() {
        super.onCreate()
        preferences = Preferences(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        startForeground(NOTIFICATION_ID, createNotification())
        startHttpServer()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopHttpServer()
        super.onDestroy()
    }

    private fun startHttpServer() {
        if (httpServer != null) return

        try {
            val port = preferences.port
            httpServer = HttpServer(port) { body ->
                receivedCount++
                onStatsChanged?.invoke()
                updateNotification()

                val target = preferences.forwardTarget
                if (target.isNotEmpty()) {
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
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(getString(R.string.service_notification_text))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, createNotification())
    }
}
