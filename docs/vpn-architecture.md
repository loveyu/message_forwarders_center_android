# VPN 模块架构文档

FlowGate 的 VPN 功能基于 mihomo（Clash.Meta）代理核心，通过 Android VpnService 的 TUN 接口实现全局或按应用的透明代理。

## 整体架构

```
┌─────────────────────────────────────────────────────────────────────┐
│                         用户操作 (VpnScreen)                         │
│              开关 VPN / 选择候选 / 下载核心 / 配置管理                │
└────────────────────────────┬────────────────────────────────────────┘
                             │
                             v
┌─────────────────────────────────────────────────────────────────────┐
│                     VpnManager (状态中心)                             │
│  StateFlow<VpnUiState> ── 候选解析 / 配置缓存 / 核心管理 / 设置持久化 │
└────────────────────────────┬────────────────────────────────────────┘
                             │
                             v
┌─────────────────────────────────────────────────────────────────────┐
│                   MfcaVpnService (Android VpnService)                │
│  生命周期管理 / 自动重试 / TUN 建立 / 前台通知                        │
│                                                                     │
│  ┌── prepareSelectedCandidate() ──┐                                 │
│  │  MihomoCoreManager.ensureCore  │ ← 下载/校验 .so 插件            │
│  │  VpnConfigCacheManager         │ ← 下载/校验 YAML 配置           │
│  │  VpnProfileManager             │ ← 构建运行时 profile            │
│  └────────────────────────────────┘                                 │
│                                                                     │
│  ┌── 启动顺序 ────────────────────────────────────────────────────┐ │
│  │  1. MihomoPluginCore.socketProtector = { fd -> protect(fd) }  │ │
│  │  2. MihomoProcessManager.start()   → JNI 加载 mihomo .so      │ │
│  │  3. VpnService.Builder.establish() → 创建 TUN 接口             │ │
│  │  4. VpnBridgeProcessManager.start() → 启动 tun2socks 桥接进程  │ │
│  └────────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────┘
                             │
          ┌──────────────────┼──────────────────┐
          v                  v                  v
   ┌─────────────┐  ┌──────────────┐  ┌──────────────────┐
   │ mihomo 核心  │  │ TUN 接口     │  │ vpnbridge 进程   │
   │ (in-process  │  │ (Android     │  │ (tun2socks,      │
   │  JNI .so)   │  │  VpnService) │  │  子进程)          │
   └──────┬──────┘  └──────┬───────┘  └────────┬─────────┘
          │                │                    │
          │    TUN fd 通过 Unix Socket SCM_RIGHTS 传递
          │                └───────────────────►│
          │                                     │
          │         ┌───────────────────────────┘
          │         v
          │   TUN 数据包 → SOCKS5 拨号
          │         │
          v         v
   ┌─────────────────────────┐
   │ mihomo SOCKS5 proxy     │
   │ 127.0.0.1:17890         │
   └────────────┬────────────┘
                │
                v (socket protector 防止环路)
         远程代理服务器 / 直连
```

## 数据流

```
Android 应用 → TUN 接口 → vpnbridge (tun2socks)
                                    │
                    ┌─── DNS 查询 (UDP port 53) ─── 其他流量 ───┐
                    │                                            │
                    v                                            v
          本地 DNS 转发 (绕过 SOCKS5)                   SOCKS5 handshake
          127.0.0.1:1053 (mihomo DNS)                            │
                    │                                            v
                    v                                  mihomo (in-process JNI)
          mihomo fake-ip DNS 解析                              │
          返回假 IP → 域名规则匹配               dialer.DefaultSocketHook
                                                               │
                                                   JNI → MihomoPluginCore.notifyMarkSocket(fd)
                                                               │
                                                   VpnService.protect(fd)  ← 防止路由环路
                                                               │
                                                               v
                                                     远程代理服务器 / 直连目标
```

## 核心组件

### MfcaVpnService

- **文件**: `app/src/main/java/info/loveyu/mfca/vpn/MfcaVpnService.kt`
- **职责**: Android VpnService 实现，VPN 生命周期入口
- **Intent 操作**:
  - `ACTION_ENABLE` — 启用 VPN
  - `ACTION_REFRESH` — 刷新（支持 `forceRestart` 强制重启）
  - `ACTION_DISABLE` — 停用 VPN
- **TUN 参数**:
  - Gateway: `172.19.0.1/30`
  - Portal: `172.19.0.2`
  - MTU: `1500`
  - DNS: `1.1.1.1`, `8.8.8.8`
  - DNS 监听端口: `1053`（mihomo DNS listener）
  - IPv6: 可选启用（添加 `::/0` 路由，捕获 IPv6 流量到 VPN 隧道）
- **自动重试**: 最多 3 次，间隔线性递增（5s × 次数）
- **访问控制模式**:
  - `acceptAll` — 所有应用（排除自身）
  - `exclude` — 排除指定应用
  - `include` — 仅指定应用

### VpnManager

- **文件**: `app/src/main/java/info/loveyu/mfca/vpn/VpnManager.kt`
- **职责**: VPN 子系统的状态中心，管理 UI 状态、候选解析、配置缓存调度
- **关键 API**:
  - `state: StateFlow<VpnUiState>` — 响应式 UI 状态
  - `initialize(context, vpnConfigs, pluginUrl, downloadProxy)` — 初始化
  - `prepareSelectedCandidate(context)` — 准备运行时产物
  - `onTick(context)` — 定时刷新配置，返回是否需要重启
  - `selectCandidate(name)` — 选择候选
  - `downloadCorePlugin(context)` / `deleteCorePlugin(context)` — 核心管理
  - `setDownloadProxyOverride(proxy)` — 下载代理覆盖
- **候选解析**: 按选择历史（LRU）优先匹配可用候选，无历史时取第一个可用候选
- **代理优先级**: VpnStateStore 覆盖 > 配置文件 `plugin.downloadProxy`

### MihomoProcessManager

- **文件**: `app/src/main/java/info/loveyu/mfca/vpn/MihomoProcessManager.kt`
- **职责**: 管理进程内 mihomo 核心的生命周期
- **启动流程**: `System.load(.so)` → `setSocketProtector(true)` → `nativeStart(args)` → 等待代理就绪（TCP 连通性探测，15s 超时）
- **守护线程**: 检测异常退出并回调通知
- **日志**: stdout/stderr 分别写入 `mihomo.stdout.log` / `mihomo.stderr.log`

### VpnBridgeProcessManager

- **文件**: `app/src/main/java/info/loveyu/mfca/vpn/VpnBridgeProcessManager.kt`
- **职责**: 管理 vpnbridge 子进程（tun2socks 中继）
- **TUN fd 传递**: 通过 Unix Domain Socket + `SCM_RIGHTS` 传递给子进程
- **启动参数**: `--control-socket`, `--socks`, `--gateway`, `--portal`, `--dns`, `--udp-relay`
- **内置二进制**: `libvpnbridge.so`（Go 编译），从 `nativeLibraryDir` 符号链接到工作目录执行

### MihomoCoreManager

- **文件**: `app/src/main/java/info/loveyu/mfca/vpn/MihomoCoreManager.kt`
- **职责**: 管理 mihomo .so 插件文件的下载、校验、缓存
- **URL 变更检测**: 使用 `PluginManager.isInstalledFrom()` 对比 `.source_url` 标记文件
- **下载代理**: 支持传入 `proxyAddress`，支持 HTTP/SOCKS5 代理

### VpnProfileManager

- **文件**: `app/src/main/java/info/loveyu/mfca/vpn/VpnProfileManager.kt`
- **职责**: 从缓存的原始配置构建运行时 YAML profile
- **覆盖字段**:
  - `mixed-port` → 用户覆盖或默认 `17890`
  - `allow-lan` → 强制 `false`
  - `bind-address` → 强制 `127.0.0.1`
  - `tun.enable` → 强制 `false`（TUN 由 vpnbridge 处理）
  - `mode` → 可选覆盖（rule/global/direct）
  - `log-level` → 可选覆盖
  - `dns.enable` → 强制 `true`
  - `dns.listen` → 强制 `127.0.0.1:1053`（与 vpnbridge `--dns` 对应）
  - `dns.enhanced-mode` → 强制 `fake-ip`
  - `dns.fake-ip-range` → 默认 `28.0.0.1/8`（仅在配置未指定时注入）
  - `dns.fake-ip-filter` → 默认排除 LAN/localhost/Google DL 等（仅在配置未指定时注入）
  - `dns.default-nameserver` → 默认 `223.5.5.5`, `119.29.29.29`（仅在配置未指定时注入）
  - `dns.nameserver` → 默认国内公共 DNS + DoH（仅在配置未指定时注入）

### VpnConfigCacheManager

- **文件**: `app/src/main/java/info/loveyu/mfca/vpn/VpnConfigCacheManager.kt`
- **职责**: 远程 YAML 配置的下载、缓存、刷新
- **存储**: `vpn/config_cache/<name>.yaml` + `.meta.json`（SHA-256、时间戳）
- **变更检测**: 比较下载前后 SHA-256，返回是否变更
- **自动刷新**: 由 `VpnManager.onTick()` 驱动，按 `refreshIntervalMs` 调度

### VpnStateStore

- **文件**: `app/src/main/java/info/loveyu/mfca/vpn/VpnStateStore.kt`
- **职责**: VPN 用户偏好持久化（SharedPreferences）
- **存储内容**:
  - 全局启用状态
  - 候选选择历史（有序列表，LRU）
  - 每个候选的访问控制模式和应用列表
  - 每个候选的端口/规则模式/日志级别覆盖
  - 每个候选的 UDP 中继/IPv6 泄漏防护/DNS 劫持开关
  - 下载代理覆盖

## 数据模型

- **文件**: `app/src/main/java/info/loveyu/mfca/vpn/VpnModels.kt`

| 模型 | 说明 |
|------|------|
| `VpnRuntimeStatus` | 运行时状态枚举: disabled/idle/preparing/prepared/starting/running/stopping/error |
| `VpnCoreState` | 核心插件状态: isReady/path/pluginVersion |
| `VpnConfigCacheState` | 配置缓存状态: isCached/lastUpdatedMs/nextRefreshMs |
| `VpnCandidateState` | 候选完整状态: config + 访问控制 + 核心 + 缓存 + 可用性 |
| `PreparedVpnArtifacts` | 运行时产物: candidate + coreFilePath + profileFilePath + localProxyPort + udpRelay + dnsHijack |
| `VpnUiState` | UI 完整状态: isEnabled + runtimeStatus + candidates + coreState |
| `VpnRuleMode` | 规则模式: rule/global/direct |
| `VpnLogLevel` | 日志级别: debug/info/warning/error/silent |

## 插件系统

### PluginManager

- **文件**: `app/src/main/java/info/loveyu/mfca/plugin/PluginManager.kt`
- **职责**: 管理 .so 插件文件的安装、下载、校验
- **存储路径**: `<filesDir>/plugins/<name>/<abi>/lib<name>_plugin.so`
- **安装方式**:
  - `installFromFile()` — 从本地文件安装
  - `installFromStream()` — 从 InputStream 安装
  - `installFromUrl()` — 从远程 URL 下载安装（支持代理、.gz 自动解压、进度日志）
  - `installPlugin()` — 挂起函数，支持 .so/.zip/.gz 格式
- **并发安全**: 每个插件一把 `ReentrantLock`，double-check 避免重复下载
- **来源追踪**: `.source_url` 标记文件记录安装来源，用于 `isInstalledFrom()` 缓存失效

### MihomoPluginCore

- **文件**: `app/src/main/java/info/loveyu/mfca/plugin/MihomoPluginCore.kt`
- **职责**: mihomo Go 核心的 JNI 包装
- **JNI 方法**:
  - `nativeGetVersion()` — 获取版本号
  - `nativeStart(args, logFile)` — 启动核心
  - `nativeStop()` — 停止核心
  - `nativeIsRunning()` — 查询运行状态
  - `nativeSetSocketProtector(enabled)` — 注册/注销 socket 保护回调

### Socket Protector 机制

VPN include 模式下，mihomo 的出站连接如果不加保护，会被路由回 TUN 接口形成路由环路。解决方案：

1. **Kotlin 侧**: `MihomoPluginCore.socketProtector` 存储一个 `SocketProtector` 函数引用，由 `MfcaVpnService` 设置为 `{ fd -> protect(fd) }`
2. **Go 侧**: `nativeSetSocketProtector(true)` 将 `dialer.DefaultSocketHook` 设置为回调函数
3. **调用链**: Go 的每次出站 socket 创建 → `DefaultSocketHook(fd)` → C 层 `mihomo_protect_socket(fd)` → JNI `AttachCurrentThread` → `MihomoPluginCore.notifyMarkSocket(fd)` → `VpnService.protect(fd)`
4. **效果**: 被标记的 socket 绑定到物理网络接口，不经过 VPN TUN

## vpnbridge (tun2socks)

- **源码**: `app/src/main/go/vpnbridge/`
- **编译**: Go 编译为 `libvpnbridge.so`，作为子进程执行
- **职责**: TUN 接口到 SOCKS5 代理的数据中继
- **工作流程**:
  1. 通过 Unix Socket 接收 TUN fd（`SCM_RIGHTS`）
  2. 创建用户态网络栈（TCP/UDP NAT 表）
  3. TCP: 解析连接目标 → SOCKS5 握手 → 双向数据转发
  4. UDP DNS (port 53): 当 `--dns` 启用时，直接转发到本地 mihomo DNS 监听（绕过 SOCKS5），每个 source 独立 UDP conn，30s 超时自动清理
  5. UDP 其他: 当 `--udp-relay=true` 时通过 SOCKS5 UDP association 中继，否则丢弃
- **命令行参数**:
  - `--control-socket` — Unix Socket 名称（接收 TUN fd）
  - `--socks` — SOCKS5 代理地址（默认 `127.0.0.1:17890`）
  - `--gateway` — TUN 网关 CIDR（默认 `172.19.0.1/30`）
  - `--portal` — TUN Portal 地址（默认 `172.19.0.2`）
  - `--dns` — 本地 DNS 监听地址，启用 DNS 劫持（如 `127.0.0.1:1053`）
  - `--udp-relay` — 是否转发非 DNS UDP 流量（默认 `true`）

## UI 层

### VpnScreen

- **文件**: `app/src/main/java/info/loveyu/mfca/ui/VpnScreen.kt`
- **内容**:
  - 运行时状态卡片: VPN 开关、状态信息、进度条、状态芯片、操作按钮
  - 核心插件管理: 下载/重下载/删除核心
  - 下载代理设置: ModalBottomSheet 设置代理覆盖
  - 候选卡片列表: 可用性状态、配置缓存管理、选择/应用/设置按钮

### VpnAppSelectActivity

- **文件**: `app/src/main/java/info/loveyu/mfca/vpn/VpnAppSelectActivity.kt`
- **职责**: 应用过滤设置界面
- **功能**: 搜索应用、全选/取消/反选、剪贴板导入导出、显示/隐藏系统应用

### VpnCandidateSettingsActivity

- **文件**: `app/src/main/java/info/loveyu/mfca/vpn/VpnCandidateSettingsActivity.kt`
- **职责**: 每个候选的高级设置
- **功能**: 端口覆盖 (1024-65535)、规则模式 (rule/global/direct)、日志级别 (debug-silent)、UDP 中继开关、IPv6 泄漏防护开关、DNS 劫持开关

### VpnLogActivity

- **文件**: `app/src/main/java/info/loveyu/mfca/vpn/VpnLogActivity.kt`
- **职责**: 实时 mihomo 日志查看
- **功能**: 每秒轮询日志文件、单行复制、复制全部

## 配置

### YAML 配置 (VpnInputConfig)

```yaml
plugin:
  m2mCore: "https://example.com/libmihomo_plugin-arm64-v8a.so.gz"
  downloadProxy: "socks5://127.0.0.1:1080"

inputs:
  m2m:
    - name: "vpn_primary"
      configUrl: "https://example.com/mihomo-profile.yaml"
      refreshIntervalMs: 3600000  # 1 小时自动刷新
      whenCondition: "network=wifi"
      enabled: true
      accessControlMode: "exclude"  # acceptAll / include / exclude
      packages:
        - "com.example.app1"
        - "com.example.app2"
```

### 配置字段说明

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `name` | String | — | 候选名称（唯一标识） |
| `configUrl` | String | — | mihomo 配置文件远程 URL |
| `refreshIntervalMs` | Long | `0` | 自动刷新间隔（毫秒），0 表示不自动刷新 |
| `whenCondition` | String? | `null` | 启用条件（网络类型/SSID/BSSID/IP 范围） |
| `deny` | String? | `null` | 禁用条件 |
| `enabled` | Boolean | `true` | 是否启用此候选 |
| `accessControlMode` | String | `"acceptAll"` | 访问控制模式 |
| `packages` | List | `[]` | 受控应用包名列表 |

### 运行时覆盖

通过 UI 设置的覆盖保存在 SharedPreferences，优先级高于配置文件：

- **端口覆盖** (`mixed-port`): 1024-65535，留空使用默认 17890
- **规则模式**: rule / global / direct
- **日志级别**: debug / info / warning / error / silent
- **UDP 中继**: 开启后非 DNS 的 UDP 流量通过 SOCKS5 转发（需代理支持 UDP），关闭后丢弃非 DNS UDP 防止流量泄漏
- **IPv6 泄漏防护**: 开启后添加 `::/0` 路由到 VPN，捕获 IPv6 流量并丢弃，防止 IPv6 流量绕过代理
- **DNS 劫持**: 开启后 vpnbridge 拦截 DNS 查询转发到 mihomo 本地 DNS 监听，启用 fake-ip 模式使域名规则生效
- **下载代理**: 覆盖 `plugin.downloadProxy`，适用于代理不可用时手动切换

## 文件结构

```
<filesDir>/
├── plugins/
│   └── mihomo/<abi>/
│       ├── libmihomo_plugin.so      # mihomo 核心二进制
│       └── .source_url              # 下载来源标记
└── vpn/
    ├── config_cache/
    │   ├── <name>.yaml              # 缓存的配置文件
    │   └── <name>.meta.json         # SHA-256 + 时间戳
    ├── profiles/
    │   └── <name>.runtime.yaml      # 运行时 profile（已覆盖）
    ├── core/
    │   └── vpnbridge                # vpnbridge 符号链接
    └── runtime/<name>/
        ├── mihomo.stdout.log        # mihomo 标准输出日志
        ├── mihomo.stderr.log        # mihomo 错误日志
        └── bridge/
            ├── bridge.stdout.log
            └── bridge.stderr.log
```

## 测试

### M2mTestActivity

- **文件**: `app/src/main/java/info/loveyu/mfca/test/M2mTestActivity.kt`
- **测试内容**: 不含 VPN 的 mihomo 代理完整链路测试
- **步骤**: 下载插件 → 下载配置 → 启动代理 → 等待就绪 → HTTP 访问测试 → 清理

### M2mVpnTestActivity

- **文件**: `app/src/main/java/info/loveyu/mfca/test/M2mVpnTestActivity.kt`
- **测试内容**: VPN 完整链路测试（含 include/exclude 双模式）
- **步骤**: VPN 授权 → 下载插件/配置 → 启动代理+VPN(exclude) → 访问测试 → 切换 include 模式 → 直连测试 → 清理

### M2mVpnTestService

- **文件**: `app/src/main/java/info/loveyu/mfca/test/M2mVpnTestService.kt`
- **职责**: 测试用 VPN Service，独立于生产 MfcaVpnService，通过静态回调报告事件
