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
import info.loveyu.mfca.config.InternalOutputConfig
import info.loveyu.mfca.config.NotifyOptions
import info.loveyu.mfca.queue.QueueItem
import info.loveyu.mfca.service.ForwardService
import info.loveyu.mfca.util.IconCacheManager
import info.loveyu.mfca.util.LogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern

/**
 * 通知输出
 */
class NotifyOutput(
    private val context: Context,
    override val name: String,
    private val config: InternalOutputConfig
) : InternalOutput {

    override val type: OutputType = OutputType.internal

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val iconCacheManager = IconCacheManager(context)

    // 每个 output name 对应最后通知的内容和时间（用于去重）
    private val lastNotify = mutableMapOf<String, Pair<String, Long>>()

    companion object {
        private const val DEDUP_INTERVAL_MS = 30_000L // 30秒

        // 全局序列号生成器 (0-999 循环)
        private val sequence = AtomicInteger(0)

        // 模板变量正则: {variable} 或 {date:format}
        private val TEMPLATE_REGEX = Pattern.compile("\\{(\\w+(?:\\.[\\w.]+)?|date:[^}]+)\\}")

        // MD5 哈希正则 (用于 tag 默认值检测)
        private val MD5_HEX_REGEX = Pattern.compile("^[a-f0-9]{32}$")
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
        val channel = NotificationChannel(channelId, channelName, importance).apply {
            description = "Message Forwarder 通知渠道: $channelName"
            enableLights(true)
            enableVibration(true)
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

            // 计算 tag: 支持模板，默认值为 channel:name 的 MD5 前12位
            val tag = notifyOptions.tag?.let { expandTemplate(it, item, channelId) }
                ?: generateDefaultTag(channelId)

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
                LogManager.logDebug("NOTIFY", "sending - tag=$tag, id=$id, title=$title, hasIcon=${iconUrl != null}, popup=$popup")
            }

            // 异步获取图标并发送通知
            scope.launch {
                try {
                    val iconBitmap = iconCacheManager.getIcon(iconUrl, fixedIconId)
                    showNotification(channelId, tag, id, title, content, iconBitmap, popup, persistent)
                    lastNotify[name] = text to now
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
     * 生成默认 tag: channel:name 的 MD5 前12位
     */
    private fun generateDefaultTag(channelId: String): String {
        val input = "$channelId:$name"
        return md5(input).take(12)
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
     * 展开模板字符串
     *
     * 支持的变量:
     * - {channel} - 输出配置的 channel
     * - {name} - 输出配置的 name
     * - {seq} - 当前序列号 (0-999)
     * - {timestamp} - 秒级时间戳 (10位)
     * - {unix} - 毫秒级时间戳 (13位)
     * - {date:format} - 格式化日期，如 {date:yyyyMMddHHmmss}
     * - {data} - 原始数据内容 (UTF-8 解码)
     * - {data.path} - JSON 路径提取 (当 data 为 JSON 时)
     * - {meta.key} - metadata 字段
     */
    fun expandTemplate(template: String, item: QueueItem, channelId: String): String {
        // 检测是否已经是 MD5 格式（直接返回，不重复处理）
        if (MD5_HEX_REGEX.matcher(template).matches()) {
            return template
        }

        val dataStr = String(item.data, Charsets.UTF_8)
        val sdfCache = mutableMapOf<String, SimpleDateFormat>()

        return template.replace(TEMPLATE_REGEX.toRegex()) { match ->
            val key = match.groupValues[1]
            when {
                key == "channel" -> channelId
                key == "name" -> name
                key == "seq" -> (sequence.get() % 1000).toString()
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
     * 计算 MD5 哈希 (返回32位十六进制小写字符串)
     */
    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
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
        item.metadata["notify_id"]?.let { options.id = it }

        // 尝试解析 data 中的 JSON 对象 (次优先级)
        try {
            val dataStr = item.text
            if (dataStr.startsWith("{")) {
                val json = JSONObject(dataStr)
                if (json.has("title") && options.title == null) options.title = json.getString("title")
                if (json.has("message") && options.message == null) options.message = json.getString("message")
                if (json.has("icon") && options.icon == null) options.icon = json.getString("icon")
                if (json.has("fixedIcon") && options.fixedIcon == null) options.fixedIcon = json.getString("fixedIcon")
                if (json.has("popup") && options.popup == null) options.popup = json.getBoolean("popup")
                if (json.has("persistent") && options.persistent == null) options.persistent = json.getBoolean("persistent")
                if (json.has("tag") && options.tag == null) options.tag = json.getString("tag")
                if (json.has("id") && options.id == null) options.id = json.getString("id")
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

        // 创建点击意图
        val intent = Intent(context, ForwardService::class.java).apply {
            action = "OPEN_NOTIFICATION"
            putExtra("output_name", name)
            putExtra("notification_tag", tag)
            putExtra("notification_id", id)
        }
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getService(context, id, intent, pendingIntentFlags)

        // 构建通知
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(iconBitmap?.let { android.R.drawable.ic_dialog_info } ?: android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

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
        NotificationManagerCompat.from(context).notify(tag, id, notification)
        LogManager.log("INTERNAL", "Notification sent: $title (tag=$tag, id=$id)")
    }
}
