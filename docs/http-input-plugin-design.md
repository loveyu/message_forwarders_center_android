# Input/Output 插件系统 - 需求设计文档

## 1. 需求概述

在 Input 和 Output 上新增插件拦截机制。每个插件为一个独立的 `.so` 动态库，共 10 个槽位（slot 0-9）。插件通过 JSON 字符串进行数据交换：Kotlin 侧将数据对象序列化为 JSON 传入插件，插件处理后返回 JSON，解析后作为后续数据使用。

插件使用 **Go** (`-buildmode=c-shared`) 或 **Rust** (`cdylib`) 实现。

## 2. 适用范围

| 层级 | 位置 | 前置拦截 | 后置拦截 | 说明 |
|------|------|----------|----------|------|
| **Input** | `HttpInput.handleRequest` | ✅ 修改 InputMessage | ✅ 修改 HTTP Response | HTTP 有响应概念 |
| **Input** | `MqttInput` / `WebSocketInput` / `TcpInput` | ✅ 修改 InputMessage | ❌ | 单向输入无响应 |
| **Output** | `RuleEngineOutputDispatcher.dispatchToOutput` | ✅ 修改 `(data, headers)` | ❌ | 输出单向无响应 |

## 3. 架构设计

### 3.1 整体架构

```
plugin/                               ← 共享管理层
  PluginBase.kt                       ← 抽象基类
  PluginManager.kt                    ← 管理器 (加载/统计/日志)
  PluginModels.kt                     ← 共用数据模型

input/plugin/                         ← Input 接入层
  InputSlot0.kt ~ InputSlot9.kt       ← 10 个 wrapper (JNI: InputSlotN)
  InputPluginDispatcher.kt            ← 前后置编排
  InputPluginConfig.kt                ← Input 配置模型

output/plugin/                        ← Output 接入层
  OutputSlot0.kt ~ OutputSlot9.kt     ← 10 个 wrapper (JNI: OutputSlotN)
  OutputPluginDispatcher.kt           ← 输出拦截编排
  OutputPluginConfig.kt               ← Output 配置模型
```

### 3.2 管理层共享

`PluginManager` 通过构造器工厂解耦 Input/Output：

```kotlin
// PluginManager — 共享，实例化两次
class PluginManager(
    private val context: Context,
    private val prefix: String,              // "Input" / "Output"
    private val slotFactory: (Int) -> PluginBase,
) {
    fun getInstalledPath(slot: Int): File =
        PluginManagerCompat.getInstalledPath(context, "${prefix}_plugin_slot_$slot")
}

// Input
val inputMgr = PluginManager(context, "Input") { slot -> createInputSlot(slot) }
// Output
val outputMgr = PluginManager(context, "Output") { slot -> createOutputSlot(slot) }
```

### 3.3 PluginBase

```kotlin
abstract class PluginBase(
    override val name: String,
) : PluginCore {

    @Volatile protected var loaded = false

    override fun load(soPath: String) {
        System.load(soPath)
        loaded = true
    }

    override fun isLoaded(): Boolean = loaded
    override fun version(): String? = null
    override fun start(args: List<String>, logFile: String?): Int = 0
    override fun stop() {}
    override fun isRunning(): Boolean = loaded

    abstract fun process(inputJson: String): String?
    abstract fun getCapabilities(): Int

    companion object {
        const val CAP_FRONT = 1 shl 0
        const val CAP_REAR  = 1 shl 1
    }
}
```

### 3.4 PluginManager

```kotlin
class PluginManager(
    private val context: Context,
    private val prefix: String,
    private val slotFactory: (Int) -> PluginBase,
) {
    companion object {
        const val MAX_SLOTS = 10
    }

    private val plugins = arrayOfNulls<PluginBase?>(MAX_SLOTS)
    private val slotCaps = IntArray(MAX_SLOTS)
    val slotStats = Array(MAX_SLOTS) { SlotStats() }

    // ── 日志收集 ──
    private val logScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val pendingLogs = mutableListOf<Pair<Int, PluginLogEntry>>()
    private val logLock = Any()

    fun loadPlugin(slot: Int): Boolean {
        val soFile = getInstalledPath(slot, prefix)
        if (!soFile.exists()) return false
        if (plugins[slot] != null) return true
        return try {
            val plugin = slotFactory(slot)
            plugin.load(soFile.absolutePath)
            slotCaps[slot] = plugin.getCapabilities()
            plugins[slot] = plugin
            LogManager.logDebug("$prefix.Plugin", "Loaded slot $slot: ${plugin.name} caps=${slotCaps[slot]}")
            true
        } catch (e: Exception) {
            LogManager.logError("$prefix.Plugin", "Failed to load slot $slot: ${e.message}")
            false
        }
    }

    fun loadConfiguredSlots(slots: List<Int>) {
        slots.distinct().sorted().forEach { loadPlugin(it) }
    }

    // ── 串行前置 ──
    fun processSerial(inputJsonBuilder: (Int) -> String, slots: List<Int>): AppliedResult? {
        var result: AppliedResult? = null
        for (slot in slots.sorted()) {
            val plugin = plugins[slot] ?: continue
            if (slotCaps[slot] and PluginBase.CAP_FRONT == 0) {
                LogManager.logWarn("$prefix.Plugin", "Slot $slot 不支持前置")
                continue
            }
            val json = if (result != null) inputJsonBuilder(slot) else inputJsonBuilder(slot)
            // 串行：链式传递
            val output = processSlot(slot, json) ?: continue
            collectLogs(slot, output)
            when (parseAction(output)) {
                PluginAction.MODIFY -> { result = applyResult(result ?: DefaultResult, output) }
                PluginAction.PASS -> {}
            }
        }
        flushLogs()
        return result
    }

    // ── 并行前置 ──
    fun processParallel(baseJson: String, slots: List<Int>): AppliedResult? {
        var result: AppliedResult? = null
        for (slot in slots.sorted()) {
            val plugin = plugins[slot] ?: continue
            if (slotCaps[slot] and PluginBase.CAP_FRONT == 0) {
                LogManager.logWarn("$prefix.Plugin", "Slot $slot 不支持前置")
                continue
            }
            val output = processSlot(slot, baseJson) ?: continue
            collectLogs(slot, output)
            when (parseAction(output)) {
                PluginAction.MODIFY -> { result = applyResult(DefaultResult, output) }
                PluginAction.PASS -> {}
            }
        }
        flushLogs()
        return result
    }

    // ── 后置 (仅 Input HTTP) ──
    fun processRearSerial(inputJsonBuilder: (Int) -> String, slots: List<Int>): String? { ... }

    // ── 内部 ──
    private fun processSlot(slot: Int, json: String): String? {
        val st = slotStats[slot]
        st.callCount++
        val t0 = System.currentTimeMillis()
        val result = runCatching { plugins[slot]?.process(json) }
            .onFailure { e -> st.errorCount++; LogManager.logWarn("$prefix.Plugin", "Slot $slot error: ${e.message}") }
            .getOrNull()
        st.totalTimeMs += System.currentTimeMillis() - t0
        return result
    }

    private fun collectLogs(slot: Int, output: String) {
        val logs = extractLogs(output) ?: return
        synchronized(logLock) { pendingLogs.addAll(logs.map { slot to it }) }
    }

    private fun flushLogs() {
        val batch: List<Pair<Int, PluginLogEntry>>
        synchronized(logLock) {
            if (pendingLogs.isEmpty()) return
            batch = pendingLogs.toList(); pendingLogs.clear()
        }
        logScope.launch {
            batch.forEach { (slot, entry) ->
                val tag = "$prefix.Plugin.$slot"
                when (entry.level.lowercase()) {
                    "error" -> LogManager.logError(tag, entry.message)
                    "warn"  -> LogManager.logWarn(tag, entry.message)
                    "info"  -> LogManager.logInfo(tag, entry.message)
                    else    -> LogManager.logDebug(tag, entry.message)
                }
            }
        }
    }

    fun unloadAll() { for (i in 0 until MAX_SLOTS) { plugins[i] = null; slotCaps[i] = 0; slotStats[i] = SlotStats() } }
}
```

### 3.5 Input Slot 类

```kotlin
// input/plugin/InputSlot0.kt
class InputSlot0 : PluginBase("input_plugin_slot_0") {
    override fun process(inputJson: String): String? = nativeProcess(inputJson)
    override fun getCapabilities(): Int = nativeGetCapabilities()
    private external fun nativeProcess(inputJson: String): String?
    private external fun nativeGetCapabilities(): Int
}
// InputSlot1 ~ InputSlot9 同理
```

JNI 函数名：`Java_info_loveyu_mfca_input_plugin_InputSlot0_nativeProcess`

### 3.6 Output Slot 类

```kotlin
// output/plugin/OutputSlot0.kt
class OutputSlot0 : PluginBase("output_plugin_slot_0") {
    override fun process(inputJson: String): String? = nativeProcess(inputJson)
    override fun getCapabilities(): Int = nativeGetCapabilities()
    private external fun nativeProcess(inputJson: String): String?
    private external fun nativeGetCapabilities(): Int
}
// OutputSlot1 ~ OutputSlot9 同理
```

JNI 函数名：`Java_info_loveyu_mfca_output_plugin_OutputSlot0_nativeProcess`

## 4. JSON 数据合约

### 4.1 Input 前置输入

```json
{
  "version": 1,
  "type": "input_front",
  "source": "webhook",
  "inputType": "http",
  "method": "POST",
  "uri": "/api/data",
  "queryString": "key=value",
  "dataBase64": "eyJ0ZW1wIjogMzZ9",
  "headers": { "content-type": "application/json" },
  "metadata": {}
}
```

### 4.2 Input 后置输入（仅 HTTP）

```json
{
  "version": 1,
  "type": "input_rear",
  "source": "webhook",
  "inputType": "http",
  "method": "POST",
  "uri": "/api/data",
  "queryString": "key=value",
  "statusCode": 200,
  "responseBody": "OK",
  "responseHeaders": {}
}
```

### 4.3 Output 前置输入

```json
{
  "version": 1,
  "type": "output",
  "source": "my_output",
  "outputType": "http",
  "dataBase64": "eyJ0ZW1wIjogMzZ9",
  "headers": { "content-type": "application/json" },
  "metadata": {
    "rule": "rule_name",
    "outputName": "my_output"
  }
}
```

### 4.4 输出（Input/Output 统一格式）

```json
// 修改
{
  "action": "modify",
  "dataBase64": "eyJtb2RpZmllZCI6IDEwfQ==",
  "headers": { "x-plugin": "processed" },
  "metadata": { "processed_by_slot_0": "true" },
  "statusCode": 201,
  "responseBody": "{\"id\": 123}",
  "responseHeaders": { "content-type": "application/json" },
  "logs": [
    { "level": "info", "message": "Transform applied" }
  ]
}

// 通过不修改
{
  "action": "pass",
  "logs": [ { "level": "debug", "message": "No change needed" } ]
}

// null / 空串 → 异常跳过
```

字段可用性按 `type` 过滤：

| 输出字段 | input_front | input_rear | output |
|----------|-------------|------------|--------|
| `dataBase64` | ✅ | ❌ | ✅ |
| `headers` | ✅ | ❌ | ✅ |
| `metadata` | ✅ | ❌ | ✅ |
| `statusCode` | ❌ | ✅ | ❌ |
| `responseBody` | ❌ | ✅ | ❌ |
| `responseHeaders` | ❌ | ✅ | ❌ |
| `logs` | ✅ | ✅ | ✅ |

## 5. 日志

日志统一走 JSON 输出的 `logs` 数组，收集后异步写入 `LogManager`，tag 格式 `Input.Plugin.<N>` 或 `Output.Plugin.<N>`。

详见 `PluginManager.collectLogs()` + `flushLogs()`。

## 6. 埋点实现

### 6.1 Input 前置埋点 — HttpInput.handleRequest

```kotlin
fun handleRequest(
    ...
    inputPluginDispatcher: InputPluginDispatcher?,
): NanoHTTPD.Response {
    var message = InputMessage(source = sourceName, data = body, headers = headersMap)

    // ★ 前置拦截
    message = inputPluginDispatcher?.interceptFront(message) ?: message

    messageListener?.invoke(message)

    // ★ 后置拦截
    return inputPluginDispatcher?.interceptRear(
        sourceName, uri, session.method.name,
        Response.Status.OK, "OK", emptyMap()
    ) ?: newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "OK")
}
```

### 6.2 Output 前置埋点 — RuleEngineOutputDispatcher.dispatchToOutput

```kotlin
class RuleEngineOutputDispatcher(
    private val outputPluginMgr: PluginManager?,
    private val outputPluginConfig: OutputPluginConfig?,
) {
    fun dispatchToOutput(
        output: Output, outputName: String,
        outData: ByteArray, outHeaders: Map<String, String>,
        ruleName: String, source: String,
        onForwarded: (() -> Unit)?, isDeadLetter: Boolean = false,
    ) {
        // ★ 输出前置拦截
        val (modifiedData, modifiedHeaders) = intercept(
            output, outputName, outData, outHeaders, ruleName, source
        )

        // ... 后续 queue / FanOut / direct send 逻辑不变，使用 modifiedData/Headers ...
    }

    private fun intercept(
        output: Output, outputName: String,
        data: ByteArray, headers: Map<String, String>,
        ruleName: String, source: String,
    ): Pair<ByteArray, Map<String, String>> {
        if (outputPluginConfig?.enabled != true) return data to headers
        val slots = outputPluginConfig.front.slots
        if (slots.isEmpty()) return data to headers

        val json = buildJson {
            put("type", "output")
            put("source", outputName)
            put("outputType", output.type.name)
            put("dataBase64", Base64.encodeToString(data, Base64.NO_WRAP))
            put("headers", JSONObject(headers))
            put("metadata", JSONObject(mapOf("rule" to ruleName, "source" to source)))
        }

        val result = if (outputPluginConfig.front.mode == PluginMode.PARALLEL) {
            outputPluginMgr.processParallel(json.toString(), slots)
        } else {
            outputPluginMgr.processSerial({ slot -> buildJson(slot) }, slots)
        }

        if (result?.dataBase64 != null) {
            return Base64.decode(result.dataBase64, Base64.DEFAULT) to
                (headers + (result.headers ?: emptyMap()))
        }
        return data to headers
    }
}
```

## 7. 配置

```yaml
plugin:
  input:                              # Input 插件安装
    - slot: 0
      url: "https://example.com/plugins/input_logger.so"
  output:                             # Output 插件安装
    - slot: 0
      url: "https://example.com/plugins/output_transformer.so"

inputs:
  http:
    - name: webhook
      dsn: http://0.0.0.0:8080
      plugins:
        enabled: true
        front: { mode: serial, slots: [0] }
        rear: { mode: serial, slots: [] }

  link:
    - name: mqtt_in
      linkId: mqtt_link
      role: consumer
      topic: home/#
      plugins:
        enabled: true
        front: { mode: serial, slots: [0] }

outputs:
  http:
    - name: my_output
      url: https://example.com/webhook
      plugins:
        enabled: true
        front: { mode: parallel, slots: [0] }
```

### Kotlin 配置模型

```kotlin
// PluginConfig
data class PluginConfig(
    val udp2rawCore: String = "",
    val m2mCore: String = "",
    val downloadProxy: String = "",
    val input: List<PluginSlotConfig> = emptyList(),
    val output: List<PluginSlotConfig> = emptyList(),
)

data class PluginSlotConfig(val slot: Int, val url: String)

// Input 配置
data class HttpInputConfig(
    ...
    val plugins: InputPluginConfig? = null,
)
data class LinkInputConfig(
    ...
    val plugins: InputPluginConfig? = null,
)
data class InputPluginConfig(
    val enabled: Boolean = true,
    val front: PluginModeConfig = PluginModeConfig(),
    val rear: PluginModeConfig = PluginModeConfig(),
)

// Output 配置
data class HttpOutputConfig(
    ...
    val plugins: OutputPluginConfig? = null,
)
data class LinkOutputConfig(
    ...
    val plugins: OutputPluginConfig? = null,
)
data class InternalOutputConfig(
    ...
    val plugins: OutputPluginConfig? = null,
)
data class OutputPluginConfig(
    val enabled: Boolean = true,
    val front: PluginModeConfig = PluginModeConfig(),
)

// 共用
data class PluginModeConfig(
    val mode: PluginMode = PluginMode.SERIAL,
    val slots: List<Int> = emptyList(),
)
enum class PluginMode { SERIAL, PARALLEL }
```

## 8. 文件变更清单

### 新增文件

| 文件 | 说明 |
|------|------|
| `app/src/main/java/.../plugin/PluginBase.kt` | 共享抽象基类 |
| `app/src/main/java/.../plugin/PluginManager.kt` | 共享管理器 |
| `app/src/main/java/.../plugin/PluginModels.kt` | SlotStats, PluginLogEntry 等 |
| `app/src/main/java/.../input/plugin/InputSlot0.kt` ~ `InputSlot9.kt` | 10 个 Input wrapper |
| `app/src/main/java/.../input/plugin/InputPluginConfig.kt` | Input 插件配置 |
| `app/src/main/java/.../input/plugin/InputPluginDispatcher.kt` | Input 拦截编排 |
| `app/src/main/java/.../output/plugin/OutputSlot0.kt` ~ `OutputSlot9.kt` | 10 个 Output wrapper |
| `app/src/main/java/.../output/plugin/OutputPluginConfig.kt` | Output 插件配置 |
| `app/src/main/java/.../output/plugin/OutputPluginDispatcher.kt` | Output 拦截编排 |

### 修改文件

| 文件 | 修改 |
|------|------|
| `app/src/main/java/.../config/models/ConfigModels.kt` | `PluginConfig.input`/`PluginConfig.output` |
| `app/src/main/java/.../config/models/InputConfigModels.kt` | `HttpInputConfig`/`LinkInputConfig` 新增 `plugins` |
| `app/src/main/java/.../config/models/OutputRuleConfigModels.kt` | 各 output config 新增 `plugins` |
| `app/src/main/java/.../config/ConfigLoaderParsers.kt` | 解析 `plugin.input`/`plugin.output` |
| `app/src/main/java/.../config/InputConfigParser.kt` | 解析 input 的 `plugins` |
| `app/src/main/java/.../config/OutputConfigParser.kt` | 解析 output 的 `plugins` |
| `app/src/main/java/.../input/InputManager.kt` | 创建 InputPluginManager |
| `app/src/main/java/.../input/http/HttpInput.kt` | handleRequest 集成拦截 |
| `app/src/main/java/.../input/http/HttpVirtualInput.kt` | matchAndHandle 传递拦截器 |
| `app/src/main/java/.../output/OutputManager.kt` | 创建 OutputPluginManager |
| `app/src/main/java/.../pipeline/core/RuleEngineOutputDispatcher.kt` | dispatchToOutput 入口集成拦截 |

## 9. JNI 函数对照

### Input Slot

| Slot | Kotlin 类 | JNI 函数名 | 签名 |
|------|-----------|-----------|------|
| 0 | `InputSlot0` | `Java_info_loveyu_mfca_input_plugin_InputSlot0_nativeProcess` | `(Ljava/lang/String;)Ljava/lang/String;` |
| 0 | `InputSlot0` | `Java_info_loveyu_mfca_input_plugin_InputSlot0_nativeGetCapabilities` | `()I` |
| 1~9 | `InputSlot1~9` | 同理替换数字 | 同上 |

### Output Slot

| Slot | Kotlin 类 | JNI 函数名 | 签名 |
|------|-----------|-----------|------|
| 0 | `OutputSlot0` | `Java_info_loveyu_mfca_output_plugin_OutputSlot0_nativeProcess` | `(Ljava/lang/String;)Ljava/lang/String;` |
| 0 | `OutputSlot0` | `Java_info_loveyu_mfca_output_plugin_OutputSlot0_nativeGetCapabilities` | `()I` |
| 1~9 | `OutputSlot1~9` | 同理替换数字 | 同上 |

## 10. 插件示例

### Output 插件 (Rust)

```rust
#[no_mangle]
pub extern "system" fn Java_info_loveyu_mfca_output_plugin_OutputSlot0_nativeProcess(
    mut env: JNIEnv, _class: JClass, input_json: JString,
) -> jstring {
    let input: String = env.get_string(&input_json).unwrap().into();
    let data: serde_json::Value = serde_json::from_str(&input).unwrap_or_default();

    // type == "output" → 处理输出数据
    match data["type"].as_str() {
        Some("output") => {
            let modified = serde_json::json!({
                "action": "modify",
                "dataBase64": "...",
                "headers": { "x-output-plugin": "processed" },
                "logs": [{"level": "info", "message": "Output transformed"}]
            });
            env.new_string(modified.to_string()).unwrap().into_raw()
        }
        _ => {
            let pass = serde_json::json!({"action": "pass"});
            env.new_string(pass.to_string()).unwrap().into_raw()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_info_loveyu_mfca_output_plugin_OutputSlot0_nativeGetCapabilities(
    _env: JNIEnv, _class: JClass,
) -> i32 {
    1  // CAP_FRONT only
}
```

### Input 插件同时处理前后置 (Rust)

```rust
#[no_mangle]
pub extern "system" fn Java_info_loveyu_mfca_input_plugin_InputSlot0_nativeProcess(
    mut env: JNIEnv, _class: JClass, input_json: JString,
) -> jstring {
    let input: String = env.get_string(&input_json).unwrap().into();
    let data: serde_json::Value = serde_json::from_str(&input).unwrap_or_default();

    match data["type"].as_str() {
        Some("input_front") => {
            let result = serde_json::json!({
                "action": "modify",
                "dataBase64": "...",
                "headers": { "x-input-plugin": "processed" }
            });
            env.new_string(result.to_string()).unwrap().into_raw()
        }
        Some("input_rear") => {
            let result = serde_json::json!({
                "action": "modify",
                "statusCode": 201,
                "responseBody": "{\"status\":\"created\"}"
            });
            env.new_string(result.to_string()).unwrap().into_raw()
        }
        _ => {
            let pass = serde_json::json!({"action": "pass"});
            env.new_string(pass.to_string()).unwrap().into_raw()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_info_loveyu_mfca_input_plugin_InputSlot0_nativeGetCapabilities(
    _env: JNIEnv, _class: JClass,
) -> i32 {
    3  // CAP_FRONT | CAP_REAR
}
```
