# CLAUDE.md

FlowGate - Android 消息转发中心，基于 Foreground Service 常驻架构，支持 MQTT/WebSocket/TCP 链接。

## 常用命令

```bash
./gradlew spotlessApply        # 格式化 Kotlin
./gradlew spotlessCheck        # 检查风格违规
./gradlew assembleDebug        # 调试构建
./gradlew testDebugUnitTest    # 运行单元测试
./gradlew generateConfigDoc    # 更新配置 Schema 文档
adb install -r app/build/outputs/apk/debug/app-debug.apk  # 安装 APK
```

## 必守规则

1. **提交前**：`spotlessApply` → `assembleDebug` → `testDebugUnitTest` 全部通过
2. **涉及 Schema 修改**：额外执行 `generateConfigDoc`
3. **涉及 UI/功能变更**：额外用 `adb install` 实机验证，确认无崩溃
4. **禁止**提交 ktlint/ktfmt 违规或测试失败的代码
5. **禁止**在 commit 中添加 `Co-Authored-By`
6. **samples/ 文件变更后**：同步更新 `HelpActivity.kt` 的 `loadSampleFiles` 列表
7. **文件大小**：UI >300 行、非 UI >500 行时提醒是否拆分

## 代码风格

Spotless + ktfmt（kotlinlangStyle），详细规范见 `.editorconfig` 和 `app/build.gradle.kts` 中的 spotless 配置。

### 注释

- **公开 API** 使用 KDoc（`/** ... */`），中文描述
- **章节分隔** 用 `// ──` 标注（如 `// ── Public API ──`、`// ── helpers ──`）
- 无文件级许可证头或文件头注释

### 类与包

- **类** PascalCase，**接口** 无 `I` 前缀，**枚举值** UPPER_SNAKE_CASE
- **可见性**：默认 `internal`，仅在测试或其他模块明确需要时用 `public`
- **文件组织**：一个文件一个主类，紧密相关的辅助类型（数据类/枚举/顶层函数）可共存
- **包命名** 扁平单数名词（`config.models`, `pipeline.core`, `ui.main`）

## 配置命名

全部使用 camelCase（如 `linkId`, `batchSize`），不支持下划线。

## 快速索引

| 如果需要 | 请查阅 |
|---------|--------|
| 包结构总览 | `find app/src/main/java -type d` 或 `docs/agents-architecture.md` |
| 插件系统设计 | `docs/agents-plugin-system.md` + `docs/http-input-plugin-design.md` |
| VPN/透明代理 | `docs/agents-vpn-m2m.md` + `docs/vpn-architecture.md` |
| 规则引擎 Pipeline | `pipeline/core/RuleEngine.kt` + `pipeline/expression/` + `pipeline/enrich/` |
| 配置模型 | `config/models/`（按 Link/Input/Queue/Output/Rule 分 6 文件） |
| 配置 Schema DSL | `config/schema/nodes/`（8 文件，每根节点一个 object） |
| 链接 URL / 网络条件 / 规则语法 | `docs/agents-config-syntax.md` |
| 技术栈 | `docs/agents-tech-stack.md` |
| Go/Rust 插件示例 | `plugin-examples/{input,output}/{go,rust}/` |
| 插件测试界面 | `test/input_plugin/InputPluginTestActivity.kt` / `test/output_plugin/OutputPluginTestActivity.kt` |
