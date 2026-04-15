package info.loveyu.mfca.output

import android.content.Context
import android.os.Environment
import info.loveyu.mfca.config.InternalOutputConfig
import info.loveyu.mfca.queue.QueueItem
import info.loveyu.mfca.util.LogManager
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 文件输出
 *
 * 路径协议:
 * - data:// - 应用私有数据目录 (context.getExternalFilesDir)
 * - sdcard:// - 外部存储卡目录
 * - file:// - 文件系统绝对路径
 *
 * fileName 支持变量:
 * - 时间变量: {yyyy} {yy} {MM} {dd} {HH} {mm} {ss} {SSS} {timestamp}
 * - QueueItem 变量: {id} {meta.xxx} (从 metadata 中读取 key xxx)
 *
 * options:
 * - autoExtension (bool) 仅在未设置 fileName 或为空时生效，自动添加 .dat 扩展名
 * - append (bool) 追加写入，与 overwrite 冲突；append 模式默认启用 8KB 缓冲
 * - overwrite (bool) 覆盖写入，与 append 冲突
 * - lock (bool) 是否加锁写入，默认 true
 * - eof (string) 写入数据后追加的字符串，默认 null 不追加
 * - buffer (int) 缓冲大小(bytes)，仅 append 模式生效；默认 8192
 * - bufferMaxIdle (duration) handle 空闲超时，超时后关闭；格式 "5m"(默认), "30s", "1h"
 */
class FileOutput(
    private val context: Context,
    override val name: String,
    private val config: InternalOutputConfig
) : InternalOutput {
    override val type: OutputType = OutputType.internal

    // 缓冲句柄映射：key = resolved file path
    private val handles = ConcurrentHashMap<String, BufferedFileHandle>()

    // 缓冲配置（send 时从 options 解析，相同 output 的配置固定）
    private var configuredBufferSize: Int? = null
    private var configuredMaxIdleMs: Long = 5 * 60 * 1000L // 默认 5 分钟

    override fun send(item: QueueItem, callback: ((Boolean) -> Unit)?) {
        try {
            val basePath = resolvePath(config.basePath ?: "data://output")
            val options = config.options ?: emptyMap()

            if (LogManager.isDebugEnabled()) {
                LogManager.logDebug("FILE", "send() - name=$name, itemId=${item.id}, basePath=$basePath")
            }

            val dir = File(basePath)
            if (!dir.exists() && !dir.mkdirs()) {
                LogManager.logError("INTERNAL", "FileOutput [$name]: failed to create directory: ${dir.absolutePath}")
                callback?.invoke(false)
                return
            }

            val fileName = resolveFileName(config.fileName, item, options)
            val file = File(dir, fileName)

            val useLock = options["lock"] as? Boolean ?: true
            val eof = options["eof"] as? String
            val append = options["append"] as? Boolean ?: false
            val overwrite = options["overwrite"] as? Boolean ?: false
            val bufferSizeOpt = options["buffer"] as? Int
            val maxIdleMs = parseDuration(options["bufferMaxIdle"] ?: "5m")

            val dataToWrite = if (eof != null) {
                item.data + eof.toByteArray(Charsets.UTF_8)
            } else {
                item.data
            }

            val writeMode = when {
                overwrite && append -> {
                    LogManager.logWarn("INTERNAL", "FileOutput [$name]: append and overwrite conflict, using overwrite")
                    WriteMode.OVERWRITE
                }
                append -> WriteMode.APPEND
                overwrite -> WriteMode.OVERWRITE
                else -> WriteMode.CREATE
            }

            if (LogManager.isDebugEnabled()) {
                LogManager.logDebug("FILE", "writeMode=$writeMode, file=${file.name}, dataLen=${dataToWrite.size}, useLock=$useLock")
            }

            if (writeMode == WriteMode.CREATE && file.exists()) {
                LogManager.logWarn("INTERNAL", "FileOutput [$name]: file already exists: ${file.absolutePath}")
                callback?.invoke(false)
                return
            }

            // 缓冲写入路径：append + 已配置 bufferSize
            val effectiveBufferSize = when {
                bufferSizeOpt != null -> bufferSizeOpt
                append -> 8192 // append 模式默认 8KB 缓冲
                else -> null
            }

            if (writeMode == WriteMode.APPEND && effectiveBufferSize != null) {
                // 更新类级别的缓冲配置（首次写入时确定）
                if (configuredBufferSize == null) configuredBufferSize = effectiveBufferSize
                if (configuredMaxIdleMs == 5 * 60 * 1000L) configuredMaxIdleMs = maxIdleMs

                val resolvedPath = file.absolutePath
                val handle = handles.getOrPut(resolvedPath) {
                    BufferedFileHandle(resolvedPath, effectiveBufferSize)
                }
                handle.write(dataToWrite)
                LogManager.logDebug("FILE", "Buffered write: $resolvedPath (buffered ${dataToWrite.size} bytes)")
                callback?.invoke(true)
                return
            }

            // 直接写入路径（无缓冲）
            when {
                useLock -> writeWithLock(file, dataToWrite, writeMode)
                writeMode == WriteMode.APPEND -> file.appendBytes(dataToWrite)
                else -> file.writeBytes(dataToWrite)
            }

            LogManager.log("INTERNAL", "Written to file: ${file.absolutePath} (${writeMode.name.lowercase()}, ${dataToWrite.size} bytes)")
            callback?.invoke(true)
        } catch (e: Exception) {
            LogManager.logError("INTERNAL", "File write failed: $name - ${e.message}")
            callback?.invoke(false)
        }
    }

    private enum class WriteMode {
        CREATE, APPEND, OVERWRITE
    }

    /**
     * 解析文件名，支持时间变量和 QueueItem 变量
     */
    private fun resolveFileName(configFileName: String?, item: QueueItem, options: Map<String, Any>): String {
        if (!configFileName.isNullOrBlank()) {
            return expandVariables(configFileName, item)
        }

        // 默认文件名：仅在此场景下 autoExtension 生效
        val timestamp = System.currentTimeMillis()
        val extension = if (options["autoExtension"] == true) ".dat" else ""
        return "msg_$timestamp$extension"
    }

    /**
     * 展开文件名中的变量
     * 时间变量: {yyyy} {yy} {MM} {dd} {HH} {mm} {ss} {SSS} {timestamp}
     * QueueItem 变量: {id} {meta.xxx}
     */
    private fun expandVariables(template: String, item: QueueItem): String {
        val now = Date()
        val sdfCache = mutableMapOf<String, SimpleDateFormat>()

        return VARIABLE_REGEX.replace(template) { match ->
            val key = match.groupValues[1]
            when (key) {
                "timestamp" -> System.currentTimeMillis().toString()
                "id" -> item.id.toString()
                else -> {
                    // 尝试从 meta.xxx 格式中读取 metadata
                    if (key.startsWith("meta.")) {
                        val metaKey = key.removePrefix("meta.")
                        item.metadata[metaKey] ?: ""
                    } else {
                        // 尝试作为日期格式
                        try {
                            val sdf = sdfCache.getOrPut(key) { SimpleDateFormat(key, Locale.getDefault()) }
                            sdf.format(now)
                        } catch (e: Exception) {
                            ""
                        }
                    }
                }
            }
        }
    }

    private fun writeWithLock(file: File, data: ByteArray, mode: WriteMode) {
        val append = mode == WriteMode.APPEND
        FileOutputStream(file, append).use { fos ->
            fos.channel.use { channel ->
                channel.lock().use {
                    channel.write(ByteBuffer.wrap(data))
                }
            }
        }
    }

    private fun resolvePath(path: String): String {
        return when {
            path.startsWith("data://") -> {
                val relativePath = path.removePrefix("data://")
                val externalDir = context.getExternalFilesDir(null)
                if (externalDir != null) {
                    File(externalDir, relativePath).absolutePath
                } else {
                    File(context.filesDir, relativePath).absolutePath
                }
            }
            path.startsWith("sdcard://") -> {
                val relativePath = path.removePrefix("sdcard://")
                File(Environment.getExternalStorageDirectory(), relativePath).absolutePath
            }
            path.startsWith("file://") -> {
                path.removePrefix("file://")
            }
            else -> throw IllegalArgumentException("Unsupported path protocol: $path, must use data://, sdcard:// or file://")
        }
    }

    /**
     * 按 resolved path 独立缓冲的写入句柄。
     * 每个句柄维护自己的 BufferedWriter，线程安全。
     */
    private class BufferedFileHandle(
        private val resolvedPath: String,
        private val bufferSize: Int
    ) {
        private var writer: BufferedWriter? = null
        private var file: File? = null
        var lastAccessMs: Long = System.currentTimeMillis()
        var isDirty: Boolean = false
        private val lock = Any()

        private fun getWriter(): BufferedWriter {
            return writer ?: synchronized(lock) {
                writer ?: run {
                    val f = File(resolvedPath)
                    file = f
                    // 确保父目录存在
                    f.parentFile?.mkdirs()
                    val fos = FileOutputStream(f, true) // append mode
                    val bw = BufferedWriter(OutputStreamWriter(fos, Charsets.UTF_8), bufferSize)
                    writer = bw
                    bw
                }
            }
        }

        fun write(data: ByteArray) {
            synchronized(lock) {
                val w = getWriter()
                w.write(String(data, Charsets.UTF_8))
                w.flush() // 刷新到底层 OutputStream，但不到磁盘
                isDirty = true
                lastAccessMs = System.currentTimeMillis()
            }
        }

        fun flushIfDirty() {
            synchronized(lock) {
                if (!isDirty) return
                try {
                    writer?.flush()
                    isDirty = false
                    LogManager.logDebug("FILE", "Flushed buffer to: $resolvedPath")
                } catch (e: Exception) {
                    LogManager.logWarn("FILE", "Failed to flush buffer: $resolvedPath - ${e.message}")
                }
            }
        }

        fun close() {
            synchronized(lock) {
                flushIfDirty()
                try {
                    writer?.close()
                } catch (e: Exception) {
                    LogManager.logDebug("FILE", "Error closing writer: $resolvedPath - ${e.message}")
                }
                writer = null
                file = null
            }
        }
    }

    /**
     * 刷新所有缓冲句柄并清理空闲句柄。
     * 由 ForwardService tick 周期调用。
     */
    fun flushAll() {
        val maxIdle = configuredMaxIdleMs
        handles.entries.removeIf { (path, handle) ->
            handle.flushIfDirty()
            val idle = System.currentTimeMillis() - handle.lastAccessMs
            if (idle > maxIdle) {
                handle.close()
                LogManager.logDebug("FILE", "Closed idle handle: $path, idle=${idle}ms")
                true
            } else false
        }
    }

    /**
     * 解析 duration 字符串，如 "5m", "30s", "1h"
     */
    private fun parseDuration(value: Any?): Long {
        val str = value?.toString() ?: return 5 * 60 * 1000L
        return try {
            val trimmed = str.trim()
            val number = trimmed.dropLast(trimmed.length - trimmed.indexOfFirst { !it.isDigit() }.coerceAtLeast(0)).toLongOrNull() ?: 300_000L
            when {
                trimmed.endsWith("s") -> number * 1000
                trimmed.endsWith("m") -> number * 60 * 1000
                trimmed.endsWith("h") -> number * 60 * 60 * 1000
                trimmed.endsWith("d") -> number * 24 * 60 * 60 * 1000
                else -> number
            }
        } catch (e: Exception) {
            5 * 60 * 1000L
        }
    }

    companion object {
        private val VARIABLE_REGEX = Regex("""\{(\w[\w.]*)\}""")
    }
}
