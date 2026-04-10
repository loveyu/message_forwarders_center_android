package info.loveyu.mfca.output

import android.content.Context
import android.os.Environment
import info.loveyu.mfca.config.InternalOutputConfig
import info.loveyu.mfca.queue.QueueItem
import info.loveyu.mfca.util.LogManager
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
 * - auto_extension (bool) 仅在未设置 fileName 或为空时生效，自动添加 .dat 扩展名
 * - append (bool) 追加写入，与 overwrite 冲突
 * - overwrite (bool) 覆盖写入，与 append 冲突
 * - lock (bool) 是否加锁写入，默认 true
 * - eof (string) 写入数据后追加的字符串，默认 null 不追加
 */
class FileOutput(
    private val context: Context,
    override val name: String,
    private val config: InternalOutputConfig
) : InternalOutput {
    override val type: OutputType = OutputType.internal

    override fun send(item: QueueItem, callback: ((Boolean) -> Unit)?) {
        try {
            val basePath = resolvePath(config.basePath ?: "data://output")
            val options = config.options ?: emptyMap()

            val dir = File(basePath)
            if (!dir.exists()) {
                dir.mkdirs()
            }

            val fileName = resolveFileName(config.fileName, item, options)
            val file = File(dir, fileName)

            val useLock = options["lock"] as? Boolean ?: true
            val eof = options["eof"] as? String
            val append = options["append"] as? Boolean ?: false
            val overwrite = options["overwrite"] as? Boolean ?: false

            val dataToWrite = if (eof != null) {
                item.data + eof.toByteArray(Charsets.UTF_8)
            } else {
                item.data
            }

            val writeMode = when {
                overwrite && append -> {
                    LogManager.appendLog("INTERNAL", "FileOutput [$name]: append and overwrite conflict, using overwrite")
                    WriteMode.OVERWRITE
                }
                append -> WriteMode.APPEND
                overwrite -> WriteMode.OVERWRITE
                else -> WriteMode.CREATE
            }

            if (writeMode == WriteMode.CREATE && file.exists()) {
                LogManager.appendLog("INTERNAL", "FileOutput [$name]: file already exists, neither append nor overwrite set: ${file.absolutePath}")
                callback?.invoke(false)
                return
            }

            when {
                useLock -> writeWithLock(file, dataToWrite, writeMode)
                writeMode == WriteMode.APPEND -> file.appendBytes(dataToWrite)
                else -> file.writeBytes(dataToWrite)
            }

            LogManager.appendLog("INTERNAL", "Written to file: ${file.absolutePath} (${writeMode.name.lowercase()})")
            callback?.invoke(true)
        } catch (e: Exception) {
            LogManager.appendLog("INTERNAL", "File write failed: ${e.message}")
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

        // 默认文件名：仅在此场景下 auto_extension 生效
        val timestamp = System.currentTimeMillis()
        val extension = if (options["auto_extension"] == true) ".dat" else ""
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

    companion object {
        private val VARIABLE_REGEX = Regex("""\{(\w[\w.]*)\}""")
    }
}
