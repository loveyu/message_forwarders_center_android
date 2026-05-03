package info.loveyu.mfca.output

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import info.loveyu.mfca.MainActivity
import info.loveyu.mfca.config.InternalOutputConfig
import info.loveyu.mfca.config.NotifyOptions
import info.loveyu.mfca.notification.NotifyHistoryDbHelper
import info.loveyu.mfca.notification.NotifyRecord
import info.loveyu.mfca.queue.QueueItem
import info.loveyu.mfca.util.IconCacheManager
import info.loveyu.mfca.util.LogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * 通知输出
 */
class NotifyOutput(
    private val context: Context,
    override val name: String,
    private val config: InternalOutputConfig
) : InternalOutput {

    override val type: OutputType = OutputType.internal
    override val formatSteps get() = config.format

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val iconCacheManager = IconCacheManager.getInstance(context)
    private val historyDbHelper = NotifyHistoryDbHelper(context)

    // 每个 output name 对应最后通知的内容和时间（用于去重）
    private val lastNotify = mutableMapOf<String, Pair<String, Long>>()

    companion object {
        private const val DEDUP_INTERVAL_MS = 30_000L // 30秒

        // 全局序列号生成器 (0-999 循环)
        private val sequence = AtomicInteger(0)

        // 模板变量正则: {variable} 或 {date:format}（预编译）
        private val TEMPLATE_REGEX = Regex("\\{(\\w+(?:\\.[\\w.]+)?|date:[^}]+)\\}")
    }

    init {
        createNotificationChannel()
    }

    /**
     * 创建通知渠道
     */
    private fun createNotificationChannel() {
        val channelId = config.channel ?: "default"
        val channelName = config.channel ?: "默认通知"

        val importance = NotificationManager.IMPORTANCE_DEFAULT
        val channel = NotificationChannel("channel_$channelId", channelName, importance).apply {
            description = "Forwarder通知: $channelName"
            enableLights(true)
            enableVibration(true)
            setLockscreenVisibility(android.app.Notification.VISIBILITY_PUBLIC)
            setSound(null, null)
        }

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    override fun send(item: QueueItem, callback: ((Boolean) -> Unit)?) {
        try {
            val channelId = config.channel ?: "default"
            val text = item.text
            val now = System.currentTimeMillis()

            if (LogManager.isDebugEnabled()) {
                LogManager.logDebug("NOTIFY", "send() - name=$name, channel=$channelId, itemId=${item.id}, dataLen=${item.data.size}")
            }

            // 解析通知选项
            val notifyOptions = parseNotifyOptions(item, channelId)
            val title = notifyOptions.title ?: "新消息"
            val content = notifyOptions.message ?: text
            val iconUrl = notifyOptions.icon
            val fixedIconId = notifyOptions.fixedIcon
            val popup = notifyOptions.popup
            val persistent = notifyOptions.persistent

            // 计算 tag: 支持模板，默认值为输出名称
            val tag = notifyOptions.tag?.let { expandTemplate(it, item, channelId) }
                ?: generateDefaultTag()

            // 计算 group: 支持模板，默认值与 tag 相同（按 tag 自动分组）
            val group = notifyOptions.group?.let { expandTemplate(it, item, channelId) }
                ?: tag

            // 计算 id: 支持模板，默认值为秒级时间戳 * 1000 + 序列号
            val id = notifyOptions.id?.let { expandTemplate(it, item, channelId) }?.toIntOrNull()
                ?: generateDefaultId()

            // 去重检查
            val lastEntry = lastNotify[name]
            if (lastEntry != null && lastEntry.first == text && now - lastEntry.second < DEDUP_INTERVAL_MS) {
                LogManager.logWarn("INTERNAL", "Notify skipped (duplicated): $name")
                callback?.invoke(true)
                return
            }

            if (LogManager.isDebugEnabled()) {
                LogManager.logDebug("NOTIFY", "[$name] sending - tag=$tag, group=$group, id=$id, title=$title, hasIcon=${iconUrl != null}, popup=$popup")
            }

            // 异步获取图标并发送通知
            scope.launch {
                try {
                    val iconBitmap = iconCacheManager.getIcon(iconUrl, fixedIconId)
                    showNotification(channelId, tag, group, id, title, content, iconBitmap, popup, persistent)
                    lastNotify[name] = text to now
                    // 记录通知到历史数据库
                    saveNotifyHistory(
                        notifyId = id, title = title, content = content,
                        rawData = item.text, channelId = channelId,
                        tag = tag, group = group, iconUrl = iconUrl,
                        sourceRule = item.metadata["rule"],
                        sourceInput = item.metadata["source"],
                        popup = popup, persistent = persistent
                    )
                    callback?.invoke(true)
                } catch (e: Exception) {
                    LogManager.logError("INTERNAL", "Notify failed: $name - ${e.message}")
                    callback?.invoke(false)
                }
            }
        } catch (e: Exception) {
            LogManager.logError("INTERNAL", "Notify error: $name - ${e.message}")
            callback?.invoke(false)
        }
    }

    /**
     * 生成默认 tag: 直接使用输出名称
     */
    private fun generateDefaultTag(): String {
        return name
    }

    /**
     * 生成默认 id: 秒级时间戳 * 1000 + 序列号 (确保非1)
     */
    private fun generateDefaultId(): Int {
        val timestamp = System.currentTimeMillis() / 1000
        val seq = sequence.incrementAndGet() % 1000
        var id = (timestamp * 1000 + seq).toInt()
        // 避免与前台通知 ID (=1) 冲突
        if (id == 1) id = 1001
        return id
    }

    /**
     * 保存通知记录到历史数据库
     */
    private fun saveNotifyHistory(
        notifyId: Int,
        title: String,
        content: String,
        rawData: String,
        channelId: String,
        tag: String?,
        group: String?,
        iconUrl: String?,
        sourceRule: String?,
        sourceInput: String?,
        popup: Boolean?,
        persistent: Boolean?
    ) {
        try {
            val record = NotifyRecord(
                notifyId = notifyId,
                title = title,
                content = content,
                rawData = rawData,
                outputName = name,
                channel = channelId,
                tag = tag,
                group = group,
                iconUrl = iconUrl,
                sourceRule = sourceRule,
                sourceInput = sourceInput,
                popup = popup == true,
                persistent = persistent == true,
                createdAt = System.currentTimeMillis()
            )
            historyDbHelper.insert(record)
        } catch (e: Exception) {
            LogManager.logError("INTERNAL", "Failed to save notify history via $name: ${e.message}")
        }
    }

    /**
     * 展开模板字符串
     *
     * 支持的变量:
     * - {channel} - 输出配置的 channel
     * - {name} - 输出配置的 name
     * - {seq} - 递增序列号 (0-999 循环)
     * - {timestamp} - 秒级时间戳 (10位)
     * - {unix} - 毫秒级时间戳 (13位)
     * - {date:format} - 格式化日期，如 {date:yyyyMMddHHmmss}
     * - {data} - 原始数据内容 (UTF-8 解码)
     * - {data.path} - JSON 路径提取 (当 data 为 JSON 时)
     * - {meta.key} - metadata 字段
     */
    fun expandTemplate(template: String, item: QueueItem, channelId: String): String {
        val dataStr = String(item.data, Charsets.UTF_8)
        val sdfCache = mutableMapOf<String, SimpleDateFormat>()

        return template.replace(TEMPLATE_REGEX) { match ->
            val key = match.groupValues[1]
            when {
                key == "channel" -> channelId
                key == "name" -> name
                key == "seq" -> (sequence.incrementAndGet() % 1000).toString()
                key == "timestamp" -> (System.currentTimeMillis() / 1000).toString()
                key == "unix" -> System.currentTimeMillis().toString()
                key.startsWith("date:") -> {
                    val format = key.removePrefix("date:")
                    sdfCache.getOrPut(format) { SimpleDateFormat(format, Locale.ROOT) }
                        .format(Date())
                }
                key == "data" -> dataStr
                key.startsWith("data.") -> {
                    // JSON 路径提取，如 data.field.subfield
                    val jsonPath = key.removePrefix("data.")
                    extractJsonPath(dataStr, jsonPath)
                }
                key.startsWith("meta.") -> {
                    val metaKey = key.removePrefix("meta.")
                    item.metadata[metaKey] ?: ""
                }
                else -> match.value
            }
        }
    }

    /**
     * 从 JSON 字符串中提取路径
     */
    private fun extractJsonPath(jsonStr: String, path: String): String {
        try {
            if (!jsonStr.startsWith("{")) return ""
            val json = JSONObject(jsonStr)
            val parts = path.split(".")
            var current: Any = json
            for (part in parts) {
                current = when (current) {
                    is JSONObject -> current.opt(part) ?: return ""
                    is String -> JSONObject(current).opt(part) ?: return ""
                    else -> return ""
                }
            }
            return when (current) {
                is String -> current
                is Number -> current.toString()
                is Boolean -> current.toString()
                else -> current.toString()
            }
        } catch (e: Exception) {
            return ""
        }
    }

    /**
     * 解析通知选项
     * 支持从 item.metadata、item.data JSON 或 config.options 中获取配置
     */
    private fun parseNotifyOptions(item: QueueItem, channelId: String): NotifyOptions {
        val options = NotifyOptions()

        // 先尝试从 metadata 获取 (运行时覆盖优先级最高)
        item.metadata["notify_title"]?.let { options.title = it }
        item.metadata["notify_icon"]?.let { options.icon = it }
        item.metadata["notify_fixed_icon"]?.let { options.fixedIcon = it }
        item.metadata["notify_popup"]?.let { options.popup = it.toBoolean() }
        item.metadata["notify_persistent"]?.let { options.persistent = it.toBoolean() }
        item.metadata["notify_tag"]?.let { options.tag = it }
        item.metadata["notify_group"]?.let { options.group = it }
        item.metadata["notify_id"]?.let { options.id = it }

        // 尝试解析 data 中的 JSON 对象 (次优先级)
        // 内容字段 (title/message/icon/fixedIcon) 直接从 data 读取
        // 控制字段 (tag/group/id/popup/persistent) 使用 notify 前缀避免与消息业务数据冲突
        try {
            val dataStr = item.text
            if (dataStr.startsWith("{")) {
                val json = JSONObject(dataStr)
                // 内容字段：直接读取
                if (json.has("title") && options.title == null) options.title = json.getString("title")
                if (json.has("message") && options.message == null) options.message = json.getString("message")
                if (json.has("icon") && options.icon == null) options.icon = json.getString("icon")
                if (json.has("fixedIcon") && options.fixedIcon == null) options.fixedIcon = json.getString("fixedIcon")
                // 控制字段：使用 notify 前缀 (notifyTag/notifyGroup/notifyId/notifyPopup/notifyPersistent)
                if (json.has("notifyTag") && options.tag == null) options.tag = json.getString("notifyTag")
                if (json.has("notifyGroup") && options.group == null) options.group = json.getString("notifyGroup")
                if (json.has("notifyId") && options.id == null) options.id = json.getString("notifyId")
                if (json.has("notifyPopup") && options.popup == null) options.popup = json.getBoolean("notifyPopup")
                if (json.has("notifyPersistent") && options.persistent == null) options.persistent = json.getBoolean("notifyPersistent")
            }
        } catch (e: Exception) {
            // 不是 JSON 格式，忽略
        }

        // 应用配置中的默认值 (最低优先级)
        config.options?.let { configOptions ->
            if (options.title == null) configOptions["title"]?.toString()?.let { options.title = it }
            if (options.icon == null) configOptions["icon"]?.toString()?.let { options.icon = it }
            if (options.fixedIcon == null) configOptions["fixedIcon"]?.toString()?.let { options.fixedIcon = it }
            if (options.popup == null) configOptions["popup"]?.let { options.popup = it as? Boolean ?: it.toString().toBoolean() }
            if (options.persistent == null) configOptions["persistent"]?.let { options.persistent = it as? Boolean ?: it.toString().toBoolean() }
            if (options.tag == null) configOptions["tag"]?.toString()?.let { options.tag = it }
            if (options.group == null) configOptions["group"]?.toString()?.let { options.group = it }
            if (options.id == null) configOptions["id"]?.toString()?.let { options.id = it }
        }

        return options
    }

    /**
     * 显示通知
     */
    private fun showNotification(
        channelId: String,
        tag: String,
        group: String,
        id: Int,
        title: String,
        content: String,
        iconBitmap: Bitmap?,
        popup: Boolean?,
        persistent: Boolean?
    ) {
        // 检查通知权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                LogManager.logWarn("INTERNAL", "Notification permission not granted, skipped: $name")
                return
            }
        }

        // 创建点击意图 - 打开通知历史页面并定位到该通知
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("notify_id", id)
            putExtra("highlight", true)
        }
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(context, id, intent, pendingIntentFlags)

        // 构建通知
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setGroup(group)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        // 设置大图标
        iconBitmap?.let {
            builder.setLargeIcon(it)
        }

        // 设置弹出窗口
        if (popup == true) {
            builder.setPriority(NotificationCompat.PRIORITY_HIGH)
            builder.setCategory(NotificationCompat.CATEGORY_MESSAGE)
        }

        // 设置持久化通知
        if (persistent == true) {
            builder.setOngoing(true)
            builder.setPriority(NotificationCompat.PRIORITY_LOW)
        }

        // 发送通知
        val notification = builder.build()
        val nm = NotificationManagerCompat.from(context)
        nm.notify(tag, id, notification)

        // 发送分组摘要通知 (Android 7.0+ 需要 summary 通知才能正确折叠)
        val summaryId = group.hashCode()
        val summaryBuilder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(content)
                    .setSummaryText(name)
            )
            .setGroup(group)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        if (popup == true) {
            summaryBuilder.setPriority(NotificationCompat.PRIORITY_HIGH)
        }
        nm.notify(group, summaryId, summaryBuilder.build())

        LogManager.logDebug("INTERNAL", "Notification sent via $name: $title (tag=$tag, group=$group, id=$id)")
    }
}
