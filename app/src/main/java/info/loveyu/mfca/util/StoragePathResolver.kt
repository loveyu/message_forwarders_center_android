package info.loveyu.mfca.util

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * 统一解析本地存储路径协议，避免各处实现不一致。
 *
 * 支持:
 * - data://   应用私有文件目录（优先 externalFilesDir，回退 filesDir）
 * - cache://  应用缓存目录
 * - sdcard:// 外部存储根目录
 * - file://   文件系统绝对路径
 */
object StoragePathResolver {

    private const val DATA_PREFIX = "data://"
    private const val CACHE_PREFIX = "cache://"
    private const val SDCARD_PREFIX = "sdcard://"
    private const val FILE_PREFIX = "file://"

    fun resolvePath(context: Context, path: String, allowRawPath: Boolean = false): String {
        return resolveFile(context, path, allowRawPath).absolutePath
    }

    fun resolveFile(context: Context, path: String, allowRawPath: Boolean = false): File {
        return resolveFile(
            path = path,
            externalFilesDir = context.getExternalFilesDir(null),
            filesDir = context.filesDir,
            cacheDir = context.cacheDir,
            sdcardDir = Environment.getExternalStorageDirectory(),
            allowRawPath = allowRawPath
        )
    }

    internal fun resolveFile(
        path: String,
        externalFilesDir: File?,
        filesDir: File,
        cacheDir: File,
        sdcardDir: File,
        allowRawPath: Boolean = false
    ): File {
        val normalizedPath = path.trim()
        return when {
            normalizedPath.startsWith(DATA_PREFIX) -> {
                File(externalFilesDir ?: filesDir, stripRelativePrefix(normalizedPath, DATA_PREFIX))
            }

            normalizedPath.startsWith(CACHE_PREFIX) -> {
                File(cacheDir, stripRelativePrefix(normalizedPath, CACHE_PREFIX))
            }

            normalizedPath.startsWith(SDCARD_PREFIX) -> {
                File(sdcardDir, stripRelativePrefix(normalizedPath, SDCARD_PREFIX))
            }

            normalizedPath.startsWith(FILE_PREFIX) -> {
                File(normalizedPath.removePrefix(FILE_PREFIX))
            }

            allowRawPath -> File(normalizedPath)

            else -> throw IllegalArgumentException(
                "Unsupported path protocol: $path, must use data://, cache://, sdcard:// or file://"
            )
        }
    }

    private fun stripRelativePrefix(path: String, prefix: String): String {
        return path.removePrefix(prefix).trimStart('/')
    }
}
