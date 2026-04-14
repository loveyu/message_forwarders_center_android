package info.loveyu.mfca.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.BufferedWriter
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
    private var logFileWriter: BufferedWriter? = null
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

        val now = Date()
        val timestamp = dateFormat.format(now)
        val fileTimestamp = fileDateFormat.format(now)
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

        if (isAllLogcatEnabled || level.androidPriority >= Log.WARN) {
            val logcatMsg = if (contextJson != null) "$message $contextJson" else message
            Log.println(level.androidPriority, tag, logcatMsg)
        }

        // Write to UI log
        _logs.value = (_logs.value + logLine).takeLast(maxLogLines)

        // Write to file if enabled
        if (isFileLoggingEnabled) {
            val fileLine = if (contextJson != null) {
                "[$fileTimestamp] [${level.tag}:$tag] $message $contextJson"
            } else {
                "[$fileTimestamp] [${level.tag}:$tag] $message"
            }
            writeToFile(fileLine)
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

    fun isDebugEnabled(): Boolean = currentLogLevel == LogLevel.DEBUG

    fun isLoggable(level: LogLevel): Boolean = !isPaused && level.androidPriority >= currentLogLevel.androidPriority

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
            logFileWriter = BufferedWriter(
                OutputStreamWriter(FileOutputStream(logFile, true), Charsets.UTF_8),
                8192
            )
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
        } catch (e: Exception) {
            Log.e("LogManager", "Failed to write to log file", e)
        }
    }

    /**
     * 由统一 Ticker 在每个 tick 末尾调用，批量 flush 日志文件缓冲。
     * 避免每条日志都触发磁盘 I/O。
     */
    fun flush() {
        try {
            logFileWriter?.flush()
        } catch (e: Exception) {
            Log.e("LogManager", "Failed to flush log file", e)
        }
    }

    fun saveLogsToFile(ctx: Context): String? {
        val baseDir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val logDir = File(baseDir, "logs")
        if (!logDir.exists()) {
            logDir.mkdirs()
        }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val file = File(logDir, "log_$timestamp.log")

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
