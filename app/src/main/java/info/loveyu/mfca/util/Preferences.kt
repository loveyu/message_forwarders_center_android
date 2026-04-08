package info.loveyu.mfca.util

import android.content.Context
import android.content.SharedPreferences
import info.loveyu.mfca.constants.ApiConstants

class Preferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("mfca_prefs", Context.MODE_PRIVATE)

    var port: Int
        get() = prefs.getInt("port", ApiConstants.DEFAULT_PORT)
        set(value) = prefs.edit().putInt("port", value).apply()

    var forwardTarget: String
        get() = prefs.getString("forward_target", "") ?: ""
        set(value) = prefs.edit().putString("forward_target", value).apply()

    var autoStart: Boolean
        get() = prefs.getBoolean("auto_start", false)
        set(value) = prefs.edit().putBoolean("auto_start", value).apply()
}
