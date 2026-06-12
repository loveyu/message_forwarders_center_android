package info.loveyu.mfca.m2m

import android.content.Context
import info.loveyu.mfca.config.ConfigLoader
import info.loveyu.mfca.config.models.GeoConfig
import info.loveyu.mfca.util.LogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "GeoManage"

fun loadGeoConfig(context: Context): GeoConfig {
    val appContext = context.applicationContext
    val dir = File(appContext.filesDir, "config")
    val configFile = File(dir, "current.yaml")
    return if (configFile.exists()) {
        try {
            ConfigLoader.loadConfig(configFile.readText()).geo
        } catch (e: Exception) {
            LogManager.logError(TAG, "Failed to load geo config: ${e.message}")
            GeoConfig()
        }
    } else {
        GeoConfig()
    }
}

suspend fun refreshStates(
    context: Context,
    cacheStates: MutableList<GeoCacheState>,
) = withContext(Dispatchers.IO) {
    val states = GeoFileManager.getAllCacheStates(context)
    cacheStates.clear()
    cacheStates.addAll(states)
}

suspend fun downloadSingle(
    context: Context,
    type: GeoFileType,
    url: String,
    stateStore: M2mStateStore,
    cacheStates: MutableList<GeoCacheState>,
    workingTypes: MutableList<GeoFileType>,
    downloadProgress: MutableMap<String, Float>,
    downloadError: MutableMap<String, String?>,
    snackbarHostState: androidx.compose.material3.SnackbarHostState,
) {
    if (url.isBlank()) {
        snackbarHostState.showSnackbar("URL 为空")
        return
    }
    downloadError[type.key] = null
    downloadProgress[type.key] = 0f
    workingTypes.add(type)
    try {
        val proxy = stateStore.getDownloadProxy()
        val result =
            GeoFileManager.downloadFile(context, type, url, proxy) { downloaded, total ->
                if (total > 0) {
                    downloadProgress[type.key] = downloaded.toFloat() / total.toFloat()
                }
            }
        result.onSuccess {
            downloadProgress[type.key] = 1f
            snackbarHostState.showSnackbar(
                context.getString(
                    info.loveyu.mfca.R.string.geo_download_success, type.label
                ),
            )
        }.onFailure { e ->
            downloadError[type.key] = e.message
            snackbarHostState.showSnackbar(
                context.getString(
                    info.loveyu.mfca.R.string.geo_download_failed, type.label, e.message ?: ""
                ),
            )
        }
        refreshStates(context, cacheStates)
    } finally {
        workingTypes.remove(type)
    }
}

suspend fun batchDownload(
    context: Context,
    geoConfig: GeoConfig,
    stateStore: M2mStateStore,
    cacheStates: MutableList<GeoCacheState>,
    workingTypes: MutableList<GeoFileType>,
    downloadProgress: MutableMap<String, Float>,
    downloadError: MutableMap<String, String?>,
    snackbarHostState: androidx.compose.material3.SnackbarHostState,
) {
    val types = GeoFileType.all.filter { type ->
        val url = GeoFileManager.getEffectiveUrl(
            type, GeoFileManager.resolveUrl(type, geoConfig), stateStore
        )
        url.isNotBlank()
    }
    if (types.isEmpty()) {
        snackbarHostState.showSnackbar("没有可下载的 URL")
        return
    }
    var successCount = 0
    var failCount = 0
    for (type in types) {
        downloadError[type.key] = null
        downloadProgress[type.key] = 0f
        workingTypes.add(type)
        val url = GeoFileManager.getEffectiveUrl(
            type, GeoFileManager.resolveUrl(type, geoConfig), stateStore
        )
        val proxy = stateStore.getDownloadProxy()
        val result =
            GeoFileManager.downloadFile(context, type, url, proxy) { downloaded, total ->
                if (total > 0) {
                    downloadProgress[type.key] = downloaded.toFloat() / total.toFloat()
                }
            }
        result.onSuccess {
            downloadProgress[type.key] = 1f
            successCount++
        }.onFailure { e ->
            downloadError[type.key] = e.message
            failCount++
        }
        workingTypes.remove(type)
    }
    refreshStates(context, cacheStates)
    val msg =
        if (failCount == 0) {
            "批量下载完成：$successCount 个成功"
        } else {
            "批量下载完成：$successCount 个成功，$failCount 个失败"
        }
    snackbarHostState.showSnackbar(msg)
}

suspend fun deleteSingle(
    context: Context,
    type: GeoFileType,
    cacheStates: MutableList<GeoCacheState>,
    snackbarHostState: androidx.compose.material3.SnackbarHostState,
) = withContext(Dispatchers.IO) {
    GeoFileManager.deleteCache(context, type)
    refreshStates(context, cacheStates)
    snackbarHostState.showSnackbar("${type.label} 缓存已删除")
}

suspend fun deleteAll(
    context: Context,
    cacheStates: MutableList<GeoCacheState>,
    snackbarHostState: androidx.compose.material3.SnackbarHostState,
) = withContext(Dispatchers.IO) {
    GeoFileManager.deleteAllCache(context)
    refreshStates(context, cacheStates)
    snackbarHostState.showSnackbar("所有 Geo 数据缓存已删除")
}

fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "%.1f MB".format(bytes.toDouble() / (1024 * 1024))
    }
}
