# TrackLab 技术契约

本文定义 TrackLab 几何拟合、轨迹生成、回放、导出、权限和数据处理的稳定技术约束。实现、测试和文档应保持一致；如修改任何公式、边界或持久化格式，应同时更新对应测试和变更记录。

## 1. 平台与坐标约定

- 应用名：TrackLab
- application ID / namespace：`io.github.haohaoo3o.tracklab`
- JDK：17
- Gradle Wrapper：8.11.1
- Android Gradle Plugin：8.7.2
- Kotlin：2.0.21
- `compileSdk` / `targetSdk`：35
- `minSdk`：26
- 内部计算、导出和测试位置输出：WGS-84
- 高德地图展示：GCJ-02
- 平面长度单位：米；时间单位按字段明确为秒或毫秒；配速单位：秒/千米；步频单位：步/分钟

主要依赖版本：

| 组件 | 版本 |
|---|---:|
| AndroidX Core KTX | 1.15.0 |
| AndroidX AppCompat | 1.7.0 |
| AndroidX Activity | 1.9.3 |
| AndroidX Lifecycle | 2.8.7 |
| Kotlin Coroutines Android | 1.7.3 |
| 高德地图、定位、搜索合包 | 11.3.100 / 11.3.000 / 9.8.1 |
| JUnit 4 | 4.13.2 |
| JSON-java（仅测试） | 20240303 |

## 2. 六点跑道模型

### 2.1 输入顺序

六点必须按以下顺序输入：

| 索引 | 含义 |
|---|---|
| `p0` | 顶部直道中点 |
| `p1` | 左上切点 |
| `p2` | 左下切点 |
| `p3` | 底部直道中点 |
| `p4` | 右下切点 |
| `p5` | 右上切点 |

### 2.2 局部坐标系

局部切平面原点为六点均值 `O`。设局部横轴和纵轴为：

```text
u = normalize(normalize(p5 - p1) + normalize(p4 - p2))
n = rot90(u) = (-u_y, u_x)
```

`u` 指向左到右，`n` 指向底到顶。六点在该坐标系中的有向面积为：

```text
A = 1/2 · Σ(x_i y_{i+1} - x_{i+1} y_i)
```

下标按 6 取模，必须满足 `A > 1.0 m²` 且 `(p0 - O) · n > 0`。否则输入绕向错误或几何退化。

局部 WGS-84 经纬度与 ENU 平面之间采用可逆等距近似：

```text
R_E = 6371008.8 m
x = R_E · cos(φ0) · Δλ
y = R_E · Δφ
z = Δh
```

角度在计算时转换为弧度。水平平移不超过 1 km 时，往返误差应小于 `1e-6 m`；不超过 200 m 的基线与使用同一 `R_E` 的 haversine 距离相对误差应不超过 `1e-3`。

### 2.3 拟合参数

点在 `u/n` 轴上的坐标分别记为 `s_i/t_i`：

```text
a = (s5 + s4 - s1 - s2) / 4
R = (t0 + t1 + t5 - t2 - t3 - t4) / 6
L = 4a + 2πR
```

`a` 是半直道长度，`R` 是弯道半径，`L` 是单圈周长。标准数值 `a=42.5 m`、`R=36.5 m` 时：

```text
L = 399.336264… m
```

不得用固定周长替代公式计算。

### 2.4 中心线参数方程

设 `C_L = O - a·u`、`C_R = O + a·u`。弧长 `s∈[0,L]` 从右上切点 `p5` 开始，按逆时针方向增加：

| 区间 | 中心线 `C(s)` | 外法向 `N(s)` |
|---|---|---|
| `0 ≤ s ≤ 2a` | `O + (a-s)u + Rn` | `n` |
| `2a ≤ s ≤ 2a+πR` | `C_L + R(cosα·n - sinα·u)`，`α=(s-2a)/R` | `cosα·n - sinα·u` |
| `2a+πR ≤ s ≤ 4a+πR` | `O + (s-3a-πR)u - Rn` | `-n` |
| `4a+πR ≤ s ≤ L` | `C_R + R(sinβ·u - cosβ·n)`，`β=(s-4a-πR)/R` | `sinβ·u - cosβ·n` |

必须满足：

- `|C(0)-C(L)| ≤ 1e-6 m`；
- 三个内部接缝及首尾接缝函数值连续；
- 一阶切向连续；
- `point(s)` 只接受 `[0,L]`，多圈调用通过 `wrapArc` 取模。

### 2.5 输入有效性

预期槽位为：

```text
p0=(0,+R)  p1=(-a,+R)  p2=(-a,-R)
p3=(0,-R)  p4=(+a,-R)  p5=(+a,+R)
```

容差：

```text
tol = max(2.0 m, 0.02R)
```

所有点检查纵向残差；四个切点另检查横向残差。顶部和底部直道中点满足 `|s0|≤0.5a`、`|s3|≤0.5a`。

以下情况拒绝拟合并返回可理解的错误：

- 任意点距小于 `0.5 m`；
- 两条直道估计方向夹角偏差超过 `15°`；
- `a ≤ 2.0 m`；
- `R ≤ 5.0 m`；
- 面积、手性、槽位残差或中点约束不满足。

### 2.6 等弧长重采样

默认目标间距 `Δ=1.0 m`：

```text
N = round(L/Δ)
s_i = i·L/N, i=0…N
```

返回 `N+1` 个点和 `N` 个等弧长段，首末点重合。间距判据为：

```text
|L/N - Δ| ≤ 0.01Δ + 0.5Δ/N
```

相邻弧长段互差不超过 `1e-6 m`，首末点距离不超过 `1e-6 m`。重采样结果用于预览和边界显示，不作为等时间隔运动序列。

## 3. 轨迹样本与运动模型

### 3.1 样本结构

`TrackSample` 包含：

- `latitudeDeg`
- `longitudeDeg`
- `elapsedMs`
- `speedMps`
- `paceSecPerKm`
- `cadenceSpm`

速度与配速满足：

```text
|paceSecPerKm - 1000/speedMps| < 1e-9
```

### 3.2 时间和随机源

固定时间步长：

```text
Δt = 0.5 s = 500 ms
```

所有随机过程使用 `java.util.Random`，并拆分为独立子流：

```text
Random(seed)     → 配速噪声
Random(seed + 1) → 步频噪声
Random(seed + 2) → 逐圈横向扰动
```

相同输入和 seed 必须逐点一致，不同 seed 应产生差异。

### 3.3 OU 噪声

噪声结点间隔为 `1.0 s`，结点之间线性插值。令 `g_k=nextGaussian()`、`θ=0.3 s⁻¹`、`ρ=e^{-θ}`：

```text
x0 = clamp(σg0, ±x_max)
raw(k+1) = ρxk + σ√(1-ρ²)g(k+1)
x(k+1) = clamp(xk + clamp(raw(k+1)-xk, ±δ_max), ±x_max)
```

- 配速噪声：`σ=0.02`、`δ_max=0.02`、`x_max=0.05`；
- 步频噪声：`σ=1.5 spm`、`δ_max=1.0 spm`、`x_max=5.0 spm`；
- 高斯样本按结点索引递增顺序消费，不因查询顺序改变结果。

### 3.4 配速模型

基准配速 `p_base∈[180,660] s/km`，默认 `330 s/km`：

```text
p_int = p_base · f_start · f_bend · f_end · (1+ν)
pace = clamp(p_int, 180, 660)
```

- 起步：前 `200 m` 使用 smoothstep，从 `1.15` 过渡到 `1.00`；
- 停步：最后 `150 m` 使用 smoothstep，从 `1.00` 过渡到 `1.35`；
- 弯道：`f_bend=1+0.05w(s)`；
- `w(s)=0.5(1+cos(π·min(1,d_arc(s)/hw)))`；
- `hw=min(8.0 m,0.4a,0.2πR)`；
- 弯道内 `d_arc=0`，直道上为到最近弯道的圈内弧长距离。

标准跑道、`p_base=330`、`seed=42`、三圈序列应满足：

- 配速始终在 `[180,660] s/km`；
- 相邻 `500 ms` 样本的配速差不超过 `35.0 s/km`；
- 前 200 m 平均配速不少于中部直道基准均值的 `1.05` 倍；
- 最后 150 m 平均配速不少于基准均值的 `1.05` 倍；
- 中间一圈弯道平均配速不少于同圈中性直道均值的 `1.03` 倍。

中性直道窗口为 `[hw,2a-hw]` 与 `[2a+πR+hw,4a+πR-hw]`。

### 3.5 步频模型

速度先经过指数移动平均：

```text
v̄(i+1) = v̄i + (1-e^{-Δt/10})(vi-v̄i)
c = clamp(clamp(60v̄/(0.9+0.15v̄),150,200)+η,150,200)
stride = 60v/c
```

应满足：

- `c∈[150,200] spm`；
- 相邻样本 `|Δc|≤4.0 spm`；
- `|v-v̄|≤0.8 m/s`；
- `stride∈[0.45,2.25] m`；
- `|stride-(0.9+0.15v)|≤0.65 m`；
- `|v-stride·c/60|<1e-9`。

### 3.6 逐圈扰动

第 `k` 圈横向谐波：

```text
δk(s) = Σ(m=1…3) [a(k,m)cos(2πms/L) + b(k,m)sin(2πms/L)]
```

系数跨圈使用 `ρ=0.85` 的 AR(1)：

```text
coef0 = σm·g
coefk = ρ·coef(k-1) + σm√(1-ρ²)·g
σ1=0.06 m, σ2=0.04 m, σ3=0.025 m
```

弯道差异项 `d_k` 使用相同 AR(1)，`σ=0.12 m`。每圈严格按 `a1,a2,a3,b1,b2,b3,d` 的顺序消费 7 个高斯样本。

最终偏移：

```text
Δ̂k(s) = 0.55·tanh((δk(s)+dk·w(s))/0.55)
```

因此 `|Δ̂|<0.55 m`。使用 `seed=42` 的六圈标准样本，在 `0.05 m` 网格比较任意两圈时，最大横向差异应不少于 `0.005 m` 且小于 `1.1 m`。

### 3.7 边界与最终序列

允许的横向半宽：

```text
W = min(1.75 m, 0.3R)
```

生成偏移正常情况下不会触及边界；边界函数仍必须把外部输入钳制到 `[-W,+W]`。

轨迹生成顺序：

1. 以 `Δt=0.5 s` 推进时间；
2. 计算配速、速度和步频；
3. 计算圈号与横向扰动；
4. 计算 `C(s)+Δ̂N(s)`；
5. 转回 WGS-84；
6. 输出 `TrackSample`。

最终序列还应满足：

```text
|相邻点距离 - vΔt| ≤ 0.15vΔt + 0.05 m
|曲率| ≤ 1.2/(R-0.55) + 0.02 m⁻¹
```

圈数范围为 `[1,100]`。

## 4. 坐标转换与导出

- 地图输入输出经过 WGS-84 与 GCJ-02 转换；往返误差应小于 `1e-7°`；
- GPX 导出格式为 1.1，包含 `creator`、`trk/trkseg`、每点的 `lat/lon/time`；
- GPX 时间为 `startEpochMs + elapsedMs` 对应的 ISO-8601 时刻；
- GeoJSON 为 `FeatureCollection`，包含一个 `LineString`；坐标顺序为 `[longitude,latitude]`；
- GeoJSON 属性包含时间数组和样本数量；
- 两种导出均保持输入 `List<TrackSample>` 的顺序；
- `MainActivity` 将文件写入 `getExternalFilesDir(null)/exports`；该应用专属 external files 目录不可用时回退到 `filesDir/exports`；
- 导出流程不启动系统文件选择器；应用专属目录中的文件可能随卸载删除，需要保留时必须在卸载前复制到目录之外；
- 空样本列表拒绝导出。

## 5. 隐私与高德 Key

高德 Key 注入顺序：

```text
local.properties 中的 AMAP_API_KEY
    或环境变量 AMAP_API_KEY
→ manifest placeholder
→ AndroidManifest meta-data: com.amap.api.v2.apikey
```

Key 缺失或为空时仍可编译，但界面显示配置提示，不创建地图视图。仓库和发布物不得包含通用 Key。

用户同意隐私说明后、创建 `MapView` 前调用：

```text
MapsInitializer.updatePrivacyShow(context, true, true)
MapsInitializer.updatePrivacyAgree(context, true)
```

隐私同意状态保存在应用私有的 `tracklab_state` 文件中，键为 `consent_agreed`。

## 6. 权限与测试位置输出

运行时权限集合：

- 所有支持版本：精确位置与近似位置；
- Android 13 / API 33 及以上：通知权限；
- Android 14 / API 34 及以上：位置类型前台服务权限。

权限判定顺序为：缺少运行时权限 → 未选择 TrackLab 为模拟位置应用 → 就绪。

测试位置输出只通过 Android `LocationManager` 测试提供者接口，端口调用顺序为：

```text
add → setEnabled(true) → set(sample)… → setEnabled(false) → remove
```

发生 `SecurityException` 时进入明确的引导状态。应用不隐藏 mock 标记，不修改电话、SIM、基站或其他应用状态。

## 7. 回放与持久化

### 7.1 状态机

状态：`IDLE`、`PLAYING`、`PAUSED`、`STOPPED`、`COMPLETED`。

合法迁移：

- `IDLE → PLAYING`：开始；
- `PLAYING → PAUSED`：暂停；
- `PAUSED → PLAYING`：继续；
- `PLAYING|PAUSED → STOPPED`：停止；
- `PLAYING → COMPLETED`：样本耗尽。

其他迁移被拒绝并保持原状态。样本索引、距离和时间只在 `PLAYING` 状态推进。

### 7.2 快照

快照存储在应用私有的 `playback_state` 文件中，键为 `playback_snapshot`。编码格式：

```text
v1|<state>|<sampleIndex>|<distanceM>|<elapsedMs>
```

每次状态迁移以及每 25 个样本保存一次。停止或完成时清除。进程恢复时读取累计值，但状态统一恢复为 `PAUSED`，不会自动继续；损坏快照应被清除且不导致崩溃。

### 7.3 前台服务

- 创建低重要性通知渠道；
- 启动命令同步进入前台，再执行轨迹准备；
- Android 10+ 声明 location 类型；
- 通知操作使用 immutable `PendingIntent`；
- 服务返回 `START_NOT_STICKY`；
- 通知和可选悬浮控件提供暂停、继续与停止；
- 销毁时取消协程、移除测试位置提供者和悬浮控件、停止前台状态并清理会话；
- Android 12+ 后台启动受限时回到主界面，由用户在前台发起。

应用内地图、通知和悬浮控件使用同一回放状态流。UI 收集遵循生命周期，悬浮控件更新必须切换到主线程。

## 8. 悬浮控件几何

布局纯函数必须保证：

1. 所有控件矩形位于视口和 margin 内；
2. 控件互不重叠；
3. 点击区域不小于 48 dp；
4. 同一输入产生同一输出；
5. 视口不足时返回明确错误，不静默生成越界布局。

拖动位置应钳制在可见区域内。未授予“显示在其他应用上层”权限时，不显示悬浮控件，主界面与通知控制仍可使用。

## 9. 本地场景模拟边界

连接场景仅在应用依赖注入层提供 `DEFAULT`、`CELLULAR`、`WIFI`、`OFFLINE` 四种业务状态，不修改系统网络、电话或蜂窝状态。

TrackLab 的技术边界是本应用内预览、导出和 Android 官方测试位置提供者。实现不得加入隐藏模拟位置来源、修改系统鉴别结果、操控第三方应用或使用非公开接口规避系统权限。

## 10. 测试不变量

自动化测试应至少覆盖：

- 六点顺序、手性、退化输入、中心线闭合与接缝连续；
- 局部坐标往返、距离误差、WGS-84/GCJ-02 往返；
- 等弧长重采样数量、间距判据和首尾闭合；
- 配速、步频、步幅、seed 确定性、逐圈差异、边界与曲率；
- GPX/GeoJSON 合法性、必填字段和点序；
- 隐私门、Key 缺失状态、权限门与模拟位置引导；
- 状态机全部迁移、并发串行化、快照损坏与暂停恢复；
- 前台服务基础行为、悬浮控件线程和几何不变量；
- 中英文字符串资源键一致；
- 应用启动、测试标识与主要控制按钮可用。
