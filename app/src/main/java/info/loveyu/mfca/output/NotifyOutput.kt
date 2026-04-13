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

            // 解析通知选项
            val notifyOptions = parseNotifyOptions(item)
            val title = notifyOptions.title ?: "新消息"
            val content = notifyOptions.message ?: text
            val iconUrl = notifyOptions.icon
            val fixedIconId = notifyOptions.fixedIcon
            val popup = notifyOptions.popup
            val persistent = notifyOptions.persistent
            val tag = notifyOptions.tag ?: name

            // 去重检查
            val lastEntry = lastNotify[name]
            if (lastEntry != null && lastEntry.first == text && now - lastEntry.second < DEDUP_INTERVAL_MS) {
                LogManager.log("INTERNAL", "Notify skipped (duplicated): $name")
                callback?.invoke(true)
                return
            }

            // 异步获取图标并发送通知
            scope.launch {
                try {
                    val iconBitmap = iconCacheManager.getIcon(iconUrl, fixedIconId)
                    showNotification(channelId, tag, title, content, iconBitmap, popup, persistent)
                    lastNotify[name] = text to now
                    callback?.invoke(true)
                } catch (e: Exception) {
                    LogManager.log("INTERNAL", "Notify failed: ${e.message}")
                    callback?.invoke(false)
                }
            }
        } catch (e: Exception) {
            LogManager.log("INTERNAL", "Notify error: ${e.message}")
            callback?.invoke(false)
        }
    }

    /**
     * 解析通知选项
     * 支持从 item.metadata 或 item.data 中获取 JSON 配置
     */
    private fun parseNotifyOptions(item: QueueItem): NotifyOptions {
        val options = NotifyOptions()

        // 先尝试从 metadata 获取
        item.metadata["notify_title"]?.let { options.title = it }
        item.metadata["notify_icon"]?.let { options.icon = it }
        item.metadata["notify_fixed_icon"]?.let { options.fixedIcon = it }
        item.metadata["notify_popup"]?.let { options.popup = it.toBoolean() }
        item.metadata["notify_persistent"]?.let { options.persistent = it.toBoolean() }
        item.metadata["notify_tag"]?.let { options.tag = it }

        // 尝试解析 data 中的 JSON 对象
        try {
            val dataStr = item.text
            if (dataStr.startsWith("{")) {
                val json = JSONObject(dataStr)
                if (json.has("title")) options.title = json.getString("title")
                if (json.has("message")) options.message = json.getString("message")
                if (json.has("icon")) options.icon = json.getString("icon")
                if (json.has("fixedIcon")) options.fixedIcon = json.getString("fixedIcon")
                if (json.has("popup")) options.popup = json.getBoolean("popup")
                if (json.has("persistent")) options.persistent = json.getBoolean("persistent")
                if (json.has("tag")) options.tag = json.getString("tag")
            }
        } catch (e: Exception) {
            // 不是 JSON 格式，忽略
        }

        // 应用配置中的默认值（如果有）
        config.options?.let { configOptions ->
            if (options.title == null) configOptions["title"]?.toString()?.let { options.title = it }
            if (options.icon == null) configOptions["icon"]?.toString()?.let { options.icon = it }
            if (options.fixedIcon == null) configOptions["fixedIcon"]?.toString()?.let { options.fixedIcon = it }
            if (options.popup == null) configOptions["popup"]?.let { options.popup = it as? Boolean ?: it.toString().toBoolean() }
            if (options.persistent == null) configOptions["persistent"]?.let { options.persistent = it as? Boolean ?: it.toString().toBoolean() }
            if (options.tag == null) configOptions["tag"]?.toString()?.let { options.tag = it }
        }

        return options
    }

    /**
     * 显示通知
     */
    private fun showNotification(
        channelId: String,
        tag: String,
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
                LogManager.log("INTERNAL", "Notification permission not granted")
                return
            }
        }

        // 创建点击意图
        val intent = Intent(context, ForwardService::class.java).apply {
            action = "OPEN_NOTIFICATION"
            putExtra("output_name", name)
            putExtra("notification_tag", tag)
        }
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getService(context, name.hashCode(), intent, pendingIntentFlags)

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
        NotificationManagerCompat.from(context).notify(tag, name.hashCode(), notification)
        LogManager.log("INTERNAL", "Notification sent: $title")
    }
}

