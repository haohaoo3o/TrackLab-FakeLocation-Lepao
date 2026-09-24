# 变更记录 / Changelog

本项目的重要变更记录在此文件。版本格式遵循[语义化版本](https://semver.org/lang/zh-CN/)，条目结构参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。

## [Unreleased]

### Added

- 中英文友好的公开仓库 README、隐私政策、安全政策、贡献指南与行为准则。
- Apache License 2.0、NOTICE 与第三方软件声明。
- 构建、算法、设备与发布验证指南。
- 高德开放平台个人 Key 申请说明和 Android 13+/MIUI 权限指南。
- `local.properties.example` 与覆盖构建产物、机密和本地工具文件的忽略规则。

### Changed

- 公开版应用名称统一为 TrackLab。
- 公开版包名统一为 `io.github.haohaoo3o.tracklab`。
- 技术契约整理为公开、实现导向的算法与边界说明。
- 高德配置改用个人 Key 和 `RELEASE_CERT_SHA1` 占位符，不记录真实证书信息。

## [0.1.0] - 2026-09-23

### Added

- 六点跑道几何拟合、闭合中心线和等弧长预览。
- 可复现的配速、步频与逐圈横向扰动模型。
- GPX 与 GeoJSON 导出。
- 高德地图预览、定位和搜索 SDK 接入。
- 应用内回放、前台服务、通知及可选悬浮控制。
- Android 官方测试位置提供者输出、权限引导和暂停恢复。
- JVM 单元测试与 Android 仪器测试。

[Unreleased]: https://github.com/haohaoo3o/TrackLab-FakeLocation-Lepao/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/haohaoo3o/TrackLab-FakeLocation-Lepao/releases/tag/v0.1.0
