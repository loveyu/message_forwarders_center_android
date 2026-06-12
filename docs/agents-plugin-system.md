# 插件系统

Input/Output 各支持 10 个 Slot（0-9），通过 SO 插件（Go/Rust 编译）实现前置/后置拦截。

完整设计文档见 `docs/http-input-plugin-design.md`。

## 架构

```
PluginEngine (plugin/core/)
├── loadPlugin(soPath, slotIndex)           # System.load + 反射
├── runFront(slotIndex, data, headers)      # 前置处理
├── runRear(slotIndex, data, headers)       # 后置处理（仅 Input）
├── runSerial(slots, data, headers)         # 串行执行
└── runParallel(slots, data, headers)       # 并行执行
```

## 核心接口

- `PluginBase` — 抽象基类，封装 `System.load` + `process` + `getCapabilities`
- `PluginEngine` — 管理加载/串行/并行/后置/日志收集
- `SlotStats`, `PluginLogEntry`, `PluginAppliedResult` — 数据模型
- 数据交换格式：JSON（`{"type":"front|rear","data":"...","headers":{}}`）
- 日志通过 JSON `logs` 数组带回，异步写入 LogManager

## 配置

```yaml
plugin:
  input:
    - so: "/path/to/input_plugin_slot_0.so"
      slot: 0
      enabled: true
  output:
    - so: "/path/to/output_plugin_slot_0.so"
      slot: 0
      enabled: true
```

各 Input/Output 配置中可额外指定 `plugins` 字段覆盖全局。

## 插件示例

`plugin-examples/` 下包含 Go/Rust 两种语言的插件源码：

| 示例 | 说明 |
|------|------|
| `input/go/input_plugin_slot_0` | 穿透（不修改数据） |
| `input/go/input_plugin_slot_1` | 修改（前缀添加） |
| `input/rust/input_plugin_slot_0` | Rust 穿透 |
| `input/rust/input_plugin_slot_1` | Rust 修改 |
| `output/go/output_plugin_slot_0` | 输出穿透 |
| `output/go/output_plugin_slot_1` | 输出修改 |
| `output/rust/output_plugin_slot_0` | Rust 输出穿透 |
| `output/rust/output_plugin_slot_1` | Rust 输出修改 |

## 测试模块

- `test/input_plugin/InputPluginTestActivity.kt` — 10 个 MockSlot + UI 测试引擎
- `test/output_plugin/OutputPluginTestActivity.kt` — 10 个 MockSlot + UI 测试引擎
- 测试入口在 `TestHubActivity`
