package info.loveyu.mfca

import android.app.Activity
import android.app.Application
import android.os.Process
import info.loveyu.mfca.link.LinkManager
import info.loveyu.mfca.service.ForwardService
import info.loveyu.mfca.util.LogManager
import info.loveyu.mfca.util.Preferences

class Application : Application() {

    private var lastActivityResumeTime = 0L

    override fun onCreate() {
        super.onCreate()
        val preferences = Preferences(this)
        LogManager.init(this, preferences)
        installCrashLogging()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (ForwardService.isServiceAlive()) {
                    LinkManager.refreshNetworkState()
                }
                // 任意 Activity 回到前台时触发 tick，确保状态最新
                ForwardService.triggerTick()
                lastActivityResumeTime = System.currentTimeMillis()
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        LogManager.logWarn("APP", "onTrimMemory(level=$level)")
        LogManager.flushAndSync()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        LogManager.logWarn("APP", "onLowMemory()")
        LogManager.flushAndSync()
    }

    private fun installCrashLogging() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            LogManager.writeExitEventSync(
                ctx = this,
                event = "uncaught-exception",
                reason = "${throwable::class.java.name}: ${throwable.message ?: "no-message"}",
                throwable = throwable,
                extras = mapOf(
                    "thread" to thread.name,
                    "serviceAlive" to ForwardService.isServiceAlive().toString(),
                    "serviceRunning" to ForwardService.isRunning.toString(),
                    "lastActivityResumeTime" to lastActivityResumeTime.toString()
                )
            )
            if (previousHandler != null) {
                previousHandler.uncaughtException(thread, throwable)
            } else {
                Process.killProcess(Process.myPid())
            }
        }
    }
}
