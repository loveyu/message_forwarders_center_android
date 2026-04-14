package info.loveyu.mfca

import android.app.Activity
import android.app.Application
import info.loveyu.mfca.service.ForwardService

class Application : Application() {

    private var lastActivityResumeTime = 0L

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
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
}
