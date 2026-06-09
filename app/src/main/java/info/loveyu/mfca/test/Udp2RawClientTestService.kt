package info.loveyu.mfca.test

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import info.loveyu.mfca.plugin.Udp2RawPluginCore
import java.io.File
import java.io.RandomAccessFile

class Udp2RawClientTestService : Service() {

    companion object {
        const val MSG_START = 1
        const val MSG_STOP = 2
        const val MSG_LOG = 100
        const val MSG_CLIENT_READY = 101
        const val MSG_ERROR = 102
    }

    private val plugin = Udp2RawPluginCore()
    private var logTailer: Thread? = null
    private var replyTo: Messenger? = null

    private val incomingHandler =
        object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(msg: Message) {
                when (msg.what) {
                    MSG_START -> {
                        replyTo = msg.replyTo
                        handleStart(msg.data)
                    }
                    MSG_STOP -> handleStop()
                }
            }
        }

    private val serviceMessenger = Messenger(incomingHandler)

    override fun onBind(intent: Intent?): IBinder = serviceMessenger.binder

    // ── start ────────────────────────────────────────────────────────────────

    private fun handleStart(data: Bundle) {
        val pluginPath = data.getString("plugin_path") ?: return sendError("缺少 plugin_path 参数")
        val logFilePath = data.getString("log_file")
        val rawMode = data.getString("raw_mode", "faketcp")
        val clientPort = data.getInt("client_port", Udp2RawTestActivity.PORT_CLIENT_UDP)
        val serverPort = data.getInt("server_port", Udp2RawTestActivity.PORT_SERVER_RAW)
        val tunnelKey = data.getString("tunnel_key", Udp2RawTestActivity.TUNNEL_KEY)

        val logFile =
            logFilePath?.let { path ->
                val f = File(path)
                f.parentFile?.mkdirs()
                if (!f.exists()) f.createNewFile()
                f
            }

        Thread(
                {
                    try {
                        sendLog("【客户端】正在加载插件…")
                        plugin.load(pluginPath)
                        sendLog("【客户端】插件加载成功，版本: ${plugin.version() ?: "unknown"}")
                        logFile?.let { sendLog("【客户端】日志文件: ${it.absolutePath}") }

                        val clientArgs =
                            listOf(
                                "-c",
                                "-l127.0.0.1:$clientPort",
                                "-r127.0.0.1:$serverPort",
                                "--raw-mode",
                                rawMode,
                                "-k",
                                tunnelKey,
                            )
                        sendLog("【客户端】参数: ${clientArgs.joinToString(" ")}")
                        val ret = plugin.start(clientArgs, logFile = logFile?.absolutePath)
                        if (ret != 0) {
                            sendError("udp2raw 客户端启动失败 (code $ret)")
                            return@Thread
                        }

                        logFile?.let { startLogTailer(it) }

                        Thread.sleep(2500)
                        if (!plugin.isRunning()) {
                            sendError("udp2raw 客户端启动后立即退出（可能缺少 CAP_NET_RAW 权限）")
                            return@Thread
                        }
                        sendLog("【客户端】udp2raw 客户端运行中")
                        sendReady()
                    } catch (e: Throwable) {
                        sendError(
                            "客户端初始化失败: ${e.javaClass.simpleName}: ${e.message}" +
                                e.stackTraceToString().lines().take(5).joinToString("\n")
                        )
                    }
                },
                "udp2raw-test-client-init",
            )
            .apply {
                isDaemon = true
                start()
            }
    }

    // ── stop ─────────────────────────────────────────────────────────────────

    private fun handleStop() {
        sendLog("【客户端】停止中…")
        logTailer?.interrupt()
        logTailer = null
        try {
            plugin.stop()
        } catch (_: Exception) {}
        sendLog("【客户端】已停止")
    }

    override fun onDestroy() {
        super.onDestroy()
        logTailer?.interrupt()
        logTailer = null
        try {
            plugin.stop()
        } catch (_: Exception) {}
        System.exit(0)
    }

    // ── Log tailer ───────────────────────────────────────────────────────────

    private fun startLogTailer(logFile: File) {
        logTailer =
            Thread(
                    {
                        try {
                            var position = 0L
                            while (!Thread.currentThread().isInterrupted()) {
                                if (logFile.exists()) {
                                    val len = logFile.length()
                                    if (len > position) {
                                        try {
                                            RandomAccessFile(logFile, "r").use { raf ->
                                                raf.seek(position)
                                                val bytes = ByteArray((len - position).toInt())
                                                raf.readFully(bytes)
                                                position = len
                                                String(bytes)
                                                    .lines()
                                                    .forEach { line ->
                                                        if (line.isNotBlank()) sendLog(line)
                                                    }
                                            }
                                        } catch (_: Exception) {}
                                    }
                                }
                                Thread.sleep(300)
                            }
                        } catch (_: InterruptedException) {}
                    },
                    "udp2raw-client-log-tailer",
                )
                .apply {
                    isDaemon = true
                    start()
                }
    }

    // ── IPC helpers ──────────────────────────────────────────────────────────

    private fun sendLog(text: String) {
        try {
            replyTo?.send(
                Message.obtain(null, MSG_LOG).apply {
                    data = Bundle().apply { putString("text", text) }
                }
            )
        } catch (_: Exception) {}
    }

    private fun sendReady() {
        try {
            replyTo?.send(Message.obtain(null, MSG_CLIENT_READY))
        } catch (_: Exception) {}
    }

    private fun sendError(error: String) {
        try {
            replyTo?.send(
                Message.obtain(null, MSG_ERROR).apply {
                    data = Bundle().apply { putString("error", error) }
                }
            )
        } catch (_: Exception) {}
    }
}
