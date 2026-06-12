package info.loveyu.mfca.test

import android.content.Context
import info.loveyu.mfca.plugin.M2mPluginCore
import info.loveyu.mfca.plugin.PluginManager
import info.loveyu.mfca.util.HttpDownloader
import info.loveyu.mfca.util.StoragePathResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket

enum class M2mStepStatus { IDLE, RUNNING, SUCCESS, FAILED }

data class M2mTestStep(val label: String, var status: M2mStepStatus = M2mStepStatus.IDLE)

suspend fun runM2mTest(
    context: Context,
    coreUrl: String,
    proxyUrl: String?,
    configUrl: String,
    mixedPort: Int,
    testUrl: String,
    insecure: Boolean,
    addLog: (String) -> Unit,
    setStep: (Int, M2mStepStatus) -> Unit,
) {
    val workDir = File(context.filesDir, "m2m_test").apply { mkdirs() }
    var core: M2mPluginCore? = null

    try {
        setStep(0, M2mStepStatus.RUNNING)
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
                setStep(0, M2mStepStatus.FAILED)
                return
            }
        addLog("插件路径: $soPath")
        setStep(0, M2mStepStatus.SUCCESS)

        setStep(1, M2mStepStatus.RUNNING)
        val configFile =
            try {
                withContext(Dispatchers.IO) {
                    downloadConfigFile(context, configUrl, proxyUrl, workDir, addLog, insecure)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                addLog("配置文件获取失败: ${e.message}")
                setStep(1, M2mStepStatus.FAILED)
                return
            }
        addLog("配置文件: ${configFile.absolutePath}")
        setStep(1, M2mStepStatus.SUCCESS)

        setStep(2, M2mStepStatus.RUNNING)
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
            setStep(2, M2mStepStatus.FAILED)
            return
        }
        setStep(2, M2mStepStatus.SUCCESS)

        setStep(3, M2mStepStatus.RUNNING)
        val logFile = File(workDir, "m2m.log")
        try {
            withContext(Dispatchers.IO) {
                logFile.writeText("")
                core = M2mPluginCore().also {
                    it.load(soPath)
                    addLog("核心版本: ${it.version() ?: "未知"}")
                    val args =
                        listOf("-d", workDir.absolutePath, "-f", configFile.absolutePath)
                    addLog("启动参数: ${args.joinToString(" ")}")
                    val ret = it.start(args, logFile.absolutePath)
                    if (ret != 0) {
                        val log = if (logFile.exists()) logFile.readText() else ""
                        error("启动失败 (code $ret): ${log.take(500).ifBlank { "无日志输出" }}")
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            addLog("启动代理失败: ${e.message}")
            dumpM2mLogFile(logFile, addLog)
            setStep(3, M2mStepStatus.FAILED)
            return
        }
        setStep(3, M2mStepStatus.SUCCESS)

        setStep(4, M2mStepStatus.RUNNING)
        try {
            withContext(Dispatchers.IO) {
                addLog("等待代理端口 $mixedPort 就绪…")
                val deadline = System.currentTimeMillis() + 15_000
                while (System.currentTimeMillis() < deadline) {
                    val c = core
                    if (c != null && !c.isRunning()) {
                        val log = if (logFile.exists()) logFile.readText() else ""
                        error(
                            "代理进程意外退出: ${log.takeLast(500).ifBlank { "无日志输出" }}"
                        )
                    }
                    if (canConnect(mixedPort)) {
                        addLog("代理端口 $mixedPort 已就绪")
                        return@withContext
                    }
                    Thread.sleep(300)
                }
                error("等待代理就绪超时 (15s)")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            addLog("代理就绪检查失败: ${e.message}")
            dumpM2mLogFile(logFile, addLog)
            setStep(4, M2mStepStatus.FAILED)
            return
        }
        setStep(4, M2mStepStatus.SUCCESS)

        setStep(5, M2mStepStatus.RUNNING)
        try {
            withContext(Dispatchers.IO) {
                addLog("通过代理访问: $testUrl")
                val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", mixedPort))
                val response =
                    HttpDownloader.openResponse(
                        testUrl,
                        HttpDownloader.Config(
                            connectTimeoutMs = 10_000L,
                            readTimeoutMs = 15_000L,
                            proxy = proxy,
                            tag = "M2M_TEST",
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
                    if (bodyPreview.isNotBlank()) {
                        addLog("内容预览: $bodyPreview")
                    }
                    if (code !in 200..399) {
                        error("非成功状态码: $code")
                    }
                    if (body.isBlank()) {
                        error("响应体为空")
                    }
                    val isHtml = body.trimStart().startsWith("<", ignoreCase = true)
                    val looksLikeContent = body.length > 100
                    if (!isHtml && !looksLikeContent) {
                        error("响应内容不像有效页面 (前缀: ${body.take(50)})")
                    }
                    addLog("访问测试通过")
                } finally {
                    response.close()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            addLog("访问测试失败: ${e.message}")
            setStep(5, M2mStepStatus.FAILED)
            return
        }
        setStep(5, M2mStepStatus.SUCCESS)

        setStep(6, M2mStepStatus.RUNNING)
        try {
            withContext(Dispatchers.IO) {
                core?.let { c ->
                    c.stop()
                    val deadline = System.currentTimeMillis() + 2_000
                    while (c.isRunning() && System.currentTimeMillis() < deadline) {
                        Thread.sleep(100)
                    }
                    addLog("代理已停止")
                }
            }
        } catch (e: Exception) {
            addLog("停止代理异常: ${e.message}")
        }
        setStep(6, M2mStepStatus.SUCCESS)
    } finally {
        try {
            core?.stop()
        } catch (_: Exception) {}
    }
}

fun overrideMixedPort(content: String, port: Int): String {
    val regex = Regex("^(mixed-port:\\s*)\\d+", RegexOption.MULTILINE)
    return if (regex.containsMatchIn(content)) {
        regex.replace(content) { "${it.groupValues[1]}$port" }
    } else {
        "mixed-port: $port\n$content"
    }
}

suspend fun downloadConfigFile(
    context: Context,
    url: String,
    proxyAddress: String?,
    workDir: File,
    addLog: (String) -> Unit,
    insecure: Boolean = true,
): File = withContext(Dispatchers.IO) {
    val destFile = File(workDir, "config.yaml")
    val isLocal =
        url.startsWith("data://") || url.startsWith("sdcard://") ||
            url.startsWith("cache://") || url.startsWith("file://")

    if (isLocal) {
        addLog("从本地路径获取配置: $url")
        val src = StoragePathResolver.resolveFile(context, url, allowRawPath = true)
        src.copyTo(destFile, overwrite = true)
    } else {
        addLog("正在下载配置文件: $url")
        val proxy = proxyAddress?.trim()?.takeIf { it.isNotBlank() }?.let {
            HttpDownloader.parseProxy(it)
        }
        HttpDownloader.downloadToFileSuspend(
            url,
            destFile,
            HttpDownloader.Config(
                connectTimeoutMs = 30_000L,
                readTimeoutMs = 30_000L,
                proxy = proxy,
                sslRetryCount = 2,
                tag = "M2M_TEST",
                insecure = insecure,
            ),
        )
        addLog("配置文件下载完成 (${destFile.length()} bytes)")
    }
    destFile
}

fun canConnect(port: Int): Boolean {
    return try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 300)
        }
        true
    } catch (_: Exception) {
        false
    }
}

fun dumpM2mLogFile(logFile: File, addLog: (String) -> Unit) {
    if (!logFile.exists()) return
    try {
        val content = logFile.readText()
        if (content.isBlank()) {
            addLog("日志文件为空")
        } else {
            addLog("--- 日志转储 ---")
            content.lines().take(50).forEach { line ->
                if (line.isNotBlank()) addLog(line)
            }
            addLog("--- 日志结束 ---")
        }
    } catch (e: Exception) {
        addLog("读取日志失败: ${e.message}")
    }
}
