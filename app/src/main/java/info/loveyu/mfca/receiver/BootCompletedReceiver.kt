package info.loveyu.mfca.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import info.loveyu.mfca.service.ForwardService
import info.loveyu.mfca.util.AppStatusManager
import info.loveyu.mfca.util.LogManager

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val status = AppStatusManager.loadStatus(context)

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.USER_UNLOCKED" -> {
                // 开机自启：仅当用户开启了 autoStart 时才启动
                if (status.autoStart) {
                    LogManager.logInfo("BOOT", "Boot completed ($action), auto-starting service")
                    val serviceIntent =
                        Intent(context, ForwardService::class.java).apply {
                            this.action = ForwardService.ACTION_START
                        }
                    context.startForegroundService(serviceIntent)
                }
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                // App 更新后若服务之前处于运行状态，则自动恢复
                if (status.isRunning) {
                    LogManager.logInfo("BOOT", "Package replaced, restoring running service")
                    val serviceIntent = Intent(context, ForwardService::class.java)
                    context.startForegroundService(serviceIntent)
                }
            }
        }
    }
}
