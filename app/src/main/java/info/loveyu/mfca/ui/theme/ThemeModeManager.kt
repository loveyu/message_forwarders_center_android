package info.loveyu.mfca.ui.theme

import android.content.Context
import android.graphics.drawable.ColorDrawable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object ThemeModeManager {
    private val _themeMode = MutableStateFlow("auto")
    val themeMode: StateFlow<String> = _themeMode

    private val lightSurfaceColor = 0xFFFDFCFF.toInt()
    private val darkSurfaceColor = 0xFF1A1C1E.toInt()

    fun initialize(context: Context) {
        val prefs = context.getSharedPreferences("mfca_prefs", Context.MODE_PRIVATE)
        _themeMode.value = prefs.getString("theme_mode", "auto") ?: "auto"
    }

    fun setThemeMode(mode: String, context: Context) {
        val prefs = context.getSharedPreferences("mfca_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("theme_mode", mode).apply()
        _themeMode.value = mode
    }

    fun isDarkTheme(context: Context): Boolean {
        val nightMode = context.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK
        val systemDark = nightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES
        return when (_themeMode.value) {
            "light" -> false
            "dark" -> true
            else -> systemDark
        }
    }

    fun applyWindowBackground(activity: android.app.Activity) {
        val isDark = isDarkTheme(activity)
        val color = if (isDark) darkSurfaceColor else lightSurfaceColor
        activity.window.setBackgroundDrawable(ColorDrawable(color))
    }
}
