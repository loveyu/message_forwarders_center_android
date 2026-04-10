# 配置示例说明

本文档包含 Message Forwarders Center Android 的完整配置示例。

## 文件列表

| 文件 | 说明 |
|------|------|
| `01_basic_mqtt.yaml` | MQTT 基础连接 - DSN 格式、TLS 支持 |
| `02_websocket_link.yaml` | WebSocket 连接 - WS/WSS 配置 |
| `03_tcp_link.yaml` | TCP 连接 - Socket 配置 |
| `04_network_conditions.yaml` | **网络条件控制** - when/deny 条件 |
| `05_http_input.yaml` | HTTP 输入 - NanoHTTPD、认证方式 |
| `06_link_input_output.yaml` | Link 输入输出 - 订阅/发布示例 |
| `07_memory_queue.yaml` | 内存队列 - 高性能临时缓冲 |
| `08_sqlite_queue.yaml` | SQLite 持久化队列 - 重试、清理 |
| `09_outputs.yaml` | 输出模块 - HTTP/Link/Internal |
| `10_rules.yaml` | 规则引擎 - 提取、过滤、检测 |
| `11_full_demo.yaml` | **完整演示** - 智能家居场景 |
| `12_clipboard_forward.yaml` | 剪贴板转发 - MQTT 到本地剪贴板 |

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
    url: ws://host:8080/path?param=value

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

## 规则引擎语法

### 提取 (extract)
- `"field"` - 提取字段
- `"data.nested.field"` - 嵌套路径
- `"data.list[0]"` - 数组索引
- `"$raw"` - 整个 JSON 对象
- `"base64Decode(content)"` - Base64 解码

### 过滤 (filter)
- `"len(path) > N"` - 数组/字符串长度
- `"path == value"` - 相等比较
- `"path != value"` - 不等比较
- `"path > N"` - 数值比较
- `"$headers.mqtt_topic == topic"` - Headers 访问（MQTT Topic 等）

### 检测 (detect)
- `"image"` - PNG/JPEG/GIF/BMP/WebP
- `"json"` - JSON 格式
- `"text"` - 文本内容

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
