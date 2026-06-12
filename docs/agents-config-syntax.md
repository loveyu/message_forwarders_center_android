# 配置语法

## 命名规范

**所有配置字段名称必须使用驼峰命名（camelCase），不支持下划线命名（snake_case）。**

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

执行顺序：`decode → detect → enrich → filter → extract → format → output`

- decode: 管道解码（`"base64Decode|jsonDecode"`, `"gzDecode|jsonDecode"`），执行顺序在 detect 之前
- detect: 类型检测（`image`, `json`, `text`）
- enrich: 数据富化（`"gotifyIcon:<linkId>"`）
- filter: 表达式过滤（`"len(data.items) > 0"`, `"path == value"`, `"startsWith"`, `"$headers.X"`）
- extract: GJSON 路径提取（`"data.temperature"`, `"$raw"`, `"base64Decode(content)"`）
- format: 模板格式化（`"{headers}\n{data}"`, `"{data.field}"`）、字段删除（`$delete: ["field"]`）
- breakOnReject: 过滤拒绝时是否中断整个管道（默认 `false`）
- onError: 错误处理管道（每条规则可选）
