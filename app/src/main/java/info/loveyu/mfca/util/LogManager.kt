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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class LogLevel(val androidPriority: Int, val tag: String) {
    DEBUG(Log.DEBUG, "D"),
    INFO(Log.INFO, "I"),
    WARN(Log.WARN, "W"),
    ERROR(Log.ERROR, "E")
}

/**
 * 日志条目结构（替代原来的字符串列表）。
 * level 在 LogManager.log() 时解析完毕，UI 层直接使用无需重复解析。
 */
data class LogEntry(
    val id: Long,
    val timestamp: String,
    val level: LogLevel,
    val tag: String,
    val message: String,
    val contextJson: String? = null
) {
    /** 格式化字符串（用于复制到剪贴板） */
    val formatted: String = buildString {
        append("[$timestamp] [${level.tag}:$tag] $message")
        contextJson?.let { append(" $it") }
    }
}

object LogManager {
    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private val _logLevel = MutableStateFlow(LogLevel.INFO)
    val logLevelFlow: StateFlow<LogLevel> = _logLevel.asStateFlow()

    /** 当为 true 时使用 println 输出（单元测试环境） */
    var logToStdout = false

    private var isPaused = false
    private var currentLogLevel: LogLevel
        get() = _logLevel.value
        set(value) { _logLevel.value = value }
    private var isFileLoggingEnabled = false
    private var isAllLogcatEnabled = false
    private var maxLogLines = 1000

    private var logFile: File? = null
    private var logFileStream: FileOutputStream? = null
    private var logFileWriter: BufferedWriter? = null
    private var contextRef: WeakReference<Context>? = null
    private val fileLock = Any()

    // 线程安全的 DateTimeFormatter（替代非线程安全的 SimpleDateFormat）
    private val dateFormat = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.getDefault())
        .withZone(ZoneId.systemDefault())
    private val fileDateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        .withZone(ZoneId.systemDefault())
    private val exitFileDateFormat = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS", Locale.getDefault())
        .withZone(ZoneId.systemDefault())

    // 环形缓冲：避免每条日志复制整个列表
    private val logBuffer = ArrayDeque<LogEntry>(1000)
    private val bufferLock = Any()

    // 自增 ID 计数器（提供稳定 key）
    private var logIdCounter = 0L
    private val idLock = Any()

    private fun nextId(): Long = synchronized(idLock) { ++logIdCounter }

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
            if (logFileWriter == null) {
                startFileLogging()
            }
        } else if (logFileWriter != null) {
            stopFileLogging()
        }
    }

    fun log(level: LogLevel, tag: String, message: String, context: Any? = null) {
        if (isPaused) return
        if (level.androidPriority < currentLogLevel.androidPriority) return

        val now = Instant.now()
        val timestamp = dateFormat.format(now)
        val fileTimestamp = fileDateFormat.format(now)
        val contextJson = context?.let {
            try {
                JSONObject().put("context", it)
            } catch (e: Exception) {
                null
            }
        }

        if (isAllLogcatEnabled || level.androidPriority >= Log.WARN) {
            val logcatMsg = if (contextJson != null) "$message $contextJson" else message
            if (logToStdout) {
                println("[${level.tag}:$tag] $logcatMsg")
            } else {
                Log.println(level.androidPriority, tag, logcatMsg)
            }
        }

        // 构造 LogEntry（level 解析完毕，UI 层直接使用）
        val entry = LogEntry(
            id = nextId(),
            timestamp = timestamp,
            level = level,
            tag = tag,
            message = message,
            contextJson = contextJson?.toString()
        )

        // 环形缓冲写入
        synchronized(bufferLock) {
            while (logBuffer.size >= maxLogLines) {
                logBuffer.removeFirst()
            }
            logBuffer.addLast(entry)
            _logs.value = logBuffer.toList()
        }

        // Write to file if enabled（文件格式不变）
        if (isFileLoggingEnabled) {
            val fileLine = if (contextJson != null) {
                "[$fileTimestamp] [${level.tag}:$tag] $message $contextJson"
            } else {
                "[$fileTimestamp] [${level.tag}:$tag] $message"
            }
            writeToFile(fileLine)
        }
    }

    /**
     * 追加单条日志（供外部调用，如等级切换时追加提示）。
     * 绕过 currentLogLevel 过滤，直接追加。
     */
    fun appendLog(level: LogLevel, tag: String, message: String) {
        val now = Instant.now()
        val entry = LogEntry(
            id = nextId(),
            timestamp = dateFormat.format(now),
            level = level,
            tag = tag,
            message = message,
            contextJson = null
        )
        synchronized(bufferLock) {
            while (logBuffer.size >= maxLogLines) {
                logBuffer.removeFirst()
            }
            logBuffer.addLast(entry)
            _logs.value = logBuffer.toList()
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
        synchronized(bufferLock) {
            logBuffer.clear()
            _logs.value = emptyList()
        }
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

        val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.getDefault())
            .withZone(ZoneId.systemDefault()).format(Instant.now())
        logFile = File(logDir, "log_$timestamp.txt")

        try {
            synchronized(fileLock) {
                logFileStream = FileOutputStream(logFile, true)
                logFileWriter = BufferedWriter(
                    OutputStreamWriter(logFileStream, Charsets.UTF_8),
                    8192
                )
                synchronized(bufferLock) {
                    logBuffer.forEach { entry ->
                        logFileWriter?.write(entry.formatted)
                        logFileWriter?.write("\n")
                    }
                }
                logFileWriter?.flush()
            }
        } catch (e: Exception) {
            Log.e("LogManager", "Failed to start file logging", e)
        }
    }

    private fun stopFileLogging() {
        try {
            synchronized(fileLock) {
                logFileWriter?.close()
                logFileStream?.close()
            }
        } catch (e: Exception) {
            Log.e("LogManager", "Failed to stop file logging", e)
        }
        logFileWriter = null
        logFileStream = null
        logFile = null
    }

    private fun writeToFile(line: String) {
        try {
            synchronized(fileLock) {
                logFileWriter?.write(line)
                logFileWriter?.write("\n")
            }
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
            synchronized(fileLock) {
                logFileWriter?.flush()
            }
        } catch (e: Exception) {
            Log.e("LogManager", "Failed to flush log file", e)
        }
    }

    fun flushAndSync() {
        try {
            synchronized(fileLock) {
                logFileWriter?.flush()
                logFileStream?.fd?.sync()
            }
        } catch (e: Exception) {
            Log.e("LogManager", "Failed to flush and sync log file", e)
        }
    }

    fun writeExitEventSync(
        ctx: Context,
        event: String,
        reason: String,
        throwable: Throwable? = null,
        extras: Map<String, String> = emptyMap()
    ): String? {
        val baseDir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val exitDir = File(File(baseDir, "logs"), "exit")
        if (!exitDir.exists()) {
            exitDir.mkdirs()
        }

        val file = File(exitDir, "exit_${exitFileDateFormat.format(Instant.now())}.log")
        val recentLogs = synchronized(bufferLock) { logBuffer.takeLast(200) }
        val content = buildString {
            appendLine("time=${fileDateFormat.format(Instant.now())}")
            appendLine("event=$event")
            appendLine("reason=$reason")
            extras.toSortedMap().forEach { (key, value) ->
                appendLine("$key=$value")
            }
            throwable?.let {
                appendLine()
                appendLine("stacktrace:")
                appendLine(Log.getStackTraceString(it))
            }
            appendLine()
            appendLine("recentLogs:")
            recentLogs.forEach { entry ->
                appendLine(entry.formatted)
            }
        }

        return try {
            FileOutputStream(file, false).use { stream ->
                OutputStreamWriter(stream, Charsets.UTF_8).use { writer ->
                    writer.write(content)
                    writer.flush()
                    stream.fd.sync()
                }
            }
            flushAndSync()
            file.absolutePath
        } catch (e: Exception) {
            Log.e("LogManager", "Failed to write exit event log", e)
            null
        }
    }

    fun saveLogsToFile(ctx: Context): String? {
        val baseDir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val logDir = File(baseDir, "logs")
        if (!logDir.exists()) {
            logDir.mkdirs()
        }

        val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.getDefault())
            .withZone(ZoneId.systemDefault()).format(Instant.now())
        val file = File(logDir, "log_$timestamp.log")

        try {
            val logsCopy = synchronized(bufferLock) { logBuffer.toList() }
            file.writeText(logsCopy.joinToString("\n") { it.formatted })
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
