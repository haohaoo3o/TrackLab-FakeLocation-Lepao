# TrackLab

TrackLab 是一个面向 Android 的开源跑道轨迹生成、地图预览与模拟定位测试工具。它根据六个跑道控制点拟合标准跑道几何，生成具有配速、步频和逐圈扰动的可复现轨迹，并支持应用内回放以及通过 Android 官方测试位置提供者输出模拟位置。

> TrackLab 仅用于开发、测试、教学和研究。模拟位置输出会保留 Android 的 mock 标记，不包含隐藏、绕过或操控其他应用的能力。

[English summary](#english-summary)

## 功能概览

- 六点跑道拟合与闭合轨迹预览
- 固定随机种子的可复现轨迹生成
- 配速、步频、起步、弯道与停步特征模拟
- GPX 与 GeoJSON 位置数据导出
- 高德地图、定位与搜索 SDK 集成
- 应用内地图回放与前台服务控制
- Android 官方 Mock Location 测试位置输出
- Android 8.0（API 26）及以上系统支持

## 六点采集顺序

请严格按以下顺序选择控制点；顺序或绕向错误会导致拟合失败：

1. 顶部（`p0`）
2. 左上切点（`p1`）
3. 左下切点（`p2`）
4. 底部（`p3`）
5. 右下切点（`p4`）
6. 右上切点（`p5`）

其中顶部和底部应位于两条直道中点附近，四个切点应靠近直道与弯道的连接处。详细几何与数值约束见 [技术契约](docs/CONTRACTS.md)。

## 使用前准备

### 1. 配置高德 Key

TrackLab 使用高德地图、定位和搜索 SDK。每位开发者必须使用自己的应用信息在高德开放平台申请 Android Key：

- 包名：`io.github.haohaoo3o.tracklab`
- SHA-1：使用你自己的签名证书指纹
- 需要开通：地图、定位、搜索相关服务

复制示例配置并填写 Key：

```bash
cp local.properties.example local.properties
```

```properties
sdk.dir=/path/to/Android/sdk
AMAP_API_KEY=YOUR_OWN_KEY
```

也可以通过环境变量 `AMAP_API_KEY` 注入。完整申请说明见 [高德开放平台配置](docs/amap-application.md)。

**本项目不会发布通用高德 Key，也不会发布预置 Key 的 APK。** 请勿把真实 Key、签名文件或证书口令提交到仓库。

### 2. 启用官方 Mock Location

如需输出模拟位置：

1. 在 Android 系统设置中启用“开发者选项”；
2. 打开“选择模拟位置信息应用”或同名入口；
3. 选择 **TrackLab**；
4. 返回应用并开始回放。

这是 Android 官方支持的测试流程。TrackLab 不隐藏模拟位置标记。Android 13+ 与部分 MIUI 系统的权限说明见 [权限指南](docs/permissions-android13-miui.md)。

## 构建与测试

环境要求：

- JDK 17
- Android SDK 35
- 可访问 Google Maven、Maven Central 与高德 SDK 仓库

常用命令：

```bash
# 单元测试、静态检查与 Debug 构建
./gradlew --no-daemon clean testDebugUnitTest lintDebug assembleDebug

# 连接设备后运行仪器测试
./gradlew --no-daemon connectedDebugAndroidTest
```

Debug APK 通常生成于：

```text
app/build/outputs/apk/debug/app-debug.apk
```

可复核的构建、算法和设备检查清单见 [验证指南](docs/VERIFICATION.md)。

## 数据与隐私

TrackLab 项目本身不运营服务器。配置、授权状态和回放快照保存在用户设备上。根据 `MainActivity` 的当前实现，用户主动导出的 GPX/GeoJSON 文件写入 `getExternalFilesDir(null)/exports`，即 TrackLab 的应用专属 external files `exports` 目录；如果该目录不可用，则回退到应用内部 `filesDir/exports`。当前实现不调用系统文件选择器，也不让用户在导出时选择任意保存位置。应用专属目录及其中的导出文件可能在卸载 TrackLab 时被系统删除；如需保留，用户必须在卸载前使用文件管理、调试工具或其他可用方式把文件复制到应用专属目录之外。

高德 SDK 在联网地图、定位和搜索功能中可能与高德服务通信。使用前请阅读完整的 [隐私政策](PRIVACY.md)。

公开隐私政策 URL：

<https://github.com/haohaoo3o/TrackLab-FakeLocation-Lepao/blob/main/PRIVACY.md>

该 GitHub URL 在同名仓库创建并发布本文件后可用；为保持对外地址稳定，请勿修改 URL。

## 文档

- [技术契约](docs/CONTRACTS.md)
- [构建与功能验证](docs/VERIFICATION.md)
- [高德开放平台配置](docs/amap-application.md)
- [Android 13+ 与 MIUI 权限指南](docs/permissions-android13-miui.md)
- [第三方软件声明](THIRD_PARTY_NOTICES.md)
- [安全政策](SECURITY.md)
- [贡献指南](CONTRIBUTING.md)
- [行为准则](CODE_OF_CONDUCT.md)
- [变更记录](CHANGELOG.md)

## 贡献与治理

提交 Issue 或 Pull Request 前，请先阅读 [贡献指南](CONTRIBUTING.md) 和 [行为准则](CODE_OF_CONDUCT.md)。安全问题请按 [安全政策](SECURITY.md) 私下报告，不要公开披露尚未修复的漏洞。

## 许可证

项目自有代码与文档依据 [Apache License 2.0](LICENSE) 发布。第三方组件继续适用其各自许可证；高德 SDK 为专有软件，不因本项目采用 Apache-2.0 而被重新许可。详情见 [NOTICE](NOTICE) 与 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## English summary

TrackLab is an open-source Android tool for fitting a running track from six ordered control points, generating reproducible motion samples, previewing/exporting routes, and testing location-aware apps through Android's official Mock Location mechanism.

- Application ID: `io.github.haohaoo3o.tracklab`
- Minimum Android version: Android 8.0 / API 26
- A personal AMap Android API key is required for map, location, and search features.
- No shared API key or APK containing an API key is distributed.
- Enable TrackLab under **Developer options → Select mock location app** for simulated-location output.
- Build with JDK 17 and Android SDK 35.
- GPX/GeoJSON exports are written to the app-specific external files `exports` directory (`getExternalFilesDir(null)/exports`; internal app storage is used only if that directory is unavailable). The current implementation does not use a system file picker. These app-specific files may be removed when TrackLab is uninstalled, so copy any files you want to keep outside the app-specific directory before uninstalling.
- Privacy policy: <https://github.com/haohaoo3o/TrackLab-FakeLocation-Lepao/blob/main/PRIVACY.md>

See the Chinese sections above and the linked documents for complete setup, privacy, security, and contribution information.
