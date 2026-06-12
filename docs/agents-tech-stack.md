# 技术栈

| 组件 | 技术 |
|------|------|
| UI | Jetpack Compose + Material 3 |
| HTTP Server | NanoHTTPD 2.3.1 |
| MQTT | Eclipse Paho 1.2.5 |
| WebSocket | OkHttp 4.12.0 |
| YAML | SnakeYAML Engine 2.9 |
| VPN 核心 | mihomo (Clash.Meta) — JNI c-shared (libm2m_plugin.so) |
| VPN 桥接 | hev-socks5-tunnel (libvpnbridge.so) — 子进程运行 |
| 插件语言 | Go (c-shared) / Rust (cdylib) |
| Min SDK | 33 (Android 13) |
| Target SDK | 36 |
