package info.loveyu.mfca.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LogLevel(val androidPriority: Int, val tag: String) {
    DEBUG(Log.DEBUG, "D"),
    INFO(Log.INFO, "I"),
    WARN(Log.WARN, "W"),
    ERROR(Log.ERROR, "E")
}

object LogManager {
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private var isPaused = false
    private var currentLogLevel = LogLevel.INFO
    private var isFileLoggingEnabled = false
    private var isAllLogcatEnabled = false
    private var maxLogLines = 1000

    private var logFile: File? = null
    private var logFileWriter: OutputStreamWriter? = null
    private var contextRef: WeakReference<Context>? = null

    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val fileDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    fun init(ctx: Context, prefs: Preferences) {
        contextRef = WeakReference(ctx.applicationContext)
        loadSettings(prefs)
    }

    fun loadSettings(prefs: Preferences) {
        currentLogLevel = LogLevel.entries.find { it.name == prefs.logLevel } ?: LogLevel.INFO
        isFileLoggingEnabled = prefs.logToFile
        isAllLogcatEnabled = prefs.logToLogcatAll
        maxLogLines = prefs.maxLogLines
        if (isFileLoggingEnabled) {
            startFileLogging()
        }
    }

    fun log(level: LogLevel, tag: String, message: String, context: Any? = null) {
        if (isPaused) return
        if (level.androidPriority < currentLogLevel.androidPriority) return

        val timestamp = dateFormat.format(Date())
        val contextJson = context?.let {
            try {
                JSONObject().put("context", it)
            } catch (e: Exception) {
                null
            }
        }
        val logLine = if (contextJson != null) {
            "[$timestamp] [${level.tag}:$tag] $message $contextJson"
        } else {
            "[$timestamp] [${level.tag}:$tag] $message"
        }

        // Write to logcat: WARN+ always, DEBUG/INFO based on isAllLogcatEnabled
        when {
            isAllLogcatEnabled -> Log.d(tag, message)
            level == LogLevel.ERROR -> Log.e(tag, message)
            level == LogLevel.WARN -> Log.w(tag, message)
            level == LogLevel.INFO -> Log.i(tag, message)
            level == LogLevel.DEBUG -> Log.d(tag, message)
        }

        // Write to UI log
        _logs.value = (_logs.value + logLine).takeLast(maxLogLines)

        // Write to file if enabled
        if (isFileLoggingEnabled) {
            writeToFile(logLine)
        }
    }

    fun log(tag: String, message: String) {
        log(LogLevel.INFO, tag, message)
    }

    fun logDebug(tag: String, message: String) = log(LogLevel.DEBUG, tag, message)
    fun logInfo(tag: String, message: String) = log(LogLevel.INFO, tag, message)
    fun logWarn(tag: String, message: String) = log(LogLevel.WARN, tag, message)
    fun logError(tag: String, message: String) = log(LogLevel.ERROR, tag, message)

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

    fun isFileLoggingEnabled(): Boolean = isFileLoggingEnabled

    fun setFileLoggingEnabled(enabled: Boolean, prefs: Preferences) {
        isFileLoggingEnabled = enabled
        prefs.logToFile = enabled
        if (enabled) {
            startFileLogging()
        } else {
            stopFileLogging()
        }
    }

    fun setLogLevel(level: LogLevel, prefs: Preferences) {
        currentLogLevel = level
        prefs.logLevel = level.name
    }

    fun showToast(message: String) {
        val ctx = contextRef?.get() ?: return
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(ctx, message, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    fun getLogLevel(): LogLevel = currentLogLevel

    fun setAllLogcatEnabled(enabled: Boolean, prefs: Preferences) {
        isAllLogcatEnabled = enabled
        prefs.logToLogcatAll = enabled
    }

    fun isAllLogcatEnabled(): Boolean = isAllLogcatEnabled

    private fun startFileLogging() {
        val ctx = contextRef?.get() ?: return
        val baseDir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val logDir = File(baseDir, "logs")
        if (!logDir.exists()) {
            logDir.mkdirs()
        }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        logFile = File(logDir, "log_$timestamp.txt")

        try {
            logFileWriter = OutputStreamWriter(FileOutputStream(logFile, true), Charsets.UTF_8)
            // Write existing logs to file
            _logs.value.forEach { line ->
                logFileWriter?.write(line)
                logFileWriter?.write("\n")
            }
            logFileWriter?.flush()
        } catch (e: Exception) {
            Log.e("LogManager", "Failed to start file logging", e)
        }
    }

    private fun stopFileLogging() {
        try {
            logFileWriter?.close()
        } catch (e: Exception) {
            Log.e("LogManager", "Failed to stop file logging", e)
        }
        logFileWriter = null
        logFile = null
    }

    private fun writeToFile(line: String) {
        try {
            logFileWriter?.write(line)
            logFileWriter?.write("\n")
            logFileWriter?.flush()
        } catch (e: Exception) {
            Log.e("LogManager", "Failed to write to log file", e)
        }
    }

    fun saveLogsToFile(ctx: Context): String? {
        val baseDir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val logDir = File(baseDir, "logs")
        if (!logDir.exists()) {
            logDir.mkdirs()
        }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val file = File(logDir, "log_$timestamp.txt")

        try {
            file.writeText(_logs.value.joinToString("\n"))
            return file.absolutePath
        } catch (e: Exception) {
            return null
        }
    }

    fun getLogsDir(ctx: Context): File {
        val baseDir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val logDir = File(baseDir, "logs")
        if (!logDir.exists()) {
            logDir.mkdirs()
        }
        return logDir
    }
}
