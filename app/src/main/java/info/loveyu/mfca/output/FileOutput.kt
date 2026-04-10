package info.loveyu.mfca.output

import android.content.Context
import android.os.Environment
import info.loveyu.mfca.config.InternalOutputConfig
import info.loveyu.mfca.output.OutputType
import info.loveyu.mfca.queue.QueueItem
import info.loveyu.mfca.util.LogManager
import java.io.File

/**
 * 文件输出
 *
 * 路径协议:
 * - data:// - 应用私有数据目录 (context.getExternalFilesDir)
 * - sdcard:// - 外部存储卡目录
 * - file:// - 文件系统绝对路径
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

            val timestamp = System.currentTimeMillis()
            val extension = if (options["auto_extension"] == true) ".dat" else ""
            val fileName = "msg_$timestamp$extension"

            val file = File(dir, fileName)
            file.writeBytes(item.data)

            LogManager.appendLog("INTERNAL", "Written to file: ${file.absolutePath}")
            callback?.invoke(true)
        } catch (e: Exception) {
            LogManager.appendLog("INTERNAL", "File write failed: ${e.message}")
            callback?.invoke(false)
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
}