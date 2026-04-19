# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

Message Forwarders Center Android - Android 消息转发中心，基于 Android Foreground Service 的常驻架构，支持 MQTT、WebSocket、TCP 等多种链接协议。

## 常用命令

```bash
./gradlew spotlessApply        # 格式化所有 Kotlin 代码
./gradlew spotlessCheck        # 检查代码风格违规
./gradlew assembleDebug        # 调试构建
./gradlew assembleRelease      # 发布构建（需配置 keystore.properties）
./gradlew clean                # 清理构建产物
./gradlew build                # 完整构建
```

## 代码风格

项目使用 Spotless + ktfmt 规范 Kotlin 代码风格（kotlinlangStyle）。

**提交前必须执行**：
1. `./gradlew spotlessApply` — 格式化所有 Kotlin 文件
2. `./gradlew assembleDebug` — 验证编译通过

禁止提交存在 ktlint/ktfmt 违规的代码。CI 会拒绝违规的 PR。

详细规范见 `.editorconfig` 和 `app/build.gradle.kts` 中的 spotless 配置。

## 架构概览

```
┌───────────────────────────────────────────────────────────────┐
│                      ForwardService                            │
│               (Android Foreground Service)                     │
│  ┌─────────── 统一 Ticker (appScheduler) ──────────────┐      │
│  │  周期 tick (30s) + 事件触发 (网络/屏幕/充放电/前后台)   │      │
│  └──────────────────────────────────────────────────────┘      │
├───────────────────────────────────────────────────────────────┤
│  LinkManager  │  InputManager  │  OutputManager  │  QueueManager │
│   (onTick)    │   (onTick)     │                 │   (onTick)    │
├───────────────────────────────────────────────────────────────┤
│                    RuleEngine (Pipeline)                        │
├───────────────────────────────────────────────────────────────┤
│                     UI Layer (Jetpack Compose)                  │
└───────────────────────────────────────────────────────────────┘
```

## 核心模块

| 模块 | 路径 | 说明 |
|------|------|------|
| 服务入口 | `service/ForwardService.kt` | Foreground Service，统一 Ticker 调度 |
| 链接层 | `link/` | MQTT (Paho)、WebSocket (OkHttp)、TCP (Socket) |
| 输入层 | `input/` | HTTP Server (NanoHTTPD)、Link 订阅 |
| 输出层 | `output/` | HTTP、Link 发布、Internal (Clipboard/File/Broadcast/Notify) |
| 队列层 | `queue/` | MemoryQueue (Channel 驱动)、SqliteQueue (tick 驱动) |
| 规则引擎 | `pipeline/` | GJSON 提取、表达式过滤、类型检测、格式化、富化 |
| 死信队列 | `deadletter/DeadLetterHandler.kt` | 消息重试失败后的死信处理 |
| 配置 | `config/` | YAML 配置加载器 |
| HTTP 服务器 | `server/` | NanoHTTPD 实现 |

## 配置文件规范

**所有配置字段名称必须使用驼峰命名（camelCase），不支持下划线命名（snake_case）。**

例如：
- ✅ `linkId`, `clientId`, `batchSize`, `retryInterval`, `maxRetry`, `maxAge`
- ❌ `link_id`, `client_id`, `batch_size`, `retry_interval`, `max_retry`, `max_age`

例外：队列名称、ID 值、路径协议（`data://`, `sdcard://`）以及 YAML 注释不受此限制。

## 配置文件协议

路径配置使用协议前缀：
- `data://` → 应用私有目录
- `sdcard://` → 外部存储
- `file://` → 文件系统绝对路径
- `cache://` → 应用缓存目录

## 链接 URL 格式

```
protocol://[username:password@]host:port[?param1=value1&param2=value2...]
```

- MQTT: `mqtt[s]://` 开头的 `broker` 字段
- WebSocket: `ws[s]://` 开头的 `url` 字段
- TCP: `tcp[s]://` 开头的 `broker` 字段

## 网络条件控制

`when`/`deny` 字段支持：
- `network=wifi|mobile|ethernet|any`（逗号分隔多值表示 OR，如 `network=wifi,mobile`）
- `ssid=WiFi名称`（支持正则，前缀 `~`）
- `bssid=MAC地址`
- `ipRanges=192.168.1.0/24`（CIDR 格式）

## 规则引擎语法

- extract: GJSON 路径提取（`"data.temperature"`, `"$raw"`, `"base64Decode(content)"`）
- filter: 表达式过滤（`"len(data.items) > 0"`, `"path == value"`, `"startsWith"`, `"$headers.X"`）
- detect: 类型检测（`image`, `json`, `text`）
- format: 模板格式化（`"{headers}\n{data}"`, `"{data.field}"`）
- enrich: 数据富化（`"gotifyIcon:<linkId>"`）
- onError: 错误处理管道（每条规则可选）

## 提交规范

提交时请勿添加 AI 签名（如 Co-Authored-By）。

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

## 示例配置

示例配置与演示文件位于 `app/src/main/assets/samples/`，详见该目录下的 README.md（仓库内）。

