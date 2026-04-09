package info.loveyu.mfca.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogManager {
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private var isPaused = false

    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun appendLog(tag: String, message: String) {
        if (isPaused) return
        val timestamp = dateFormat.format(Date())
        val logLine = "[$timestamp] [$tag] $message"
        Log.d(tag, message)
        _logs.value = _logs.value + logLine
    }

    fun appendLog(message: String) {
        appendLog("APP", message)
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    fun pauseLogs() {
        isPaused = true
    }

    fun resumeLogs() {
        isPaused = false
    }

    fun isPaused(): Boolean = isPaused

    fun saveLogsToFile(context: Context): String? {
        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        val logDir = File(baseDir, "logs")
        if (!logDir.exists()) {
            logDir.mkdirs()
        }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val logFile = File(logDir, "log_$timestamp.txt")

        try {
            logFile.writeText(_logs.value.joinToString("\n"))
            return logFile.absolutePath
        } catch (e: Exception) {
            return null
        }
    }

    fun getLogsDir(context: Context): File {
        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        val logDir = File(baseDir, "logs")
        if (!logDir.exists()) {
            logDir.mkdirs()
        }
        return logDir
    }
}
