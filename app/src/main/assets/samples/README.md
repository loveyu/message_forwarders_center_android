# 配置示例说明

本文档包含 Message Forwarders Center Android 的完整配置示例。

## 文件列表

| 文件 | 说明 |
|------|------|
| `01_basic_mqtt.yaml` | MQTT 基础连接 - DSN 格式、TLS 支持 |
| `02_websocket_link.yaml` | WebSocket 连接 - WS/WSS 配置 |
| `03_tcp_link.yaml` | TCP 连接 - Socket 配置 |
| `04_network_conditions.yaml` | 网络条件控制 - when/deny 条件 |
| `05_http_input.yaml` | HTTP 输入 - NanoHTTPD、认证方式 |
| `06_link_input_output.yaml` | Link 输入输出 - 订阅/发布示例 |
| `07_memory_queue.yaml` | 内存队列 - 高性能临时缓冲 |
| `08_sqlite_queue.yaml` | SQLite 持久化队列 - 重试、清理 |
| `09_outputs.yaml` | 输出模块 - HTTP/Link/Internal |
| `10_rules.yaml` | 规则引擎 - 提取、过滤、检测、富化、格式化 |
| `11_clipboard_forward.yaml` | 剪贴板转发 - 本地剪贴板与 Link 的示例 |
| `12_http_shared_input.yaml` | HTTP 共享端口 - 多输入共用端口示例 |
| `13_quick_settings.yaml` | 快捷设置 - quickSettings 通知栏按钮、快捷开关 |
| `14_scheduler.yaml` | 调度器 - tickInterval / 事件触发 配置 |
| `15_clipboard_history.yaml` | 剪贴板历史 - 去重同步与历史存储 |
| `16_encode.yaml` | 数据编码封装 - base64/url/json/嵌套函数/UUID/IP |
| `17_fail_queue.yaml` | 失败队列 - 输出重试与失败消息回收 |
| `18_output_format.yaml` | 输出格式化 - 每个输出独立格式化 data/header，不影响 pipeline |
| `99_full_demo.yaml` | 完整演示 - 将多个模块组合的演示配置 |

## 链接配置说明

### DSN 格式

链接类型通过 DSN 协议自动判断：

```yaml
links:
  # MQTT (mqtt:// 或 mqtts://)
  - id: mqtt_link
    dsn: mqtt://foo:foo@host:1883?param=value

  # WebSocket (ws:// 或 wss://)
  - id: ws_link
    dsn: ws://host:8080/path?param=value

  # TCP (tcp:// 或 ssl://)
  - id: tcp_link
    dsn: tcp://host:9000?param=value
```

### TLS/SSL 证书配置

支持多种协议读取证书：

```yaml
tls:
  ca: https://example.com/certs/ca.pem   # 网络 URL - 自动下载
  ca: http://example.com/certs/ca.pem    # HTTP URL - 自动下载
  ca: sdcard://Download/ca.pem           # SD 卡路径
  ca: file:///data/certs/ca.pem          # 文件系统绝对路径
  ca: data://certs/ca.pem                # 应用私有目录
  cert: file:///path/to/cert.pem         # 客户端证书 (可选)
  key: file:///path/to/key.pem           # 客户端私钥 (可选)
  insecure: false                        # 跳过证书验证 (不推荐，默认 false)
```

证书下载后存储在外部扩展目录，以 hash 值命名，仅下载一次，更新时重复覆盖。

## 网络条件配置 (when/deny)

`when` 和 `deny` 字段使用 URI query string 格式：

```yaml
links:
  # 仅 WiFi 下启用
  - id: wifi_only
    dsn: mqtt://host:1883
    when: network=wifi

  # WiFi + 特定 SSID
  - id: home_wifi
    dsn: mqtt://host:1883
    when: network=wifi&ssid=MyHomeWiFi

  # WiFi + 多个 SSID（逗号分隔）
  - id: multi_ssid
    dsn: mqtt://host:1883
    when: network=wifi&ssid=MyHomeWiFi,MyHomeWiFi-5G

  # WiFi + IP 段
  - id: local_network
    dsn: mqtt://host:1883
    when: network=wifi&ipRanges=192.168.1.0/24,10.0.0.10

  # 禁止移动网络
  - id: no_mobile
    dsn: mqtt://host:1883
    deny: network=mobile

  # 正则匹配 SSID（前缀 ~）
  - id: regex_ssid
    dsn: mqtt://host:1883
    when: network=wifi&ssid=~MyHomeWiFi-.*

  # 同时使用 when 和 deny
  - id: complex
    dsn: mqtt://host:1883
    when: network=wifi&ipRanges=192.168.1.0/24
    deny: ssid=PublicWiFi
```

### 条件参数说明

| 参数 | 说明 | 示例 |
|------|------|------|
| `network` | 网络类型 | `wifi`, `mobile`, `any` |
| `ssid` | WiFi 名称，支持正则（~前缀） | `MyWiFi`, `~MyWiFi-.*` |
| `bssid` | WiFi MAC 地址 | `AA:BB:CC:DD:EE:FF` |
| `ipRanges` | IP 段，支持 CIDR | `192.168.1.0/24`, `10.0.0.10` |

when/deny 也适用于 input、output、rule 配置：

```yaml
inputs:
  http:
    - name: local_only
      port: 8080
      path: /webhook
      when: network=wifi  # 仅 WiFi 下启用

outputs:
  link:
    - name: mobile_disable
      linkId: mqtt_broker
      role: producer
      deny: network=mobile  # 移动网络下禁用

rules:
  - name: wifi_only_rule
    from: some_input
    when: network=wifi  # 仅 WiFi 下执行
    pipeline:
      - to: [some_output]
```

## 输出格式化（Output Format）

每个输出（`link`、`http`、`internal`）均支持 `format` 字段，对发送数据做二次处理，**不影响 pipeline 后续步骤**。

### 字符串简写
```yaml
outputs:
  link:
    - name: mqtt_out
      ...
      format: '{"data":"{base64Encode(data)}","type":"text","ts":"{now(3)}"}'
```
等同于一步 `$data` 替换。

### 数组步骤（分别处理 data 字段 / header）
```yaml
format:
  - $data: '模板字符串'          # 整体替换 data（→ 字符串）
  - $data.field: '模板字符串'   # 设置/追加 JSON 字段（若 data 不是 JSON 则包裹）
  - $header: '{"K":"V",...}'    # 整体替换 headers（模板须返回 JSON 对象）
  - $header.X-Key: '模板字符串' # 设置/追加单个 header
  - $delete: ["field1","field2"] # 从 data JSON 删除字段
  - $data:                        # Map 形式删除 data 字段
      delete: ["field1", "field2"]
  - $header:                      # Map 形式删除 headers
      delete: ["X-Unwanted"]
```

### 可用上下文变量
| 变量 | 说明 |
|------|------|
| `{data}` | 原始数据字符串 |
| `{data.field}` | JSON 路径提取 |
| `{$rule}` | 规则名称 |
| `{$source}` | 来源输入名 |
| `{$timestamp}` | 秒级时间戳 |
| `{$unix}` | 毫秒时间戳 |
| `{$receivedAt}` | 输入接收毫秒时间戳 |
| `now()` / `now(3)` | 当前时间（可选小数位） |
| `uuidv4()` | 随机 UUID |
| `deviceId()` | Android ID |
| `base64Encode(data)` | Base64 编码 |
| `jsonEncode(data)` | JSON 字符串转义 |
| `urlEncode(data)` | URL 编码 |

## 规则引擎语法

### 提取 (extract)
- `"field"` - 提取字段
- `"data.nested.field"` - 嵌套路径
- `"data.list[0]"` - 数组索引
- `"$raw"` - 整个 JSON 对象
- `"base64Decode(content)"` - Base64 解码

### 过滤 (filter)
- `"length(path) > N"` - 数组/字符串长度
- `"path == value"` - 相等比较
- `"path != value"` - 不等比较
- `"path > N"` - 数值比较
- `"$headers.mqtt_topic == topic"` - Headers 访问（MQTT Topic 等）
- `"contains($headers.mqtt_topic, topic/prefix)"` - 包含匹配

### 检测 (detect)
- `"image"` - PNG/JPEG/GIF/BMP/WebP
- `"json"` - JSON 格式
- `"text"` - 文本内容

### 格式化 (format)
- `"{data}"` - 原始数据
- `"{headers}"` - 所有 Headers（JSON 格式）
- `"{headers.X}"` - 特定 Header 值
- `"{data.field}"` - GJSON 路径提取
- `"{base64Decode(content)}"` - 内置函数调用
- `"{jsonEncode(base64Encode(data))}"` - 嵌套函数调用
- `"{{"` - 转义为字面量 `{`

#### 格式化上下文变量 ($var)

| 变量 | 说明 |
|------|------|
| `{$rule}` | 当前规则名称 |
| `{$source}` | 消息来源输入名称 |
| `{$timestamp}` | 当前秒级时间戳（整数） |
| `{$unix}` | 当前毫秒级时间戳（整数） |
| `{$receivedAt}` | 消息被接收时的毫秒时间戳（InputManager 自动注入） |
| `{$headers.X-ReceivedAt}` | 同 `$receivedAt`，通过 header 访问 |

#### 内置函数列表

**时间函数**

| 函数 | 说明 | 示例输出 |
|------|------|----------|
| `now()` | 当前秒级时间戳（整数） | `1746184530` |
| `now(3)` | 秒级时间戳，保留至多3位小数（ms精度） | `1746184530.123` |
| `now(6)` | 秒级时间戳，保留至多6位小数 | `1746184530.123000` |
| `nowMs()` | 当前毫秒时间戳（整数） | `1746184530123` |
| `nowDate("yyyy-MM-dd HH:mm:ss")` | 当前时间格式化字符串 | `2026-05-02 19:55:30` |
| `msToDate(ms, "yyyy-MM-dd")` | 毫秒时间戳转日期字符串 | `2026-05-02` |
| `msToSec(ms)` | 毫秒转带小数秒（默认3位） | `1746184530.123` |
| `msToSec(ms, 6)` | 毫秒转带小数秒（最多6位） | `1746184530.123000` |

**UUID 函数**

| 函数 | 说明 | 示例输出 |
|------|------|----------|
| `uuid()` / `uuidv4()` | UUID v4（随机） | `550e8400-e29b-41d4-a716-446655440000` |
| `uuidv3("dns", "example.com")` | UUID v3（MD5，名称空间+名称） | 确定性，相同输入相同输出 |
| `uuidv5("url", "https://example.com")` | UUID v5（SHA-1，名称空间+名称） | 确定性，相同输入相同输出 |
| `uuidv7()` | UUID v7（时间有序，单调递增） | `01960d1a-b4c3-7abc-8def-123456789abc` |

uuidv3/uuidv5 内置名称空间：`"dns"`, `"url"`, `"oid"`, `"x500"`, 或自定义 UUID 字符串。

**随机/编码函数**

| 函数 | 说明 | 示例输出 |
|------|------|----------|
| `randStr(16)` | 16位随机字母数字字符串（0-9a-zA-Z） | `aB3xK9mZ2qW7vNpQ` |
| `base64Encode(data)` | Base64 编码 | `aGVsbG8=` |
| `base64Decode(data)` | Base64 解码 | `hello` |
| `urlEncode(data)` | URL 编码 | `hello+world` |
| `jsonEncode(data)` | JSON 字符串转义 | `he said \"hello\"` |
| `httpBuildQuery(data)` | JSON 对象转 URL query string | `key1=val1&key2=val2` |

**字符串函数**

| 函数 | 说明 |
|------|------|
| `contains(str, sub)` | 包含检查 |
| `startsWith(str, prefix)` | 前缀检查 |
| `endsWith(str, suffix)` | 后缀检查 |
| `length(str)` | 字符串/数组长度 |
| `toUpperCase(str)` | 转大写 |
| `toLowerCase(str)` | 转小写 |
| `trim(str)` | 去除首尾空白 |
| `replace(str, old, new)` | 字符串替换 |
| `substring(str, start, end)` | 子字符串 |

**设备/网络函数**

| 函数 | 说明 | 示例输出 |
|------|------|----------|
| `deviceId()` | 设备 ANDROID_ID（每应用签名+设备唯一） | `a1b2c3d4e5f60789` |
| `localIp()` | 当前设备主 IPv4 地址 | `192.168.1.100` |
| `localIps()` | 所有 IPv4 地址，逗号分隔 | `192.168.1.100,10.0.0.2` |

**数学函数**

| 函数 | 说明 |
|------|------|
| `abs(n)` | 绝对值 |
| `ceil(n)` | 向上取整 |
| `floor(n)` | 向下取整 |

### 富化 (enrich)
- `"gotifyIcon:<linkId>"` - 从 Gotify REST API 获取应用图标并注入 `icon` 字段

### 错误处理 (onError)
```yaml
rules:
  - name: example
    from: some_input
    pipeline:
      - to: [output]
    onError:                # 主管道执行失败时的备用管道
      - to: [error_output]
```

## 输入配置

### 多主题订阅
```yaml
inputs:
  link:
    - name: multi_topic
      linkId: mqtt_broker
      role: consumer
      topics:
        - sensors/#
        - devices/+
      qos: 1
```

### 主题排除
```yaml
inputs:
  link:
    - name: exclude_example
      linkId: mqtt_broker
      role: consumer
      topic: phone-clipboard/#
      excludeTopics:
        - phone-clipboard/system
      qos: 1
```

## 文件路径协议

所有需要指定文件路径的配置项统一使用以下协议前缀：

| 协议 | 说明 | 对应目录 | 示例 |
|------|------|----------|------|
| `data://` | 应用私有数据目录 | context.getExternalFilesDir | `data://queues/db` |
| `sdcard://` | 外部存储根目录 | Environment.getExternalStorageDirectory | `sdcard://backups/db` |
| `file://` | 文件系统绝对路径 | 直接使用绝对路径 | `file:///data/queue.db` |
| `cache://` | 应用缓存目录 | context.getExternalCacheDir | `cache://temp/config.yaml` |

注意：不再支持无协议前缀的裸路径，必须使用上述协议之一。

### 各场景支持的协议

| 场景 | 支持的协议 |
|------|-----------|
| 配置文件路径 (ConfigDownloader) | `http://`, `https://`, `sdcard://` |
| 队列数据库路径 (SqliteQueue) | `data://`, `sdcard://`, `file://` |
| TLS 证书路径 (CertResolver) | `file://`, `sdcard://`, `data://`, `http://`, `https://` |
| 文件输出路径 (FileOutput) | `data://`, `sdcard://`, `file://` |

## 使用方法

1. 在示例页面选择要使用的配置
2. 点击复制按钮获取配置内容
3. 修改地址、端口、凭证等为实际值
4. 通过 UI 加载配置或使用文件路径

## 调度器配置

所有定时检查（链路健康、输入源恢复、统计刷新、队列处理）由统一 Ticker 驱动。

```yaml
scheduler:
  tickInterval: "40s"              # 定时检查间隔，默认 40s，最小 20s
  chargingTickInterval: "20s"      # 充电时更短的间隔（可选）
  wakeLockTimeout: "1h"            # WakeLock 超时，默认 1h，"0" 表示永久（可选）
  wifiLockTimeout: "1h"            # WiFi Lock 超时，默认 1h，"0" 表示永久（可选）
```

### 事件驱动

除定期 tick 外，以下系统事件会立即触发检查（受 5 秒最小间隔保护）：

| 事件 | 说明 |
|------|------|
| 网络变更 | WiFi/移动网络切换、网络恢复/丢失 |
| 屏幕点亮 | `ACTION_SCREEN_ON` |
| 用户解锁 | `ACTION_USER_PRESENT` |
| 接入/断开电源 | 自动切换到充电/普通间隔 |
| APP 前台 | 任意 Activity 从后台回到前台 |
