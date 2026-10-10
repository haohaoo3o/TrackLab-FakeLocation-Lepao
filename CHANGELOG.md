# 变更记录 / Changelog

本项目的重要变更记录在此文件。版本格式遵循[语义化版本](https://semver.org/lang/zh-CN/)，条目结构参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。

## [Unreleased]

## [0.3.0] - 2026-10-10

界面彻底重绘（沿用 0.x 版本线；上一版误标为 `2.0.0`，本次一并修正为 `0.3.0`）。

### Changed

- **设计系统落地**：新增黑白灰阶 + 四条低饱和强调色令牌（`colors-matrix.xml`）、组件样式（`styles.xml`）、
  深色主题与状态栏配套，以及 40+ 形状资源（玻璃卡片、发丝线、按钮四态、页签、控制台底、角标、LED、扫描线）。
- **主界面重排**：顶栏（状态 LED + 常驻测试徽标 + 状态词 + 时钟）→ 页签（控制台 / 跑道 / 更多）→ 地图卡 →
  分段面板 → 常驻底部主控条；冗余控件收进页签，主控三键恒可见（48dp 命中区）。
- **日志区**：常驻级别图例（I / OK / W / X）、等宽两列前缀与事件时间列、字段边界预折行；
  贴底跟随仅在贴近底部时生效，用户滚动即脱离（回到底部自动恢复）。
- **悬浮窗**：玻璃卡片（半透明实底 + 顶部高光 + 双发丝描边）、初始落点水平居中（左右边距相等）、
  折叠/展开与呼吸光等动效全部可取消；无权限时静默缺席、由通知兜底。
- **地图**：叠加线改用低饱和深色档、位置标记改为自绘低饱和圆标；底图加 40% 非交互压暗层，
  并关闭高德自带的缩放/比例尺/罗盘/定位/楼层五项浮层控件。
- **图标重绘**：自适应图标（前景跑道环 + 回放三角 + 起点节点、背景径向渐变、Android 13 单色层）；
  通知图标改为单 path 纯白（alpha-only）。
- `versionName` 2.0.0 → 0.3.0，`versionCode` 20 → 30。

### Added

- 数字雨装饰（默认关）、「减少动态」开关、地图高度两档、页签导航与分段折叠。

### Fixed

- 控制台贴底吸附会在用户滚动后把视口拽回底部（现改为「贴近底部才跟随」）。
- 悬浮窗在窄屏上初始落点未钳位、右侧贴边（右边距 0）导致圆角被切平。


## [0.2.0] - 2026-10-08

### Added

- 预制跑道：一键把标准 400m 放置到地图视野中心（`TrackPreset` / `TrackPresetLibrary`）。
- 跑道整体变换：移动（5m 步进）、缩放（±5%）、旋转（±5°），对当前拟合六点整体生效。
- 六点编号 Marker 可拖动微调；微调与变换后仍走同一拟合校验，容差不放宽。
- 测试位置输出拟真：`gps` + `network` 双提供者覆盖；墙钟/单调钟/样本时间三钟同锚；`speed = 位移/Δt`、`bearing = 位移方向`；精度/高程为低频相关过程。
- 输出精度可选档位（10/40/60 m 档带）与可复现的固定种子帧合成器（`LocationFrameSynthesizer`）。
- 控件区改为可滚动布局，地图保持占比（小屏/横屏不再被挤压）。

### Fixed

- `MetricResampler` 末点 `s_N = N·(L/N)` 浮点舍入可能比 `L` 大 1 ulp 导致越域抛错；末点现精确取 `L`（含回归测试）。

### Changed

- 冒烟测试：主控按钮断言前先 `scrollTo`（控件区可滚动）；NEEDS_KEY 提示按构建 Key 事实断言（无 Key 可见 / 有 Key 为 GONE）。

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

[Unreleased]: https://github.com/haohaoo3o/TrackLab-FakeLocation-Lepao/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/haohaoo3o/TrackLab-FakeLocation-Lepao/releases/tag/v0.2.0
[0.1.0]: https://github.com/haohaoo3o/TrackLab-FakeLocation-Lepao/releases/tag/v0.1.0
