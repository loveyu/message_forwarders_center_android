package info.loveyu.mfca.test

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import info.loveyu.mfca.plugin.PluginManager
import info.loveyu.mfca.util.HttpDownloader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy

enum class M2mFullStepStatus { IDLE, RUNNING, SUCCESS, FAILED }

data class M2mFullTestStep(
    val label: String,
    var status: M2mFullStepStatus = M2mFullStepStatus.IDLE
)

suspend fun runVpnTest(
    context: Context,
    coreUrl: String,
    proxyUrl: String?,
    configUrl: String,
    mixedPort: Int,
    testUrl: String,
    addLog: (String) -> Unit,
    setStep: (Int, M2mFullStepStatus) -> Unit,
) {
    val workDir = File(context.filesDir, "m2m_vpn_test").apply { mkdirs() }
    var vpnStarted = false

    try {
        setStep(0, M2mFullStepStatus.SUCCESS)
        addLog("m2m 权限已就绪")

        setStep(1, M2mFullStepStatus.RUNNING)
        val soPath =
            try {
                withContext(Dispatchers.IO) {
                    addLog("正在检查核心插件…")
                    val installed = PluginManager.isInstalledFrom(context, "m2m", coreUrl)
                    if (installed) {
                        addLog("核心插件已缓存，跳过下载")
                    } else {
                        addLog("正在下载核心插件: $coreUrl")
                        if (!proxyUrl.isNullOrBlank()) addLog("使用代理: $proxyUrl")
                        PluginManager.installPlugin(context, "m2m", coreUrl, proxyUrl)
                        addLog("核心插件下载完成")
                    }
                    val path = PluginManager.getInstalledPath(context, "m2m")
                    addLog(
                        "插件文件大小: ${path.length()} bytes, ABI: ${PluginManager.deviceAbi}"
                    )
                    path.absolutePath
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                addLog("核心插件获取失败: ${e.message}")
                setStep(1, M2mFullStepStatus.FAILED)
                return
            }
        setStep(1, M2mFullStepStatus.SUCCESS)

        setStep(2, M2mFullStepStatus.RUNNING)
        val configFile =
            try {
                withContext(Dispatchers.IO) {
                    downloadConfigFile(context, configUrl, proxyUrl, workDir, addLog)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                addLog("配置文件获取失败: ${e.message}")
                setStep(2, M2mFullStepStatus.FAILED)
                return
            }
        setStep(2, M2mFullStepStatus.SUCCESS)

        setStep(3, M2mFullStepStatus.RUNNING)
        try {
            withContext(Dispatchers.IO) {
                val original = configFile.readText()
                val modified = overrideMixedPort(original, mixedPort)
                configFile.writeText(modified)
                addLog("混合端口已覆盖为: $mixedPort")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            addLog("修改混合端口失败: ${e.message}")
            setStep(3, M2mFullStepStatus.FAILED)
            return
        }
        setStep(3, M2mFullStepStatus.SUCCESS)

        setStep(4, M2mFullStepStatus.RUNNING)
        try {
            startVpnService(
                context, soPath, configFile.absolutePath, mixedPort, includeSelf = false
            )
            vpnStarted = true
            addLog("m2m 服务已启动（排除本应用模式）")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            addLog("m2m 服务启动失败: ${e.message}")
            setStep(4, M2mFullStepStatus.FAILED)
            return
        }
        setStep(4, M2mFullStepStatus.SUCCESS)

        setStep(5, M2mFullStepStatus.RUNNING)
        try {
            val vpnReady = CompletableDeferred<Unit>()
            M2mFullTestService.callback = { event ->
                when (event) {
                    is M2mFullTestService.Event.Log -> addLog(event.message)
                    is M2mFullTestService.Event.Ready -> vpnReady.complete(Unit)
                    is M2mFullTestService.Event.Error -> {
                        if (!vpnReady.isCompleted) {
                            vpnReady.completeExceptionally(Exception(event.message))
                        }
                    }
                }
            }
            withTimeout(30_000) { vpnReady.await() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            addLog("m2m 就绪失败: ${e.message}")
            setStep(5, M2mFullStepStatus.FAILED)
            return
        }
        setStep(5, M2mFullStepStatus.SUCCESS)

        setStep(6, M2mFullStepStatus.RUNNING)
        try {
            withContext(Dispatchers.IO) {
                addLog("通过代理访问: $testUrl")
                val proxy =
                    Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", mixedPort))
                val response =
                    HttpDownloader.openResponse(
                        testUrl,
                        HttpDownloader.Config(
                            connectTimeoutMs = 10_000L,
                            readTimeoutMs = 15_000L,
                            proxy = proxy,
                            tag = "M2M_FULL_TEST",
                        ),
                    )
                try {
                    val code = response.code
                    val body = response.body?.string() ?: ""
                    val bodyPreview = body.take(200)
                    addLog(
                        "响应状态: $code, Content-Type: ${response.header("Content-Type") ?: "未知"}"
                    )
                    addLog("响应大小: ${body.length} 字符")
                    if (bodyPreview.isNotBlank()) addLog("内容预览: $bodyPreview")
                    if (code !in 200..399) error("非成功状态码: $code")
                    if (body.isBlank()) error("响应体为空")
                    addLog("代理访问测试通过")
                } finally {
                    response.close()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            addLog("代理访问测试失败: ${e.message}")
            setStep(6, M2mFullStepStatus.FAILED)
            return
        }
        setStep(6, M2mFullStepStatus.SUCCESS)

        setStep(7, M2mFullStepStatus.RUNNING)
        try {
            stopVpnService(context)
            vpnStarted = false
            addLog("已停止排除模式 m2m，正在重建（仅本应用模式）…")
            delay(1000)
            startVpnService(
                context, soPath, configFile.absolutePath, mixedPort, includeSelf = true
            )
            vpnStarted = true
            addLog("m2m 服务已启动（仅本应用模式）")
            val vpnReady2 = CompletableDeferred<Unit>()
            M2mFullTestService.callback = { event ->
                when (event) {
                    is M2mFullTestService.Event.Log -> addLog(event.message)
                    is M2mFullTestService.Event.Ready -> vpnReady2.complete(Unit)
                    is M2mFullTestService.Event.Error -> {
                        if (!vpnReady2.isCompleted) {
                            vpnReady2.completeExceptionally(Exception(event.message))
                        }
                    }
                }
            }
            withTimeout(30_000) { vpnReady2.await() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            addLog("m2m 重建失败: ${e.message}")
            setStep(7, M2mFullStepStatus.FAILED)
            return
        }
        setStep(7, M2mFullStepStatus.SUCCESS)

        setStep(8, M2mFullStepStatus.RUNNING)
        try {
            withContext(Dispatchers.IO) {
                addLog("通过 m2m 直接访问: $testUrl")
                val response =
                    HttpDownloader.openResponse(
                        testUrl,
                        HttpDownloader.Config(
                            connectTimeoutMs = 10_000L,
                            readTimeoutMs = 15_000L,
                            tag = "M2M_FULL_TEST",
                        ),
                    )
                try {
                    val code = response.code
                    val body = response.body?.string() ?: ""
                    val bodyPreview = body.take(200)
                    addLog(
                        "响应状态: $code, Content-Type: ${response.header("Content-Type") ?: "未知"}"
                    )
                    addLog("响应大小: ${body.length} 字符")
                    if (bodyPreview.isNotBlank()) addLog("内容预览: $bodyPreview")
                    if (code !in 200..399) error("非成功状态码: $code")
                    if (body.isBlank()) error("响应体为空")
                    addLog("m2m 直接访问测试通过")
                } finally {
                    response.close()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = e.message ?: ""
            val reason =
                when {
                    msg.contains("resolve host", ignoreCase = true) ->
                        "DNS 解析失败（路由环路: m2m 出站 DNS 也经 VPN 回环）"
                    msg.contains("timeout", ignoreCase = true) ->
                        "连接超时（路由环路: m2m 出站经 VPN 回环）"
                    msg.contains("Connection refused", ignoreCase = true) -> "连接被拒绝"
                    else -> msg
                }
            addLog("m2m 直接访问失败: $reason")
            setStep(8, M2mFullStepStatus.FAILED)
            return
        }
        setStep(8, M2mFullStepStatus.SUCCESS)
    } finally {
        if (vpnStarted) {
            setStep(9, M2mFullStepStatus.RUNNING)
            stopVpnService(context)
            addLog("m2m 已停止，资源已清理")
            setStep(9, M2mFullStepStatus.SUCCESS)
        } else {
            setStep(9, M2mFullStepStatus.SUCCESS)
        }
    }
}

private fun startVpnService(
    context: Context,
    pluginPath: String,
    configPath: String,
    mixedPort: Int,
    includeSelf: Boolean,
) {
    val intent = Intent(context, M2mFullTestService::class.java).apply {
        action = M2mFullTestService.ACTION_START
        putExtra(M2mFullTestService.EXTRA_PLUGIN_PATH, pluginPath)
        putExtra(M2mFullTestService.EXTRA_CONFIG_PATH, configPath)
        putExtra(M2mFullTestService.EXTRA_MIXED_PORT, mixedPort)
        putExtra(M2mFullTestService.EXTRA_INCLUDE_SELF, includeSelf)
    }
    ContextCompat.startForegroundService(context, intent)
}

fun stopVpnService(context: Context) {
    val intent = Intent(context, M2mFullTestService::class.java).apply {
        action = M2mFullTestService.ACTION_STOP
    }
    ContextCompat.startForegroundService(context, intent)
    M2mFullTestService.callback = null
}
