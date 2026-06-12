# VPN / m2m 模块

基于 mihomo (Clash.Meta) 实现 Android 透明代理，详细架构文档见 `docs/vpn-architecture.md`。

## 技术架构

| 组件 | 技术选型 | 说明 |
|------|---------|------|
| 核心代理 | mihomo (Clash.Meta) | JNI c-shared 加载（libm2m_plugin.so，运行时下载） |
| TUN 桥接 | vpnbridge (hev-socks5-tunnel) | C 编译为 shared library (libvpnbridge.so)，打包进 APK 子进程运行 |
| 通信方式 | TCP SOCKS5 | vpnbridge 子进程 → 127.0.0.1 SOCKS5 → m2m in-process (JNI) |
| TUN fd 传递 | Unix LocalSocket SCM_RIGHTS | 从 vpnbridge 子进程传递到 m2m |
| Socket 保护 | VpnService.protect(fd) + JNI 回调 | Go → C → JNI → Kotlin → VpnService.protect() |
| 环路保护 | JNI notifyMarkSocket 回调 | 标记连接 socket 防止环路 |

## 子包结构

```
m2m/
├── core/        M2mManager, M2mCoreManager, M2mStateStore, M2mBridgeProcessManager
├── models/      M2mModels, M2mLogData
├── service/     MfcaM2mService, M2mProvidersService
├── config/      M2mConfigCacheManager, M2mProfileManager, M2mProviderClient
├── process/     M2mProcessManager
├── traffic/     M2mTrafficStatsCollector
├── geo/         GeoFileManager, GeoManageService, GeoManageActivity, GeoManageComponents
├── util/        MfcaM2mRetryHandler, MfcaM2mNotificationHelper, MfcaM2mAutoDownloadHelper, MfcaM2mTunHelper
└── ui/          M2mLogActivity, M2mAppSelectActivity, M2mProvidersActivity, M2mCandidateSettingsActivity
```

## 外部资源管理

通过 m2m REST API (`GET/PUT /providers/{type}/{name}`) 管理代理提供者和规则提供者，UI 入口在 M2mScreen 溢出菜单。
