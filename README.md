# Message Forwarders Center Android

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
| WebSocket | OkHttp | WSS/TLS支持、Basic Auth、Gotify协议 |
| TCP | Kotlin Socket | Keep-Alive、帧协议(lb/tlv/split) |

#### Link 配置
类型通过 DSN 协议自动判断，所有连接参数通过 URL 的 query 部分传递：
```yaml
links:
  - id: mqtt_link
    dsn: mqtt://admin:123456@10.4.125.53:1883?connectTimeout=3&keepAliveInterval=60

  - id: mqtt_tls
    dsn: mqtts://broker.example.com:8883
    tls:
      ca: /path/to/ca.pem

  - id: ws_link
    dsn: ws://localhost:8080/ws?readTimeout=30&automaticReconnect=true

  - id: ws_tls
    dsn: wss://secure.example.com/ws
    tls:
      ca: /data/certs/ca.pem

  - id: tcp_link
    dsn: tcp://192.168.1.100:9000?connectTimeout=5&keepAlive=true
```

URL 格式统一为：
```
protocol://[username:password@]host:port[?param1=value1&param2=value2...]
```

#### 通用参数 (所有 Link 类型支持)
| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `automaticReconnect` | bool | true | 自动重连（由 LinkManager 健康检查驱动） |
| `reconnectInterval` | long | 10 | 重连最小间隔(秒) |
| `reconnectMaxInterval` | long | 60 | 重连最大间隔(秒, 预留) |

所有 Link 类型采用统一的重连模型：
- 无内部定时器重连，由统一 Ticker 健康检查驱动（默认 30s，可配置）
- 连续失败计数器（最大5次），超过后等待下一健康检查周期重置
- 最小重试间隔防止高频重连
- 支持 YAML `reconnect` 配置块或 DSN 查询参数两种方式配置

#### MQTT (`dsn` 字段)
```
mqtt[s]://[username:password@]host:port[?param1=value1...]
```
| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `connectTimeout` | int | 10 | 连接超时(秒) |
| `keepAliveInterval` | int | 60 | 心跳间隔(秒) |
| `cleanSession` | bool | true | 清理会话 |
| `username` | string | - | 用户名(覆盖URL) |
| `password` | string | - | 密码(覆盖URL) |
| `clientId` | string | 设备ID | 客户端标识 |

#### WebSocket (`dsn` 字段)
```
ws[s]://[username:password@]host:port/path[?param1=value1...]
```
| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `readTimeout` | long | 30 | 读取超时(秒) |
| `writeTimeout` | long | 30 | 写入超时(秒) |
| `pingInterval` | long | 30 | Ping间隔(秒) |
| `username` | string | - | 用户名(用于Basic Auth) |
| `password` | string | - | 密码(用于Basic Auth) |
| `token` | string | - | Gotify客户端Token |
| `protocol` | string | - | 设为 `gotify` 启用Gotify协议(自动添加/stream路径) |

WSS 支持 TLS 证书配置（与 MQTT 相同的 `tls` 块）。

#### TCP (`dsn` 字段)
```
tcp://host:port[?param1=value1...]
```
| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `connectTimeout` | int | 10 | 连接超时(秒) |
| `readTimeout` | int | 30 | Socket读取超时(秒) |
| `writeTimeout` | int | 10 | 写入超时(秒) |
| `keepAlive` | bool | true | TCP Keep-Alive |
| `noDelay` | bool | true | TCP Nagle算法禁用 |
| `protocol` | string | lb | 帧协议: lb/tlv/split |
| `maxLength` | int | 1048576 | 单条消息最大长度(字节) |
| `split` | string | \n | 分隔符(仅split协议) |

#### TLS 配置
所有 TLS 链接类型（mqtts://、wss://）支持自定义证书：
```yaml
tls:
  ca: file:///data/certs/ca.pem       # CA 证书
  cert: file:///path/to/cert.pem      # 客户端证书(可选)
  key: file:///path/to/key.pem        # 客户端私钥(可选)
```

证书路径支持协议前缀：
| 协议 | 示例 | 说明 |
|------|------|------|
| `file://` | `file:///data/certs/ca.pem` | 文件系统绝对路径 |
| `sdcard://` | `sdcard://Download/ca.pem` | 外部存储 |
| `data://` | `data://certs/ca.pem` | 应用私有目录 |
| `http://` | `http://example.com/ca.pem` | HTTP下载(缓存) |
| `https://` | `https://example.com/ca.pem` | HTTPS下载(缓存) |

#### 重连配置
支持两种方式配置（DSN 查询参数优先）：

```yaml
# 方式一: DSN 查询参数
- id: ws_link
  dsn: ws://host/ws?reconnectInterval=10&automaticReconnect=true

# 方式二: YAML 配置块
- id: ws_link
  dsn: ws://host/ws
  reconnect:
    enabled: true
    interval: 10s
    maxInterval: 60s
```

### 输入层 (InputManager)
| 类型 | 说明 |
|------|------|
| HTTP | NanoHTTPD 服务器，支持 Basic/Bearer/Query 认证 |
| Link订阅 | 订阅 MQTT/WebSocket/TCP 链路消息 |

### 队列层 (QueueManager)
| 类型 | 路径协议 |
|------|----------|
| 内存队列 | 高性能，容量可配，溢出策略（dropOldest/dropNew/block） |
| SQLite | `data://` → 应用私有目录<br>`sdcard://` → 外部存储<br>`file://` → 文件系统绝对路径<br>重试机制 + 指数退避 + 自动清理 |

### 输出层 (OutputManager)
- **HTTP**: POST/PUT/GET，配置重试
- **Link发布**: MQTT/WebSocket/TCP 发布
- **Internal**: Clipboard、File、Broadcast、Notify

### 规则引擎 (RuleEngine)
```yaml
pipeline:
  - transform:
      extract: "data.temperature"    # GJSON 路径提取
      filter: "len(data.items) > 0"   # 表达式过滤
      detect: "image"                 # image/json/text 检测
    to: ["mqtt_output", "http_output"]
```

### 调度器配置

所有定时检查由统一 Ticker 驱动，支持配置检查间隔和事件触发：
```yaml
scheduler:
  tickInterval: "30s"              # 定时检查间隔，默认 30s，最小 15s
  chargingTickInterval: "15s"      # 充电时更短的间隔（可选）
```

事件触发（受 5 秒最小间隔防抖保护）：
| 事件 | 说明 |
|------|------|
| 网络变更 | WiFi/移动网络切换、网络恢复/丢失 |
| 屏幕点亮 | `ACTION_SCREEN_ON` |
| 用户解锁 | `ACTION_USER_PRESENT` |
| 接入/断开电源 | 自动切换到充电/普通间隔 |
| APP 前台 | 任意 Activity 从后台回到前台 |

### 网络条件控制
```yaml
links:
  - id: wifi_only
    dsn: mqtt://broker:1883
    when: "network=wifi&ssid=MyWiFi"
    deny: "network=mobile"
```

支持条件：
| 条件 | 值 | 说明 |
|------|-----|------|
| `network` | `wifi`/`mobile`/`ethernet`/`any` | 网络类型，逗号分隔表示OR |
| `ssid` | 名称或 `~正则` | WiFi SSID匹配 |
| `bssid` | MAC地址 | WiFi BSSID匹配 |
| `ipRanges` | CIDR或IP | IP地址范围匹配 |

## 技术栈

| 组件 | 技术 |
|------|------|
| UI | Jetpack Compose + Material 3 |
| HTTP Server | NanoHTTPD 2.3.1 |
| MQTT | Eclipse Paho 1.2.5 |
| WebSocket | OkHttp 4.12.0 |
| YAML | SnakeYAML Engine 2.9 |
| Min SDK | 33 (Android 13) |
| Target SDK | 36 |

## 配置示例

示例文件位于 [`app/src/main/assets/samples/`](app/src/main/assets/samples/) 目录：

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
| [`09_outputs.yaml`](app/src/main/assets/samples/09_outputs.yaml) | 输出模块 |
| [`10_rules.yaml`](app/src/main/assets/samples/10_rules.yaml) | 规则引擎 |
| [`11_full_demo.yaml`](app/src/main/assets/samples/11_full_demo.yaml) | 完整演示 |
| [`15_scheduler.yaml`](app/src/main/assets/samples/15_scheduler.yaml) | 调度器配置 |

## 状态栏显示

服务运行时通知栏显示（由统一 Ticker 驱动，默认 30s 刷新）：
```
L链路数 I输入数 O输出数 · R接收数 S发送数
```

例如：`L3 I2 O4 · R156 S143`

## 权限需求

- `INTERNET` - 网络通信
- `ACCESS_NETWORK_STATE` - 网络状态监听
- `FOREGROUND_SERVICE` - 前台服务
- `FOREGROUND_SERVICE_DATA_SYNC` - 数据同步服务类型
- `POST_NOTIFICATIONS` - Android 13+ 通知权限

## 构建

```bash
./gradlew assembleDebug
```
