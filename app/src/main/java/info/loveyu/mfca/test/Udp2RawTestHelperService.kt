package info.loveyu.mfca.test

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import info.loveyu.mfca.input.udp2raw.Udp2RawPluginCore
import java.io.File
import java.io.RandomAccessFile
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Bound service that runs in process `:udp2rawtest` (isolated from main process so it can host
 * a second JNI instance of libudp2raw_plugin.so simultaneously with the client-side instance
 * running in the main process).
 *
 * Responsibilities:
 *  1. Load the plugin .so and start udp2raw in **server** mode.
 *  2. Host a plain UDP echo server that the udp2raw server tunnels packets to.
 *  3. Forward log lines back to the Activity via the reply Messenger.
 */
class Udp2RawTestHelperService : Service() {

    companion object {
        /** Activity → Service: start server. data: plugin_path, echo_port, raw_port, tunnel_key */
        const val MSG_START = 1

        /** Activity → Service: stop everything. */
        const val MSG_STOP = 2

        /** Service → Activity: a log line. data: "text" */
        const val MSG_LOG = 100

        /** Service → Activity: server is ready, activity may start client. */
        const val MSG_SERVER_READY = 101

        /** Service → Activity: a fatal error occurred. data: "error" */
        const val MSG_ERROR = 102
    }

    private val plugin = Udp2RawPluginCore()
    private var echoSocket: DatagramSocket? = null
    private var echoThread: Thread? = null
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
        val echoPort = data.getInt("echo_port", Udp2RawTestActivity.PORT_ECHO)
        val rawPort = data.getInt("raw_port", Udp2RawTestActivity.PORT_SERVER_RAW)
        val tunnelKey = data.getString("tunnel_key", Udp2RawTestActivity.TUNNEL_KEY)
        val logFilePath = data.getString("log_file")
        val logFile =
            logFilePath?.let { path ->
                val f = File(path)
                f.parentFile?.mkdirs()
                if (!f.exists()) f.createNewFile()
                f
            }
        val rawMode = data.getString("raw_mode", "faketcp")

        Thread(
                {
                    try {
                        // 1. Load plugin
                        sendLog("【服务端】正在加载插件…")
                        plugin.load(pluginPath)
                        sendLog("【服务端】插件加载成功，版本: ${plugin.version() ?: "unknown"}")

                        // 2. Start UDP echo server
                        sendLog("【服务端】启动 UDP Echo 服务，端口 $echoPort…")
                        val socket = DatagramSocket(echoPort, InetAddress.getByName("127.0.0.1"))
                        echoSocket = socket
                        echoThread =
                            Thread(
                                    {
                                        val buf = ByteArray(4096)
                                        while (!socket.isClosed) {
                                            try {
                                                val pkt = DatagramPacket(buf, buf.size)
                                                socket.receive(pkt)
                                                val reply =
                                                    DatagramPacket(
                                                        pkt.data,
                                                        pkt.length,
                                                        pkt.address,
                                                        pkt.port,
                                                    )
                                                socket.send(reply)
                                            } catch (_: Exception) {
                                                if (!socket.isClosed) {
                                                    sendLog("【服务端】Echo 服务异常，等待…")
                                                }
                                            }
                                        }
                                    },
                                    "udp2raw-echo-server",
                                )
                                .apply {
                                    isDaemon = true
                                    start()
                                }
                        sendLog("【服务端】UDP Echo 服务已启动")

                        // 3. Start udp2raw server mode
                        sendLog("【服务端】启动 udp2raw 服务端 (原始端口 $rawPort → Echo $echoPort, mode=$rawMode)…")
                        val args =
                            listOf(
                                "-s",
                                "-l0.0.0.0:$rawPort",
                                "-r127.0.0.1:$echoPort",
                                "--raw-mode",
                                rawMode,
                                "-k",
                                tunnelKey,
                            )
                        val ret = plugin.start(args, logFile = logFile?.absolutePath)
                        if (ret != 0) {
                            sendError("udp2raw 服务端启动失败 (code $ret)")
                            return@Thread
                        }

                        // 4. Wait for init
                        Thread.sleep(2500)
                        if (!plugin.isRunning()) {
                            sendError("udp2raw 服务端启动后立即退出（可能缺少 CAP_NET_RAW 权限）")
                            return@Thread
                        }
                        sendLog("【服务端】udp2raw 服务端运行中")

                        // 4.5 Start log tailer
                        logFile?.let { startLogTailer(it) }

                        // 5. Notify activity it may start client
                        sendReady()
                    } catch (e: Throwable) {
                        sendError(
                            "服务端初始化失败: ${e.javaClass.simpleName}: ${e.message}" +
                                e.stackTraceToString().lines().take(5).joinToString("\n")
                        )
                    }
                },
                "udp2raw-test-server-init",
            )
            .apply {
                isDaemon = true
                start()
            }
    }

    // ── stop ─────────────────────────────────────────────────────────────────

    private fun handleStop() {
        sendLog("【服务端】停止中…")
        logTailer?.interrupt()
        logTailer = null
        try {
            plugin.stop()
        } catch (_: Exception) {}
        try {
            echoSocket?.close()
            echoSocket = null
        } catch (_: Exception) {}
        sendLog("【服务端】已停止")
    }

    override fun onDestroy() {
        super.onDestroy()
        logTailer?.interrupt()
        logTailer = null
        try {
            plugin.stop()
        } catch (_: Exception) {}
        try {
            echoSocket?.close()
        } catch (_: Exception) {}
        // Kill the isolated process to ensure clean native state for the next test.
        // The .so has global C state that cannot be reset by stop() alone.
        System.exit(0)
    }

    // ── Log tailer ──────────────────────────────────────────────────────────

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
                    "udp2raw-server-log-tailer",
                )
                .apply {
                    isDaemon = true
                    start()
                }
    }

    // ── IPC helpers ───────────────────────────────────────────────────────────

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
            replyTo?.send(Message.obtain(null, MSG_SERVER_READY))
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
