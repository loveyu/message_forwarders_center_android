package info.loveyu.mfca.util

object ScreenStateTracker {
    @Volatile
    var isScreenOn: Boolean = true
        private set

    fun markScreenOn() {
        isScreenOn = true
    }

    fun markScreenOff() {
        isScreenOn = false
    }
}
