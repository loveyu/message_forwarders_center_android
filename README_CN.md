# FlowGate

[English](README.md) | **中文**

Android 消息转发中心 - Service 常驻架构

## 功能特性

### 核心架构
- **Android Foreground Service** - START_STICKY 保证服务存活
- **统一 Ticker 调度** - 单一调度器驱动所有定时检查，支持事件触发
- **网络状态实时监听** - ConnectivityManager.NetworkCallback
- **事件驱动** - 网络变更、屏幕点亮、用户解锁、充放电变化、APP 前后台切换

### 链接层 (LinkManager)
| 类型 | 库 | 特性 |
|------|-----|------|
| MQTT | Eclipse Paho | TLS支持、连续失败退避 |
| WebSocket | OkHttp | WSS/TLS支持、Basic Auth、Gotify协议、消息回放 |
| TCP | Kotlin Socket | Keep-Alive、帧协议(lb/tlv/split) |

类型通过 DSN 协议自动判断（`mqtt://`、`ws://`、`tcp://`），连接参数通过 URL query 传递。支持 TLS 证书配置（`file://`、`sdcard://`、`data://`、`http(s)://`）。

### 输入层 (InputManager)
| 类型 | 说明 |
|------|------|
| HTTP | NanoHTTPD 服务器，支持 Basic/Bearer/Query 认证 |
| Link订阅 | 订阅 MQTT/WebSocket/TCP 链路消息 |
| Gotify回放 | 断线重连后通过 REST API 回放离线消息 |

### 队列层 (QueueManager)
| 类型 | 特性 |
|------|------|
| 内存队列 | 高性能，容量可配，溢出策略（dropOldest/dropNew/block） |
| SQLite | 持久化存储，重试机制 + 指数退避 + 自动清理 |

### 输出层 (OutputManager)
- **HTTP**: POST/PUT/GET，配置重试
- **Link发布**: MQTT/WebSocket/TCP 发布，支持多 linkId 扇出
- **Internal**: Clipboard、File、Broadcast、Notify

每个输出支持即时重试（`retry`）和异步失败队列（`onFailureQueue`）。

### 规则引擎 (RuleEngine)
执行顺序：`decode → detect → enrich → filter → extract → format → output`

| 选项 | 说明 |
|------|------|
| `decode` | 管道解码（`base64Decode|jsonDecode`） |
| `detect` | 类型检测（`image`/`json`/`text`） |
| `enrich` | 数据富化（Gotify 图标等） |
| `filter` | 表达式过滤 |
| `extract` | GJSON 路径提取 |
| `format` | 模板格式化 |

支持 `onError` 错误处理管道和 `when`/`deny` 网络条件控制。

### 死信队列 (DeadLetter)
消息重试达到上限后进入死信队列，支持自定义管道做最终处理。死信消息保存到 `data://deadletter/` 目录，重启后自动恢复。

### 调度器配置
统一 Ticker 驱动所有定时检查（默认 40s，充电时可缩短），事件触发受 5 秒最小间隔防抖保护。

### 网络条件控制
`when`/`deny` 支持 `network`（网络类型）、`ssid`（WiFi 名称，支持正则）、`bssid`（MAC 地址）、`ipRanges`（CIDR）条件，适用于 link、input、output、rule。

## 详细文档

- **配置示例**: [`app/src/main/assets/samples/`](app/src/main/assets/samples/) — 各模块独立示例 + 完整演示
- **示例说明**: [`samples/README.md`](app/src/main/assets/samples/README.md) — 所有配置语法、内置函数、路径协议等完整文档
- **配置 Schema**: `app/src/main/assets/config_schema.md` — 由 `./gradlew generateConfigDoc` 生成

## 配置示例

| 文件 | 说明 |
|------|------|
| [`01_basic_mqtt.yaml`](app/src/main/assets/samples/01_basic_mqtt.yaml) | MQTT 基础连接 |
| [`02_websocket_link.yaml`](app/src/main/assets/samples/02_websocket_link.yaml) | WebSocket 连接 |
| [`03_tcp_link.yaml`](app/src/main/assets/samples/03_tcp_link.yaml) | TCP Socket |
| [`04_network_conditions.yaml`](app/src/main/assets/samples/04_network_conditions.yaml) | 网络条件控制 |
| [`05_http_input.yaml`](app/src/main/assets/samples/05_http_input.yaml) | HTTP 输入服务器 |
| [`06_link_input_output.yaml`](app/src/main/assets/samples/06_link_input_output.yaml) | Link 输入输出 |
| [`07_memory_queue.yaml`](app/src/main/assets/samples/07_memory_queue.yaml) | 内存队列 |
| [`08_sqlite_queue.yaml`](app/src/main/assets/samples/08_sqlite_queue.yaml) | SQLite 持久化队列 |
| [`09_outputs.yaml`](app/src/main/assets/samples/09_outputs.yaml) | 输出模块（含重试/onFailureQueue/通知） |
| [`10_rules.yaml`](app/src/main/assets/samples/10_rules.yaml) | 规则引擎 |
| [`11_clipboard_forward.yaml`](app/src/main/assets/samples/11_clipboard_forward.yaml) | 剪贴板转发示例 |
| [`12_http_shared_input.yaml`](app/src/main/assets/samples/12_http_shared_input.yaml) | HTTP 共享端口示例 |
| [`13_quick_settings.yaml`](app/src/main/assets/samples/13_quick_settings.yaml) | 通知栏快捷设置示例 |
| [`14_scheduler.yaml`](app/src/main/assets/samples/14_scheduler.yaml) | 调度器配置 |
| [`15_clipboard_history.yaml`](app/src/main/assets/samples/15_clipboard_history.yaml) | 剪贴板历史 |
| [`16_encode.yaml`](app/src/main/assets/samples/16_encode.yaml) | 数据编码封装 |
| [`17_fail_queue.yaml`](app/src/main/assets/samples/17_fail_queue.yaml) | 失败队列重试（onFailureQueue） |
| [`18_output_format.yaml`](app/src/main/assets/samples/18_output_format.yaml) | 输出格式化 |
| [`99_full_demo.yaml`](app/src/main/assets/samples/99_full_demo.yaml) | 完整演示 (组合示例) |

## 状态栏 / 服务状态显示

服务运行时通知栏（由统一 Ticker 驱动，默认 40s 刷新），用于展示运行时统计与简要状态提示。

显示格式（简写说明）：
- L：链路数 (Links)
- I：输入数 (Inputs)
- O：输出数 (Outputs)

示例显示：
```
L链路数 I输入数 O输出数
```

例如：`L3 I2 O4`

状态标志（以 `|` 分隔，按需显示）：
- `暂停接收` - 接收已暂停
- `暂停转发` - 转发已暂停
- `W锁` - WakeLock 已启用
- `WiFi锁` - WiFi Lock 已启用

例如：`L3 I2 O4 | 暂停转发 | W锁`

## 权限需求

- `INTERNET` - 网络通信
- `ACCESS_NETWORK_STATE` - 网络状态监听
- `ACCESS_WIFI_STATE` - WiFi 状态监听
- `NEARBY_WIFI_DEVICES` - WiFi 信息访问（Android 13+ 获取 SSID/BSSID）
- `ACCESS_FINE_LOCATION` - 定位权限（部分设备获取 WiFi 信息需要）
- `ACCESS_BACKGROUND_LOCATION` - 后台定位权限（Android 10+ 后台获取 WiFi SSID/BSSID）
- `FOREGROUND_SERVICE` - 前台服务
- `FOREGROUND_SERVICE_REMOTE_MESSAGING` - 远程消息前台服务类型（Android 14+，适用于 MQTT/WebSocket/TCP 长连接）
- `WAKE_LOCK` - 唤醒锁（保持 CPU 在熄屏后运行）
- `RECEIVE_BOOT_COMPLETED` - 开机自启动
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` - 电池优化豁免
- `POST_NOTIFICATIONS` - 通知权限（Android 13+）
- `READ_MEDIA_IMAGES` - 读取媒体图片（Android 13+）
- `READ_EXTERNAL_STORAGE` - 读取外部存储（Android 12 及以下）
- `WRITE_EXTERNAL_STORAGE` - 写入外部存储（Android 10 及以下）
- `MANAGE_EXTERNAL_STORAGE` - 管理所有文件（Android 11+，用于 sdcard:// 路径写入）

## 构建

```bash
./gradlew assembleDebug
```
