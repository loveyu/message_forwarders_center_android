# Message Forwarders Center Android

Android 消息转发中心 - Service 常驻架构

## 功能特性

### 核心架构
- **Android Foreground Service** - START_STICKY 保证服务存活
- **网络状态实时监听** - ConnectivityManager.NetworkCallback
- **健康检查自动恢复** - 30秒间隔自动重连断开的链路和输入源

### 链接层 (LinkManager)
| 类型 | 库 | 特性 |
|------|-----|------|
| MQTT | Eclipse Paho | TLS支持、自动重连 |
| WebSocket | OkHttp | WSS支持、自动重连 |
| TCP | Kotlin Socket | Keep-Alive |

#### Link URL 参数
所有连接参数通过 URL 的 query 部分传递，格式统一为：
```
protocol://[username:password@]host:port[?param1=value1&param2=value2...]
```

##### 通用参数 (所有 Link 类型支持)
| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `automaticReconnect` | bool | true | 自动重连 |
| `reconnectInterval` | long | 5 | 重连初始间隔(秒) |
| `reconnectMaxInterval` | long | 60 | 重连最大间隔(秒) |

##### MQTT (`broker` 字段)
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

##### WebSocket (`url` 字段)
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

##### TCP (`broker` 字段)
```
tcp[s]://host:port[?param1=value1...]
```
| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `connectTimeout` | int | 10 | 连接超时(秒) |
| `soTimeout` | int | 30 | Socket超时(秒) |
| `keepAlive` | bool | true | TCP Keep-Alive |
| `noDelay` | bool | true | TCP Nagle算法禁用 |

**示例：**
```yaml
links:
  - id: mqtt_link
    type: mqtt
    broker: mqtt://admin:123456@10.4.125.53:1883?connectTimeout=3&keepAliveInterval=60

  - id: mqtt_tls
    type: mqtt
    broker: mqtts://broker.example.com:8883
    tls:
      ca: /path/to/ca.pem

  - id: ws_link
    type: websocket
    url: ws://localhost:8080/ws?readTimeout=30&automaticReconnect=true

  - id: tcp_link
    type: tcp
    broker: tcp://192.168.1.100:9000?connectTimeout=5&keepAlive=true
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
- **Internal**: Clipboard、File、Broadcast

### 规则引擎 (RuleEngine)
```yaml
pipeline:
  - transform:
      extract: "data.temperature"    # GJSON 路径提取
      filter: "len(data.items) > 0"   # 表达式过滤
      detect: "image"                 # image/json/text 检测
    to: ["mqtt_output", "http_output"]
```

### 网络条件控制
```yaml
enabledWhen:
  network:
    type: wifi        # mobile | wifi | any
    ipRanges:         # CIDR 支持
      - "192.168.1.0/24"
  wifi:
    ssid: ["MyWiFi", "~Guest-.*"]  # 正则支持
    bssid: ["AA:BB:CC:DD:EE:FF"]
```

## 技术栈

| 组件 | 技术 |
|------|------|
| UI | Jetpack Compose + Material 3 |
| HTTP Server | NanoHTTPD |
| MQTT | Eclipse Paho |
| WebSocket | OkHttp |
| YAML | SnakeYAML |
| 异步 | Kotlin Coroutines + ScheduledExecutorService |
| 表达式 | 自定义 ExpressionEngine |

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

## 状态栏显示

服务运行时通知栏显示（5秒刷新）：
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
