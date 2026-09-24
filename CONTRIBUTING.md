# 贡献指南 / Contributing Guide

感谢你改进 TrackLab。提交贡献即表示你同意遵守 [行为准则](CODE_OF_CONDUCT.md)，并确认你有权按本项目许可证提供相关内容。

## 开始之前

1. 搜索现有 Issue 与 Pull Request，避免重复工作；
2. 对较大的功能、数据模型变更或兼容性调整，先创建 Issue 说明目标与设计；
3. 安全漏洞不要公开提交，按 [SECURITY.md](SECURITY.md) 私下报告；
4. 不要提交真实高德 Key、证书、签名文件、口令、精确个人轨迹或设备标识符。

## 开发环境

- JDK 17
- Android SDK 35
- Android 8.0 / API 26 或以上的模拟器或设备（仪器测试需要）

配置本地文件：

```bash
cp local.properties.example local.properties
```

按需设置自己的 `sdk.dir` 和 `AMAP_API_KEY`。每位开发者应自行申请高德 Key；仓库不接受通用 Key 或带 Key 的 APK。

## 代码与文档约定

- Kotlin 代码遵循 Kotlin 官方编码约定和现有项目风格；
- 保持公开版包名 `io.github.haohaoo3o.tracklab`；
- 不改变六点采集顺序、坐标口径或数值契约，除非 Pull Request 同时提供设计说明、测试与文档更新；
- 用户可见文本应保持中文与英文资源键同步；
- 新权限、新网络请求、新数据存储或第三方 SDK 必须同步更新 `PRIVACY.md`、权限说明和第三方声明；
- 不实现隐藏 mock 标记、规避 Android 权限或控制第三方应用的功能；
- 文档和示例使用通用占位符，不写本机路径、个人邮箱、设备序列号、真实证书指纹或私有构建信息。

## 验证

提交前至少运行：

```bash
./gradlew --no-daemon clean testDebugUnitTest lintDebug assembleDebug
```

涉及 Android UI、权限、前台服务或设备行为时，还应在连接设备或模拟器上运行：

```bash
./gradlew --no-daemon connectedDebugAndroidTest
```

并按照 [docs/VERIFICATION.md](docs/VERIFICATION.md) 完成与变更相关的人工检查。若某项无法执行，请在 Pull Request 中明确说明原因与未覆盖范围。

## 提交与 Pull Request

- 每个提交聚焦一个逻辑变更，提交信息使用简洁祈使句；
- Pull Request 描述应包含问题、实现、用户影响和验证结果；
- 行为或界面变化应附去除敏感信息后的截图或录屏；
- 算法变化应提供边界案例、确定性测试与数值误差说明；
- 依赖变化应说明来源、许可证和隐私影响；
- 不提交构建产物、日志、截图录屏原件、`.env`、`local.properties` 或任何密钥材料。

## 许可证

除非另有明确说明，贡献将依据 [Apache License 2.0](LICENSE) 许可。提交第三方代码时，必须保留所需归属信息，并确保其许可证允许纳入或分发。

## English summary

Before opening a pull request, discuss substantial changes, follow the Code of Conduct, run unit tests/lint/debug assembly, and document any untested areas. Never commit API keys, signing material, precise personal tracks, device identifiers, or local machine paths. Contributions are submitted under Apache License 2.0 unless explicitly stated otherwise.
