# TrackLab 隐私政策 / Privacy Policy

**生效日期：2026-09-23**

本政策适用于开源 Android 应用 **TrackLab**（包名 `io.github.haohaoo3o.tracklab`）。TrackLab 用于跑道轨迹生成、地图预览、位置数据导出和 Android 官方模拟位置测试。

## 1. 重要说明

- TrackLab 项目本身**不运营服务器或云端账户系统**，不会由项目维护者集中收集、存储或出售用户数据。
- TrackLab 集成高德地图、定位与搜索 SDK。使用联网地图、定位或搜索功能时，这些 SDK 可能直接与高德服务通信；相关处理受高德的隐私政策、SDK 合规文档及开发者在高德开放平台接受的条款约束。
- 应用应在用户同意隐私说明后、创建地图组件前，向高德 SDK 传递隐私展示和同意状态。拒绝后，不应初始化地图组件或调用相关联网能力。

## 2. 处理的数据

根据用户使用的功能，TrackLab 可能处理以下数据：

### 2.1 位置与轨迹数据

- 用户在地图上选择的六个跑道控制点；
- 设备位置或由应用生成的模拟位置；
- 轨迹样本中的经纬度、时间、速度、配速和步频；
- 用户导出的 GPX 或 GeoJSON 文件。

这些数据用于跑道拟合、轨迹生成、预览、回放、模拟位置输出和文件导出。项目本身不把这些数据上传到 TrackLab 自有服务器，因为项目不提供此类服务器。

### 2.2 地图、定位与搜索数据

高德地图、定位和搜索 SDK 可能为提供底图、定位、地理搜索及相关服务而处理网络请求、设备与应用信息、IP 地址、位置数据和诊断信息。具体数据类别、目的、保留期限和接收方以高德公布的 SDK 隐私合规材料及隐私政策为准。

开发者使用自己的高德 Key 构建应用，并负责在分发前核对所用 SDK 版本、配置和高德最新合规要求。本仓库不提供通用 Key，也不发布带 Key 的 APK。

### 2.3 本地配置与状态

应用可能在设备本地保存：

- 隐私同意状态；
- 回放状态快照，包括状态、样本索引、累计距离和累计时间；
- 用户设置、输入参数或临时运行状态；
- Android 系统与应用运行所需的缓存。

上述内部状态存储在应用私有目录或系统管理的应用存储中。清除应用存储会删除这些数据，卸载应用通常也会删除这些数据。导出文件的具体位置和卸载前保留要求见下一节。

### 2.4 导出文件

只有在用户主动执行导出操作时，TrackLab 才会生成 GPX 或 GeoJSON 文件。导出内容可包含精确经纬度、时间和运动信息。根据 `MainActivity` 的当前实现，文件写入 `getExternalFilesDir(null)/exports`，即 TrackLab 的应用专属 external files `exports` 目录；如果该目录不可用，则回退到应用内部 `filesDir/exports`。当前实现不调用系统文件选择器，也不让用户在导出时选择任意保存位置。

应用专属目录及其中的导出文件可能在卸载 TrackLab 时被系统删除。用户如需保留导出文件，必须在卸载前使用文件管理、调试工具或其他可用方式将文件复制到应用专属目录之外。复制、分享或备份文件后，相关副本由用户管理；向第三方分享前，请确认文件不包含不希望披露的位置或活动信息。

## 3. 权限及用途

应用可能请求或声明以下 Android 权限：

| 权限/系统能力 | 用途 |
|---|---|
| 精确位置与近似位置 | 地图定位、位置相关功能及测试位置输出的权限前置条件 |
| 网络访问与网络状态 | 加载高德在线地图、定位和搜索服务 |
| 通知（Android 13+） | 显示回放前台服务及暂停、继续、停止操作 |
| 前台服务与位置类型前台服务 | 在用户可感知的情况下持续执行轨迹回放 |
| 显示在其他应用上层 | 可选的浮动控制界面，需要用户在系统设置中手动授权 |
| 开发者选项中的模拟位置应用 | 通过 Android 官方测试位置提供者输出模拟位置，需要用户手动选择 TrackLab |

用户可以拒绝非必需权限，但相关功能可能不可用或降级。TrackLab 不通过隐藏接口移除模拟位置标记。

## 4. 数据共享与第三方接收方

TrackLab 项目维护者不运营数据收集后端。以下场景可能涉及数据离开设备：

1. **高德服务**：使用地图、定位或搜索时，高德 SDK 可能把提供服务所需的数据发送给高德；
2. **用户主动分享**：用户自行导出、复制、上传或分享轨迹文件；
3. **操作系统与设备服务**：Android 系统根据权限、通知、定位和存储机制处理必要信息。

除上述功能所需或用户主动操作外，TrackLab 不设计向项目维护者或其他第三方传输用户轨迹的机制。

## 5. 保留、删除与撤回同意

- **撤回权限**：在 Android“设置 → 应用 → TrackLab → 权限”中撤销位置、通知等权限；在“显示在其他应用上层”页面撤销悬浮窗权限；在开发者选项中取消或更换模拟位置应用。
- **停止处理**：停止回放并退出应用。若不希望高德 SDK 继续联网，请不要使用地图、定位或搜索功能，或撤回相关同意。
- **删除本地数据**：在 Android“设置 → 应用 → TrackLab → 存储”中执行“清除存储/清除数据”，或卸载应用。
- **删除导出文件**：删除 TrackLab 应用专属目录中 `exports` 下的 GPX/GeoJSON 文件，并分别删除此前复制、分享或备份到其他位置的副本。卸载可能删除应用专属目录中的文件，但不应把卸载当作保留导出文件的方法。
- **撤回隐私同意**：可通过清除应用存储恢复到首次使用状态。撤回不会自动删除已复制或分享至应用专属目录之外的文件，需由用户分别删除。

由于项目没有服务器或用户账户，维护者没有可供查询或删除的云端 TrackLab 账户数据。

## 6. 安全

应用使用 Android 应用私有存储保存内部状态，并尽量减少数据处理范围。任何本地软件都无法保证绝对安全；请保护设备解锁凭据，不要公开包含敏感位置的导出文件，也不要把高德 Key、签名文件或口令提交到公开仓库。

安全漏洞或其他敏感问题请通过 [GitHub Private Vulnerability Reporting](https://github.com/haohaoo3o/TrackLab-FakeLocation-Lepao/security/advisories/new) 私下报告；不要在公开 Issue 中披露。详细要求见 [SECURITY.md](SECURITY.md)。

## 7. 儿童隐私

TrackLab 不提供面向儿童的账户、社交或广告服务，也不主动收集儿童个人信息。监护人应根据设备、位置数据和第三方地图服务的实际使用情况决定是否允许未成年人使用。

## 8. 政策更新

本政策可能因功能、权限、SDK 或法律要求变化而更新。重要变更将通过仓库中的本文件和变更记录说明。继续使用更新后的版本前，请阅读最新政策。

## 9. 联系方式

不含敏感信息的一般隐私问题可通过[仓库 Issues](https://github.com/haohaoo3o/TrackLab-FakeLocation-Lepao/issues)联系维护者。安全漏洞、精确位置、轨迹文件、API Key、证书、设备标识符或其他敏感信息必须通过 [GitHub Private Vulnerability Reporting](https://github.com/haohaoo3o/TrackLab-FakeLocation-Lepao/security/advisories/new) 私下提交，不得放入公开 Issue。

上述 GitHub URL 在同名仓库创建后可用，其中私密报告 URL 还需要仓库启用 GitHub Private Vulnerability Reporting；为保持预定公开地址稳定，请勿修改这些 URL。

---

## English summary

TrackLab does not operate its own server or account system. Track points, generated samples, playback state, and exported GPX/GeoJSON files are processed locally unless a feature requires the AMap map, location, or search SDK to communicate with AMap services. The current `MainActivity` writes exports to the app-specific external files `exports` directory (`getExternalFilesDir(null)/exports`) and falls back to internal `filesDir/exports` only when external app storage is unavailable; it does not use a system file picker. App-specific files may be deleted when the app is uninstalled, so users must copy exports outside the app-specific directory before uninstalling if they want to keep them. Non-sensitive privacy questions belong in the repository Issues; sensitive reports must use the GitHub Private Vulnerability Reporting URL above. Permissions and consent can be withdrawn in Android settings. AMap SDK processing remains subject to AMap's applicable privacy and SDK terms.
