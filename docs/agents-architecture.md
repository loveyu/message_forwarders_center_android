# 架构概览

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
│  ┌─────────── PluginEngine (Input/Output 拦截) ───────────┐   │
│  │  Front Slots → Input/Output 处理 → Rear Slots (仅 Input)  │   │
│  └────────────────────────────────────────────────────────┘   │
├───────────────────────────────────────────────────────────────┤
│                    RuleEngine (Pipeline)                        │
│  decode → detect → enrich → filter → extract → format → output │
├───────────────────────────────────────────────────────────────┤
│                     UI Layer (Jetpack Compose)                  │
│    main / config / settings / help / m2m / clipboard / ...     │
├───────────────────────────────────────────────────────────────┤
│               VPN 模块 (m2m / mihomo)                           │
│    vpnbridge(子进程) ─SOCKS5─ m2m(JNI in-process) ─TUN─ Vpn    │
└───────────────────────────────────────────────────────────────┘
```

# 核心模块

| 模块 | 路径 | 说明 |
|------|------|------|
| 服务入口 | `service/` | ForwardService，统一 Ticker 调度 |
| 链接层 | `link/`, `link/tcp/` | MQTT (Paho)、WebSocket (OkHttp)、TCP (Socket) |
| 输入层 | `input/`, `input/{http,mqtt,tcp,udp2raw}/` | HTTP Server (NanoHTTPD)、Link 订阅、UDP2Raw |
| 输入插件 | `input/plugin/` | Input Slot 0-9 JNI wrapper |
| 输出层 | `output/`, `output/{http,mqtt,tcp,notify,clipboard,file,internal}/` | HTTP、MQTT、TCP、通知、剪贴板、文件、广播 |
| 输出插件 | `output/plugin/` | Output Slot 0-9 JNI wrapper |
| 插件核心 | `plugin/core/` | PluginEngine、PluginBase、PluginModels 共享管理层 |
| 队列层 | `queue/` | MemoryQueue (Channel 驱动)、SqliteQueue (tick 驱动) |
| 规则引擎 | `pipeline/core/` | GJSON 提取、表达式过滤、类型检测、格式化、富化 |
| 表达式引擎 | `pipeline/expression/` | ExpressionEngine Lexer/Parser/Filter |
| 富化器 | `pipeline/enrich/` | GotifyIconEnricher 等数据富化 |
| 死信队列 | `deadletter/` | 消息重试失败后的死信处理 |
| 配置 | `config/` | YAML 配置加载器 |
| 配置模型 | `config/models/` | ConfigModels 按功能域拆分（Link/Input/Queue/Output/Rule） |
| 配置 Schema | `config/schema/`, `config/schema/nodes/` | DSL Schema 定义 + 文档生成 |
| VPN 核心 | `m2m/core/`, `m2m/models/`, `m2m/config/` | mihomo 管理、状态存储、配置缓存 |
| VPN 服务 | `m2m/service/` | MfcaM2mService (Foreground Service) |
| VPN 进程 | `m2m/process/` | vpnbridge 子进程管理 |
| VPN 流量 | `m2m/traffic/` | 流量统计收集 |
| VPN 地理 | `m2m/geo/` | Geo 数据文件管理 |
| VPN 工具 | `m2m/util/` | 通知/自动下载/重试/TUN 辅助 |
| VPN UI | `m2m/ui/` | VPN 相关 Activity/Composable |
| HTTP 服务器 | `server/` | NanoHTTPD 实现 |
| 工具层 | `util/`, `util/{network,config,cache,http}/` | 网络检查、配置备份/校验/下载、图标缓存、HTTP 下载 |
| UI | `ui/{main,config,settings,help,component,clipboard,notification,m2m,floating,allcomponents,theme,webview,log,input,license}/` | Jetpack Compose UI |
| 测试模块 | `test/{input_plugin,output_plugin,m2m,udp2raw}/` | 插件/功能测试 Activity 和工具 |
| 插件示例 | `plugin-examples/{input,output}/{go,rust}/` | Go/Rust 插件源码及构建脚本 |
