# 高德开放平台配置

TrackLab 的地图、定位与搜索功能需要开发者自行申请高德开放平台 Android Key。仓库不提供通用 Key，不提交真实 Key，也不发布预置 Key 的 APK。

## 应用登记信息

在高德开放平台创建 Android 应用时使用：

| 字段 | 值 |
|---|---|
| 应用名称 | TrackLab（名称可按个人控制台规则调整） |
| 平台 | Android |
| 包名 | `io.github.haohaoo3o.tracklab` |
| 发布证书 SHA-1 | `RELEASE_CERT_SHA1` |
| 所需能力 | 地图、定位、搜索 |

`RELEASE_CERT_SHA1` 是文档占位符。请使用你自己实际用于签名 APK/AAB 的证书 SHA-1，不要复制他人的证书指纹，也不要把真实指纹或签名材料提交到仓库。

可使用 JDK 的 `keytool` 查看个人证书信息：

```bash
keytool -list -v -keystore /path/to/your/keystore
```

按照提示输入本地证书口令后，在输出中查找 SHA-1。命令中的路径仅为通用示例。

也可以在仓库根目录运行本地签名准备脚本：

```bash
./tools/prepare_release_signing.sh
```

脚本生成或复用 `.secrets/tracklab-release.jks`，通过权限为 `600` 的临时密码文件调用 `keytool`，退出时自动清理临时文件，不把口令值放入进程参数，也不修改受版本控制的文档。PackageName 与证书 SHA-1 只写入本地忽略的 `.secrets/amap-registration.txt`；普通成功日志不会打印完整 SHA-1。需要查看登记值时由用户自行运行：

```bash
cat .secrets/amap-registration.txt
```

`.secrets/` 中的签名材料、口令和登记值均不得提交或分享。

## 申请步骤

1. 登录 <https://lbs.amap.com/> 并进入控制台；
2. 创建应用，应用类型选择 Android；
3. 添加 Android Key，填写包名 `io.github.haohaoo3o.tracklab`；
4. 把 `RELEASE_CERT_SHA1` 替换为你自己的签名证书 SHA-1；
5. 按当前高德控制台要求开通地图、定位和搜索能力；
6. 阅读并接受高德适用的开发者协议、SDK 许可和隐私合规要求；
7. 把获得的 Key 仅写入本机配置或受保护的 CI Secret。

每位开发者、构建者和分发者都应申请并管理自己的 Key。Key 的配额、服务权限、域名或签名绑定由对应高德账户管理。

## 本机配置

复制示例文件：

```bash
cp local.properties.example local.properties
```

然后填写本机 Android SDK 路径和个人 Key：

```properties
sdk.dir=/path/to/Android/sdk
AMAP_API_KEY=你的个人Key
```

也可在构建环境中设置 `AMAP_API_KEY` 环境变量。若本机文件和环境变量均提供 Key，当前构建配置优先读取 `local.properties`。

注入链路：

```text
local.properties 或环境变量 AMAP_API_KEY
→ Gradle manifest placeholder
→ AndroidManifest meta-data com.amap.api.v2.apikey
→ 应用内 KeyProvider
```

Key 为空时项目仍可构建；TrackLab 会显示缺少 Key 的提示，并避免创建地图视图。

## 隐私合规

在用户同意应用隐私说明后、创建高德 `MapView` 前，应用调用高德要求的隐私状态接口。开发者分发自己的构建版本前，还应：

- 核对当前 SDK 版本对应的高德隐私合规文档；
- 确认 [PRIVACY.md](../PRIVACY.md) 与实际功能、权限和 SDK 行为一致；
- 在应用发布渠道提供可访问的隐私政策链接；
- 不在日志、截图、Issue、Pull Request 或构建产物元数据中泄露 Key；
- Key 泄露时立即在高德控制台停用或轮换。

TrackLab 公开隐私政策 URL：

<https://github.com/haohaoo3o/TrackLab-FakeLocation-Lepao/blob/main/PRIVACY.md>

## 常见问题

### 地图为空或提示缺少 Key

检查 `AMAP_API_KEY` 是否为空、包名是否完全一致、证书 SHA-1 是否与当前安装包签名一致，以及对应服务是否已开通。修改后重新构建并安装。

### Debug 与 Release 都需要使用吗

高德 Android Key 通常与包名和签名证书绑定。若 Debug 与 Release 使用不同证书，应按高德控制台当前规则分别配置相应信息，或为本地测试使用受控的个人签名配置。不要把证书或口令提交到仓库。

### 可以提交临时 Key 吗

不可以。即使 Key 设置了配额或签名限制，也应放在本机配置或 CI Secret 中，而不是版本控制文件。
