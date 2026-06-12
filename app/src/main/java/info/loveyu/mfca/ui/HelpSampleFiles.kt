package info.loveyu.mfca.ui

import android.content.Context
import java.io.IOException

data class SampleFile(
    val name: String,
    val description: String,
    val content: String
)

private data class SampleFileInfo(
    val fileName: String,
    val description: String,
    val assetPath: String? = null,
)

fun loadSampleFiles(context: Context): List<SampleFile> {
    val sampleList = listOf(
        SampleFileInfo(
            fileName = "README.md",
            description = "配置说明 - 完整配置语法和协议说明"
        ),
        SampleFileInfo(
            fileName = "config_schema.md",
            description = "配置 Schema 文档 - 所有配置项类型、默认值与说明",
            assetPath = "config_schema.md"
        ),
        SampleFileInfo(
            fileName = "01_basic_mqtt.yaml",
            description = "MQTT 基础连接 - DSN 格式、TLS 支持"
        ),
        SampleFileInfo(
            fileName = "02_websocket_link.yaml",
            description = "WebSocket 连接 - WS/WSS 配置"
        ),
        SampleFileInfo(
            fileName = "03_tcp_link.yaml",
            description = "TCP 连接 - Socket 配置"
        ),
        SampleFileInfo(
            fileName = "04_network_conditions.yaml",
            description = "网络条件控制 - when/deny 条件"
        ),
        SampleFileInfo(
            fileName = "05_http_input.yaml",
            description = "HTTP 输入 - NanoHTTPD、认证方式"
        ),
        SampleFileInfo(
            fileName = "06_link_input_output.yaml",
            description = "Link 输入输出 - 订阅/发布示例"
        ),
        SampleFileInfo(
            fileName = "07_memory_queue.yaml",
            description = "内存队列 - 高性能临时缓冲"
        ),
        SampleFileInfo(
            fileName = "08_sqlite_queue.yaml",
            description = "SQLite 持久化队列 - 重试、退避、清理"
        ),
        SampleFileInfo(
            fileName = "09_outputs.yaml",
            description = "输出模块 - HTTP/Link/Internal 输出"
        ),
        SampleFileInfo(
            fileName = "10_rules.yaml",
            description = "规则引擎 - 提取、过滤、检测"
        ),
        SampleFileInfo(
            fileName = "11_clipboard_forward.yaml",
            description = "剪贴板转发 - MQTT 到本地剪贴板"
        ),
        SampleFileInfo(
            fileName = "12_http_shared_input.yaml",
            description = "HTTP 共享输入 - 多转发器共享输入配置"
        ),
        SampleFileInfo(
            fileName = "13_quick_settings.yaml",
            description = "快捷设置 - 通知栏按钮开关"
        ),
        SampleFileInfo(
            fileName = "14_scheduler.yaml",
            description = "调度器配置 - 定时检查间隔、锁超时"
        ),
        SampleFileInfo(
            fileName = "15_clipboard_history.yaml",
            description = "剪贴板历史 - 多设备剪贴板同步与去重"
        ),
        SampleFileInfo(
            fileName = "16_encode.yaml",
            description = "数据编码封装 - base64/URL/JSON 编码与元信息注入"
        ),
        SampleFileInfo(
            fileName = "17_fail_queue.yaml",
            description = "失败队列输入 - 输出失败后重新注入规则引擎"
        ),
        SampleFileInfo(
            fileName = "18_output_format.yaml",
            description = "输出格式化 - 每个输出独立格式化 data/header，不影响 pipeline"
        ),
        SampleFileInfo(
            fileName = "19_call_resource.yaml",
            description = "Call 资源调用 - 在 pipeline 中调用外部 HTTP 服务并注入结果"
        ),
        SampleFileInfo(
            fileName = "20_m2m_input.yaml",
            description = "m2m 输入 - 远程 m2m 配置、核心地址、候选切换、应用过滤"
        ),
        SampleFileInfo(
            fileName = "99_full_demo.yaml",
            description = "完整演示 - 智能家居场景"
        )
    )

    return sampleList.mapNotNull { info ->
        try {
            val content = context.assets.open(info.assetPath ?: "samples/${info.fileName}")
                .bufferedReader()
                .use { it.readText() }
            SampleFile(
                name = info.fileName,
                description = info.description,
                content = content
            )
        } catch (e: IOException) {
            null
        }
    }
}

fun buildHtmlContent(template: String, content: String, fileName: String, isDarkTheme: Boolean): String {
    val escapedContent = content
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    val isMarkdown = fileName.endsWith(".md", ignoreCase = true)
    val languageClass = if (isMarkdown) "" else " class=\"language-yaml\""
    val viewClass = if (isMarkdown) "md-view" else "yaml-view"
    val themeClass = if (isDarkTheme) "dark" else "light"
    val highlightStyle = if (isDarkTheme) {
        "https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/styles/github-dark-dimmed.min.css"
    } else {
        "https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/styles/github.min.css"
    }

    val markdownScript = if (isMarkdown) {
        """
            var md = window.markdownit({ linkify: false, breaks: true });
            md.renderer.rules.link_open = function() { return ''; };
            md.renderer.rules.link_close = function() { return ''; };
            document.getElementById('content-area').innerHTML = md.render(content);
            document.querySelectorAll('pre code').forEach(function(block) {
                hljs.highlightElement(block);
            });
            addCopyButtons();
        """
    } else {
        """
            hljs.highlightElement(document.getElementById('content'));
        """
    }

    return template
        .replace("{{THEME_CLASS}}", themeClass)
        .replace("{{HIGHLIGHT_STYLE}}", highlightStyle)
        .replace("{{VIEW_CLASS}}", viewClass)
        .replace("{{LANGUAGE_CLASS}}", languageClass)
        .replace("{{CONTENT}}", escapedContent)
        .replace("{{IS_MARKDOWN}}", isMarkdown.toString())
        .replace("{{MARKDOWN_SCRIPT}}", markdownScript)
}
