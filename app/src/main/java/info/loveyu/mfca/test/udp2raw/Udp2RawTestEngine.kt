package info.loveyu.mfca.test.udp2raw

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

enum class StepStatus { IDLE, RUNNING, SUCCESS, FAILED }

data class TestStep(val label: String, var status: StepStatus = StepStatus.IDLE)

suspend fun runUdp2RawTest(
    context: Context,
    pluginUrl: String,
    proxyUrl: String?,
    rawMode: String,
    addLog: (String) -> Unit,
    setStep: (Int, StepStatus) -> Unit,
) {
    var serverConn: ServiceConnection? = null
    var serverMessenger: Messenger? = null
    var clientServiceConn: ServiceConnection? = null
    var clientServiceMessenger: Messenger? = null
    var serverBound = false
    var clientBound = false
    var cleanedUp = false

    val testLogDir =
        File(context.getExternalFilesDir(null), "udp2raw_test_logs").also { it.mkdirs() }
            ?: File(context.cacheDir, "udp2raw_test_logs").also { it.mkdirs() }
    testLogDir.listFiles()?.forEach { it.delete() }
    val serverLogFile = File(testLogDir, "server.log")
    val clientLogFile = File(testLogDir, "client.log")

    try {
        setStep(0, StepStatus.RUNNING)
        val soPath =
            try {
                withContext(Dispatchers.IO) {
                    addLog("正在检查插件…")
                    val installed =
                        info.loveyu.mfca.plugin.PluginManager.isInstalledFrom(
                            context, "udp2raw", pluginUrl
                        )
                    if (installed) {
                        addLog("插件已缓存，跳过下载")
                    } else {
                        addLog("正在下载插件: $pluginUrl")
                        if (!proxyUrl.isNullOrBlank()) addLog("使用代理: $proxyUrl")
                        info.loveyu.mfca.plugin.PluginManager.installPlugin(
                            context, "udp2raw", pluginUrl, proxyUrl
                        )
                        addLog("插件下载完成")
                    }
                    val path =
                        info.loveyu.mfca.plugin.PluginManager.getInstalledPath(context, "udp2raw")
                    addLog(
                        "插件文件大小: ${path.length()} bytes, ABI: ${info.loveyu.mfca.plugin.PluginManager.deviceAbi}"
                    )
                    path.absolutePath
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                addLog("❌ 插件获取失败: ${e.message}")
                setStep(0, StepStatus.FAILED)
                return
            }
        addLog("插件路径: $soPath")
        setStep(0, StepStatus.SUCCESS)

        setStep(1, StepStatus.RUNNING)
        try {
            withContext(Dispatchers.IO) {
                addLog("【直连】启动 UDP Echo 服务，端口 ${Udp2RawTestActivity.PORT_ECHO}…")
                val echoSocket =
                    DatagramSocket(
                        Udp2RawTestActivity.PORT_ECHO,
                        InetAddress.getByName("127.0.0.1"),
                    )
                val echoThread =
                    Thread(
                        {
                            val buf = ByteArray(4096)
                            repeat(3) {
                                try {
                                    val pkt = DatagramPacket(buf, buf.size)
                                    echoSocket.receive(pkt)
                                    echoSocket.send(
                                        DatagramPacket(
                                            pkt.data, pkt.length, pkt.address, pkt.port
                                        )
                                    )
                                } catch (_: Exception) {}
                            }
                        },
                        "direct-echo",
                    ).apply {
                        isDaemon = true
                        start()
                    }
                addLog("【直连】Echo 服务已启动")

                val socket = DatagramSocket()
                socket.soTimeout = 3_000
                val addr = InetAddress.getByName("127.0.0.1")
                var directOk = true
                repeat(3) { i ->
                    val msg = "direct-udp-$i"
                    val msgBytes = msg.toByteArray()
                    socket.send(
                        DatagramPacket(
                            msgBytes, msgBytes.size, addr, Udp2RawTestActivity.PORT_ECHO
                        )
                    )
                    addLog("【直连】→ 发送: $msg")
                    try {
                        val recvBuf = ByteArray(256)
                        val recvPkt = DatagramPacket(recvBuf, recvBuf.size)
                        socket.receive(recvPkt)
                        val reply = String(recvPkt.data, 0, recvPkt.length)
                        val ok = reply == msg
                        addLog("【直连】← 收到: $reply ${if (ok) "✓" else "✗"}")
                        if (!ok) directOk = false
                    } catch (e: Exception) {
                        addLog("【直连】← 超时: ${e.message}")
                        directOk = false
                    }
                }
                socket.close()
                echoThread.join(1000)
                echoSocket.close()
                if (!directOk) error("Java 直连 UDP 测试失败")
                addLog("【直连】测试通过")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            addLog("❌ ${e.message}")
            setStep(1, StepStatus.FAILED)
            return
        }
        setStep(1, StepStatus.SUCCESS)

        setStep(2, StepStatus.RUNNING)
        val serverReadyDeferred = CompletableDeferred<Unit>()
        val incomingHandler =
            object : Handler(Looper.getMainLooper()) {
                override fun handleMessage(msg: Message) {
                    when (msg.what) {
                        Udp2RawTestHelperService.MSG_LOG ->
                            addLog(msg.data.getString("text", ""))
                        Udp2RawTestHelperService.MSG_SERVER_READY ->
                            serverReadyDeferred.complete(Unit)
                        Udp2RawTestHelperService.MSG_ERROR -> {
                            val err = msg.data.getString("error", "未知错误")
                            if (!serverReadyDeferred.isCompleted) {
                                serverReadyDeferred.completeExceptionally(Exception(err))
                            }
                        }
                    }
                }
            }
        val activityMessenger = Messenger(incomingHandler)
        val bindDeferred = CompletableDeferred<Messenger>()
        val conn =
            object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    bindDeferred.complete(Messenger(binder))
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    if (!serverReadyDeferred.isCompleted) {
                        serverReadyDeferred.completeExceptionally(
                            Exception("测试服务进程异常退出（可能因 native crash）")
                        )
                    }
                }
            }
        serverConn = conn
        withContext(Dispatchers.Main) {
            val intent = Intent(context, Udp2RawTestHelperService::class.java)
            context.bindService(intent, conn, Context.BIND_AUTO_CREATE)
        }
        serverBound = true
        serverMessenger =
            try {
                withTimeout(8_000) { bindDeferred.await() }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                addLog("❌ 绑定测试服务超时")
                setStep(2, StepStatus.FAILED)
                return
            }
        setStep(2, StepStatus.SUCCESS)

        setStep(3, StepStatus.RUNNING)
        addLog(
            "【服务端】参数: -s -l0.0.0.0:${Udp2RawTestActivity.PORT_SERVER_RAW} " +
                "-r127.0.0.1:${Udp2RawTestActivity.PORT_ECHO} --raw-mode $rawMode " +
                "-k ${Udp2RawTestActivity.TUNNEL_KEY}"
        )
        addLog("【服务端】日志文件: ${serverLogFile.absolutePath}")
        val startMsg =
            Message.obtain(null, Udp2RawTestHelperService.MSG_START).apply {
                replyTo = activityMessenger
                data =
                    Bundle().apply {
                        putString("plugin_path", soPath)
                        putInt("echo_port", Udp2RawTestActivity.PORT_ECHO)
                        putInt("raw_port", Udp2RawTestActivity.PORT_SERVER_RAW)
                        putString("tunnel_key", Udp2RawTestActivity.TUNNEL_KEY)
                        putString("log_file", serverLogFile.absolutePath)
                        putString("raw_mode", rawMode)
                    }
            }
        serverMessenger.send(startMsg)
        try {
            withTimeout(30_000) { serverReadyDeferred.await() }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            addLog("❌ 服务端启动超时（可能缺少 CAP_NET_RAW / root 权限）")
            dumpLogFile(serverLogFile, "服务端", addLog)
            captureNativeCrashLogs(addLog)
            setStep(3, StepStatus.FAILED)
            return
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            addLog("❌ 服务端错误: ${e.message}")
            dumpLogFile(serverLogFile, "服务端", addLog)
            captureNativeCrashLogs(addLog)
            setStep(3, StepStatus.FAILED)
            return
        }
        setStep(3, StepStatus.SUCCESS)

        setStep(4, StepStatus.RUNNING)
        val clientReadyDeferred = CompletableDeferred<Unit>()
        val clientIncomingHandler =
            object : Handler(Looper.getMainLooper()) {
                override fun handleMessage(msg: Message) {
                    when (msg.what) {
                        Udp2RawClientTestService.MSG_LOG ->
                            addLog(msg.data.getString("text", ""))
                        Udp2RawClientTestService.MSG_CLIENT_READY ->
                            clientReadyDeferred.complete(Unit)
                        Udp2RawClientTestService.MSG_ERROR -> {
                            val err = msg.data.getString("error", "未知错误")
                            if (!clientReadyDeferred.isCompleted) {
                                clientReadyDeferred.completeExceptionally(Exception(err))
                            }
                        }
                    }
                }
            }
        val clientActivityMessenger = Messenger(clientIncomingHandler)
        val clientBindDeferred = CompletableDeferred<Messenger>()
        val cConn =
            object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    clientBindDeferred.complete(Messenger(binder))
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    if (!clientReadyDeferred.isCompleted) {
                        clientReadyDeferred.completeExceptionally(
                            Exception("客户端服务进程异常退出")
                        )
                    }
                }
            }
        clientServiceConn = cConn
        withContext(Dispatchers.Main) {
            val intent = Intent(context, Udp2RawClientTestService::class.java)
            context.bindService(intent, cConn, Context.BIND_AUTO_CREATE)
        }
        clientBound = true
        clientServiceMessenger =
            try {
                withTimeout(8_000) { clientBindDeferred.await() }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                addLog("❌ 绑定客户端服务超时")
                setStep(4, StepStatus.FAILED)
                return
            }
        clientLogFile.parentFile?.mkdirs()
        if (!clientLogFile.exists()) clientLogFile.createNewFile()
        val clientStartMsg =
            Message.obtain(null, Udp2RawClientTestService.MSG_START).apply {
                replyTo = clientActivityMessenger
                data =
                    Bundle().apply {
                        putString("plugin_path", soPath)
                        putString("log_file", clientLogFile.absolutePath)
                        putString("raw_mode", rawMode)
                        putInt("client_port", Udp2RawTestActivity.PORT_CLIENT_UDP)
                        putInt("server_port", Udp2RawTestActivity.PORT_SERVER_RAW)
                        putString("tunnel_key", Udp2RawTestActivity.TUNNEL_KEY)
                    }
            }
        clientServiceMessenger.send(clientStartMsg)
        try {
            withTimeout(30_000) { clientReadyDeferred.await() }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            addLog("❌ 客户端启动超时")
            dumpLogFile(clientLogFile, "客户端", addLog)
            captureNativeCrashLogs(addLog)
            setStep(4, StepStatus.FAILED)
            return
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            addLog("❌ 客户端错误: ${e.message}")
            dumpLogFile(clientLogFile, "客户端", addLog)
            captureNativeCrashLogs(addLog)
            setStep(4, StepStatus.FAILED)
            return
        }
        setStep(4, StepStatus.SUCCESS)

        setStep(5, StepStatus.RUNNING)
        val echoResults = mutableListOf<Boolean>()
        try {
            withContext(Dispatchers.IO) {
                val socket = DatagramSocket()
                socket.soTimeout = 5_000
                addLog(
                    "【隧道】本地端口: ${socket.localPort}, 目标: 127.0.0.1:${Udp2RawTestActivity.PORT_CLIENT_UDP}"
                )
                val serverAddr = InetAddress.getByName("127.0.0.1")
                repeat(5) { i ->
                    val msg = "hello-flowgate-$i"
                    val sendBuf = msg.toByteArray()
                    val sendPkt =
                        DatagramPacket(
                            sendBuf, sendBuf.size, serverAddr,
                            Udp2RawTestActivity.PORT_CLIENT_UDP
                        )
                    socket.send(sendPkt)
                    addLog("→ 发送: $msg")
                    val recvBuf = ByteArray(256)
                    val recvPkt = DatagramPacket(recvBuf, recvBuf.size)
                    try {
                        socket.receive(recvPkt)
                        val reply = String(recvPkt.data, 0, recvPkt.length)
                        val ok = reply == msg
                        echoResults.add(ok)
                        addLog("← 收到: $reply ${if (ok) "✓" else "✗ (不匹配)"}")
                    } catch (e: Exception) {
                        echoResults.add(false)
                        addLog("← 超时或错误: ${e.message}")
                    }
                    Thread.sleep(500)
                }
                socket.close()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            addLog("❌ Echo 测试异常: ${e.message}")
            setStep(5, StepStatus.FAILED)
            return
        }
        setStep(5, StepStatus.SUCCESS)

        setStep(6, StepStatus.RUNNING)
        val passed = echoResults.count { it }
        val total = echoResults.size
        addLog("结果: $passed/$total 成功")
        if (passed == total) {
            addLog("✅ 所有 Echo 测试通过")
            setStep(6, StepStatus.SUCCESS)
        } else {
            addLog("❌ 部分 Echo 测试失败")
            dumpLogFile(serverLogFile, "服务端", addLog)
            dumpLogFile(clientLogFile, "客户端", addLog)
            setStep(6, StepStatus.FAILED)
        }

        setStep(7, StepStatus.RUNNING)
        doCleanup(
            serverMessenger, serverConn,
            clientServiceMessenger, clientServiceConn,
            context, serverBound, clientBound, addLog
        )
        cleanedUp = true
        setStep(7, StepStatus.SUCCESS)
    } finally {
        if (!cleanedUp) {
            doCleanup(
                serverMessenger, serverConn,
                clientServiceMessenger, clientServiceConn,
                context, serverBound, clientBound
            )
        }
    }
}

private fun dumpLogFile(logFile: File, tag: String, addLog: (String) -> Unit) {
    if (!logFile.exists()) return
    try {
        val content = logFile.readText()
        if (content.isBlank()) {
            addLog("【$tag】日志文件为空")
        } else {
            addLog("【$tag】--- 日志转储 ---")
            content.lines().forEach { line ->
                if (line.isNotBlank()) addLog("【$tag】$line")
            }
            addLog("【$tag】--- 日志结束 ---")
        }
    } catch (e: Exception) {
        addLog("【$tag】读取日志失败: ${e.message}")
    }
}

private fun doCleanup(
    serverMessenger: Messenger?,
    serverConn: ServiceConnection?,
    clientMessenger: Messenger?,
    clientConn: ServiceConnection?,
    context: Context,
    serverBound: Boolean,
    clientBound: Boolean,
    addLog: ((String) -> Unit)? = null,
) {
    try {
        clientMessenger?.send(Message.obtain(null, Udp2RawClientTestService.MSG_STOP))
    } catch (_: Exception) {}
    if (clientBound) {
        try {
            clientConn?.let { context.unbindService(it) }
        } catch (_: Exception) {}
        try {
            context.stopService(Intent(context, Udp2RawClientTestService::class.java))
        } catch (_: Exception) {}
    }
    try {
        serverMessenger?.send(Message.obtain(null, Udp2RawTestHelperService.MSG_STOP))
    } catch (_: Exception) {}
    if (serverBound) {
        try {
            serverConn?.let { context.unbindService(it) }
        } catch (_: Exception) {}
        try {
            context.stopService(Intent(context, Udp2RawTestHelperService::class.java))
        } catch (_: Exception) {}
    }
}

private fun captureNativeCrashLogs(addLog: (String) -> Unit) {
    try {
        val process =
            Runtime.getRuntime().exec("logcat -d -t 200 -s AndroidRuntime:E DEBUG:V")
        val output = process.inputStream.bufferedReader().readText().trim()
        if (output.isNotBlank()) {
            addLog("--- 原生日志 (logcat) ---")
            output.lines().take(50).forEach { line ->
                if (line.isNotBlank()) addLog(line)
            }
            addLog("--- 原生日志结束 ---")
        } else {
            addLog("原生日志 (logcat) 为空，无崩溃信息")
        }
    } catch (e: Exception) {
        addLog("捕获原生日志失败: ${e.message}")
    }
}
