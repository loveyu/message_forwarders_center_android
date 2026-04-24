package info.loveyu.mfca.receiver

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import info.loveyu.mfca.service.ForwardService
import info.loveyu.mfca.util.AppStatusManager
import info.loveyu.mfca.util.LogManager

/**
 * JobScheduler 定时看门狗：每 15 分钟检查一次前台服务是否仍在运行。
 *
 * 相比 AlarmManager + BroadcastReceiver 方案，JobService.onStartJob() 属于 Android 12+
 * 明确豁免的上下文，允许启动前台服务，不会抛出 ForegroundServiceStartNotAllowedException。
 *
 * 使用 setPersisted(true) 使 Job 在重启后自动恢复（需 RECEIVE_BOOT_COMPLETED 权限）。
 */
class ServiceWatchdogJob : JobService() {

    companion object {
        private const val JOB_ID = 1001
        private const val WATCHDOG_INTERVAL_MS = 15 * 60 * 1000L

        fun schedule(context: Context) {
            val scheduler =
                context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            // 幂等：若相同 JOB_ID 已在调度中，重新调用会覆盖，不会重复注册
            val jobInfo =
                JobInfo.Builder(JOB_ID, ComponentName(context, ServiceWatchdogJob::class.java))
                    .setPeriodic(WATCHDOG_INTERVAL_MS)
                    .setPersisted(true)
                    .build()
            val result = scheduler.schedule(jobInfo)
            if (result == JobScheduler.RESULT_SUCCESS) {
                LogManager.logDebug(
                    "WATCHDOG",
                    "Watchdog job scheduled (interval=${WATCHDOG_INTERVAL_MS / 1000}s)"
                )
            } else {
                LogManager.logWarn("WATCHDOG", "Failed to schedule watchdog job")
            }
        }

        fun cancel(context: Context) {
            val scheduler =
                context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            scheduler.cancel(JOB_ID)
            LogManager.logDebug("WATCHDOG", "Watchdog job cancelled (user-initiated stop)")
        }
    }

    override fun onStartJob(params: JobParameters?): Boolean {
        if (ForwardService.isServiceAlive()) {
            LogManager.logDebug("WATCHDOG", "Service is alive, no restart needed")
            return false
        }
        val status = AppStatusManager.loadStatus(this)
        if (status.isRunning) {
            LogManager.logInfo("WATCHDOG", "Service not alive but was running — restarting")
            try {
                startForegroundService(Intent(this, ForwardService::class.java))
            } catch (e: Exception) {
                LogManager.logWarn("WATCHDOG", "Failed to restart service: ${e.message}")
            }
        }
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = true
}
