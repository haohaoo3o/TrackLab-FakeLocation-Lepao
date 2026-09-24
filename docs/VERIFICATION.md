# TrackLab 验证指南

本文提供公开仓库的可复现检查清单。命令均从仓库根目录执行，不依赖固定的本机路径、设备型号或设备标识符。

## 1. 环境

- JDK 17
- Android SDK 35
- Android SDK Platform Tools（设备测试需要）
- 可访问项目声明的依赖仓库
- 高德功能测试使用个人 `AMAP_API_KEY`

确认环境：

```bash
java -version
./gradlew --version
adb version
```

复制本地配置：

```bash
cp local.properties.example local.properties
```

设置个人 Android SDK 路径；需要在线地图时再填写个人高德 Key。真实 Key 和签名材料不得提交到版本控制。

## 2. 基础构建

```bash
./gradlew --no-daemon clean testDebugUnitTest lintDebug assembleDebug
```

预期：

- Gradle 配置成功；
- JVM 单元测试通过；
- Android Lint 无阻断问题；
- 生成 Debug APK。

APK 默认位置：

```text
app/build/outputs/apk/debug/app-debug.apk
```

Release 构建需要构建者自己的签名配置。仓库不提供发布私钥、口令、证书指纹或预签名 APK。

## 3. 单元测试覆盖

`testDebugUnitTest` 应覆盖以下核心不变量：

- 六点顺序、手性、拟合边界和中心线闭合；
- ENU/WGS-84 与 WGS-84/GCJ-02 坐标往返；
- 1 m 目标等弧长重采样；
- 配速、步频、步幅、seed 确定性和逐圈差异；
- 轨迹距离连续性、横向边界和曲率；
- GPX/GeoJSON 结构、字段和点序；
- 权限门、隐私门和高德 Key 缺失状态；
- 回放状态机、快照恢复和并发事件串行化；
- 测试位置提供者端口调用顺序；
- 悬浮控件几何与主线程切换；
- 中英文字符串资源键同步。

关键公式与阈值见 [CONTRACTS.md](CONTRACTS.md)。

## 4. 仪器测试

连接 Android 8.0 / API 26 或以上的设备或模拟器：

```bash
adb devices
./gradlew --no-daemon connectedDebugAndroidTest
```

预期至少验证：

- 应用能够启动；
- 隐私同意状态可被测试环境初始化；
- 测试模式标识可见；
- 开始、暂停和停止控件可用；
- 未配置 Key 时出现明确提示而不是崩溃；
- 悬浮界面更新遵守 Android 主线程规则。

厂商系统可能额外限制测试框架启动 Activity。遇到问题时，先确认设备已解锁、USB 调试已授权、应用允许前台启动，再查阅 [权限指南](permissions-android13-miui.md)。

## 5. 人工功能检查

### 5.1 隐私与 Key

1. 清除 TrackLab 应用数据；
2. 启动应用，确认地图初始化前显示隐私说明；
3. 拒绝后确认不会创建在线地图；
4. 同意但不配置 Key，确认出现 Key 配置提示；
5. 使用个人 Key 重新构建，确认地图可加载；
6. 检查日志和界面，不应显示完整 Key。

### 5.2 六点拟合

按固定顺序选择：顶部、左上切点、左下切点、底部、右下切点、右上切点。确认：

- 合理跑道能生成闭合预览；
- 乱序、反向、重合点、过小半径和偏离切点的输入被明确拒绝；
- 顶部与底部明显偏离直道中点时有可理解的提示。

### 5.3 轨迹与导出

1. 使用相同六点、圈数、配速和 seed 生成两次；
2. 比较样本与导出文件，确认结果一致；
3. 更换 seed，确认轨迹存在可见但受边界约束的差异；
4. 确认文件写入 TrackLab 的应用专属 external files `exports` 目录，且导出过程未启动系统文件选择器；
5. 用 GPX/GeoJSON 查看器打开导出文件；
6. 确认 GeoJSON 坐标顺序为经度、纬度，时间顺序递增；
7. 卸载前把需要保留的文件复制到应用专属目录之外，并确认文档已提示卸载可能删除原导出文件。

### 5.4 官方 Mock Location

1. 在开发者选项中把 TrackLab 设为模拟位置应用；
2. 授予所需位置权限；
3. 开始回放并用系统或自有测试应用观察位置更新；
4. 验证暂停时样本索引、距离和时间不推进；
5. 停止后确认测试位置提供者被移除；
6. 取消模拟位置应用选择并重试，确认 TrackLab 显示引导而不是隐藏错误。

### 5.5 前台服务与恢复

- 回放期间通知标题应明确表示测试/模拟定位状态；
- 暂停、继续、停止操作应与主界面状态同步；
- 授予悬浮窗权限后，浮动控制应可拖动且不越出屏幕；
- 未授予时应用不崩溃，主界面控制仍可用；
- 回放中终止进程后再次打开，状态应恢复为暂停，不自动继续。

## 6. 隐私与发布检查

发布源码或二进制前检查：

- application ID 是 `io.github.haohaoo3o.tracklab`；
- 未包含 `local.properties`、`.env`、Key、签名文件、口令或真实证书信息；
- 未包含个人轨迹、设备标识符、日志、截图录屏或本机绝对路径；
- 不提供通用高德 Key或带 Key 的公开 APK；
- `PRIVACY.md`、`THIRD_PARTY_NOTICES.md` 和权限文档与实际依赖及功能一致；
- Apache-2.0 的 `LICENSE` 与 `NOTICE` 随分发物保留；
- 高德 SDK 及其他第三方组件的许可和分发条件已单独核对。

可用文本搜索辅助检查，但结果需要人工复核：

```bash
grep -RInE 'AMAP_API_KEY=.+|BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY|sdk\.dir=/' \
  --exclude-dir=.gradle --exclude-dir=build --exclude=local.properties.example .
```

示例文件中的空 Key 和通用 SDK 路径属于预期内容。
