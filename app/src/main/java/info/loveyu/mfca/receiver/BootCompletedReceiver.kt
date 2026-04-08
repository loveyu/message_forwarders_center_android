package info.loveyu.mfca.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import info.loveyu.mfca.service.ForwardService
import info.loveyu.mfca.util.Preferences

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val preferences = Preferences(context)
            if (preferences.autoStart) {
                val serviceIntent = Intent(context, ForwardService::class.java).apply {
                    action = ForwardService.ACTION_START
                }
                context.startForegroundService(serviceIntent)
            }
        }
    }
}
