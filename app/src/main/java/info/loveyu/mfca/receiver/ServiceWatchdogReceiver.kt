package info.loveyu.mfca.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import info.loveyu.mfca.service.ForwardService
import info.loveyu.mfca.util.AppStatusManager
import info.loveyu.mfca.util.LogManager

/**
 * AlarmManager 定时看门狗：每 15 分钟检查一次前台服务是否仍在运行。
 * 若进程被系统 / OEM 电池优化杀死，则自动重启。
 * Alarm 在服务启动时注册，仅在用户主动停止时取消，从而在崩溃后仍能触发重启。
 */
class ServiceWatchdogReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_WATCHDOG = "info.loveyu.mfca.action.WATCHDOG"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_WATCHDOG) return
        if (ForwardService.isServiceAlive()) {
            LogManager.logDebug("WATCHDOG", "Service is alive, no restart needed")
            return
        }
        val status = AppStatusManager.loadStatus(context)
        if (status.isRunning) {
            LogManager.logInfo("WATCHDOG", "Service not alive but was running — restarting")
            val serviceIntent = Intent(context, ForwardService::class.java)
            context.startForegroundService(serviceIntent)
        }
    }
}
