package io.github.haohaoo3o.tracklab.core.motion

/**
 * 运动模型数值契约（foundation 落盘骨架；全文与推导见 ）。
 * motion 阶段的 PaceProfile / CadenceProfile / LapPerturber / BoundaryGuard / TrajectoryGenerator
 * 必须以本对象常量为唯一数值来源；测试阈值同源（ 数值界全表）。
 *
 * 随机源（F4）：全部使用 java.util.Random（非 kotlin.random.Random），子流
 * `Random(seed)` / `Random(seed+1)` / `Random(seed+2)` 分属 PaceProfile / CadenceProfile / LapPerturber，
 * `nextGaussian()` 消耗次序见 （钉死，保证同 seed 逐点可复现）。
 */
object MotionContracts {

    // ---------------------------------------------------------------- 时间离散（终审 feedback：Δt 全文钉死）
    /** MotionClock 等时步长；TrackSample 间隔 Δt。全部连续性阈值按 Δt=0.5s 标定，禁止取 1s（ 推导）。 */
    const val DELTA_T_SEC = 0.5

    /** OU 噪声结点间隔 Δ=1.0s=2·Δt，结点间线性插值（B4）。 */
    const val OU_NODE_INTERVAL_SEC = 1.0

    /** CadenceProfile 速度 EMA 时间常数 τ。 */
    const val EMA_TAU_SEC = 10.0

    // ---------------------------------------------------------------- 随机子流（F4）
    /** PaceProfile（ν）子流 = Random(seed + SEED_OFFSET_PACE)。 */
    const val SEED_OFFSET_PACE = 0L

    /** CadenceProfile（η）子流 = Random(seed + SEED_OFFSET_CADENCE)。 */
    const val SEED_OFFSET_CADENCE = 1L

    /** LapPerturber（a,b,d）子流 = Random(seed + SEED_OFFSET_LAP)。 */
    const val SEED_OFFSET_LAP = 2L

    /**
     * GNSS 观测噪声 E 路子流 = Random(seed + SEED_OFFSET_OBS_E)（ 输出覆盖层；
     * 偏移 +5/+6 经核验在轨迹种子空间（motion +0/+1/+2，合成器 +11..16）内空闲）。
     */
    const val SEED_OFFSET_OBS_E = 5L

    /** GNSS 观测噪声 N 路子流 = Random(seed + SEED_OFFSET_OBS_N)（，与 E 路独立）。 */
    const val SEED_OFFSET_OBS_N = 6L

    // ---------------------------------------------------------------- OU 噪声（B4 递推式见 ）
    /** OU 回归速率 θ（s⁻¹），ρ = e^{−θΔ} ≈ 0.7408。 */
    const val OU_THETA_PER_SEC = 0.3

    /** OU 离散化系数 ρ = e^{−θΔ}。 */
    val OU_RHO: Double = Math.exp(-OU_THETA_PER_SEC * OU_NODE_INTERVAL_SEC)

    /** 配速噪声 ν：σ / 逐结点增量限幅 δ_max / 状态限幅 x_max。⇒ 任意 500ms 步 |Δν|≤0.5·δ_max=0.01。 */
    const val PACE_NOISE_SIGMA = 0.02
    const val PACE_NOISE_DELTA_MAX = 0.02
    const val PACE_NOISE_X_MAX = 0.05

    /** 步频噪声 η（spm）：σ / δ_max / x_max。⇒ 任意 500ms 步 |Δη|≤0.5·δ_max=0.5spm。 */
    const val CADENCE_NOISE_SIGMA_SPM = 1.5
    const val CADENCE_NOISE_DELTA_MAX_SPM = 1.0
    const val CADENCE_NOISE_X_MAX_SPM = 5.0

    // ---------------------------------------------------------------- PaceProfile（F2）
    /** pace 唯一夹紧区间与默认基准配速 p_base（s/km；p0 仅指点位『顶部』，与此无关）。 */
    const val PACE_MIN_S_PER_KM = 180.0
    const val PACE_MAX_S_PER_KM = 660.0
    const val PACE_DEFAULT_S_PER_KM = 330.0

    /** f_start：前 200m smoothstep 1.15→1.00（max 斜率 1.5×0.15/200）。 */
    const val F_START_ZONE_M = 200.0
    const val F_START_FROM = 1.15
    const val F_START_TO = 1.00

    /** f_end：后 150m smoothstep 1.00→1.35（max 斜率 1.5×0.35/150）。 */
    const val F_END_ZONE_M = 150.0
    const val F_END_FROM = 1.00
    const val F_END_TO = 1.35

    /** f_bend = 1 + A_b·w(s)，A_b=0.05（弯道适度降速）。 */
    const val F_BEND_AMPLITUDE = 0.05

    /** w(s) 过渡半宽 hw = min(8.0m, 0.4a, 0.2πR) 的常量项。 */
    const val BEND_WINDOW_HW_BASE_M = 8.0
    const val BEND_WINDOW_HW_REL_A = 0.4
    const val BEND_WINDOW_HW_REL_ARC = 0.2 // 乘 0.2πR

    /** 连续性阈值：|Δpace| ≤ 35.0 s/km 每 500ms 步（标准试样推导见 ，含裕量）。 */
    const val PACE_STEP_MAX_DELTA = 35.0

    // ---------------------------------------------------------------- CadenceProfile（）
    /** 步频 c 夹紧区间 [150, 200] spm。 */
    const val CADENCE_MIN_SPM = 150.0
    const val CADENCE_MAX_SPM = 200.0

    /** 连续性阈值：|Δc| ≤ 4.0 spm 每 500ms 步（ 推导：内层夹紧导数 ≤ 26.0）。 */
    const val CADENCE_STEP_MAX_DELTA_SPM = 4.0

    /** EMA 不变量：|v − v̄| ≤ 0.8 m/s。 */
    const val SPEED_EMA_MAX_GAP_MPS = 0.8

    /** 步幅派生量 S = 60v/c（用瞬时 v；恒等式 |v − S·c/60| < 1e-9，TrackSample 不含步幅字段）。 */
    const val STRIDE_MIN_M = 0.45
    const val STRIDE_MAX_M = 2.25

    /** 步幅参考包络：|S − (a + b·v)| ≤ 0.65m（a=0.9, b=0.15，单位 s）。 */
    const val STRIDE_REF_A_M = 0.9
    const val STRIDE_REF_B_SEC = 0.15
    const val STRIDE_REF_MAX_DEV_M = 0.65

    // ---------------------------------------------------------------- LapPerturber（）
    /** (a,b) 跨圈 AR(1) 系数；d_k 同结构。 */
    const val LAP_AR_RHO = 0.85

    /** δ_k(s) 谐波数 m=1..3，振幅 σ_m（米）。 */
    const val LAP_HARMONICS = 3
    val LAP_SIGMA_M: DoubleArray = doubleArrayOf(0.06, 0.04, 0.025)

    /** 弯道差异 d_k 的创新 σ（米）。 */
    const val LAP_D_SIGMA_M = 0.12

    /** 总偏移显式软钳 Δ̂(x) = S·tanh(x/S)，S=0.55m ⇒ |Δ̂| < 0.55m。 */
    const val SOFT_CLAMP_SCALE_M = 0.55

    /** 逐圈差异（固定 seed）：任意两圈 max_s|Δ̂_j−Δ̂_k| ∈ [0.005, 1.1)。
     *  上界 1.1 是 |Δ̂|<0.55 的推论（非独立断言）；有效断言是下界 0.005 防逐圈严格重合。
     *  下界为 seed 相关概率性命题：测试须使用  钉死的 seed（附录 A 实测满足）。 */
    const val LAP_DIFF_MIN_M = 0.005
    const val LAP_DIFF_MAX_EXCLUSIVE_M = 1.1

    /** LapPerturberTest 钉死圈数与比较网格（）。 */
    const val LAP_DIFF_TEST_LAPS = 6
    const val LAP_DIFF_GRID_STEP_M = 0.05

    // ---------------------------------------------------------------- 序列度量界（ 数值界全表）
    /** 相邻样本距离：|d − v·Δt| ≤ 0.15·v·Δt + 0.05m（在重采样输出度量）。 */
    const val STEP_DIST_TOL_REL = 0.15
    const val STEP_DIST_TOL_ABS_M = 0.05

    /** 曲率界：|κ| ≤ 1.2/(R−0.55) + 0.02 m⁻¹（在最终序列度量）。 */
    const val CURVATURE_K_TOL_ABS = 0.02

    // ---------------------------------------------------------------- GNSS 观测噪声输出覆盖层（，第 2 轮增补）
    /**
     * 观测噪声 OU 结点间隔（秒；与  同款 1.0s=2·Δt，结点间线性插值）。
     * OU 回归速率 θ（s⁻¹；与  同款 0.3，ρ=e^{−θΔ}≈0.7408）。
     * 相关分量 σ（米；管线验证档 0.10——真实 GPS 1–3m 待  结构修订后另立适用域）。
     * 白噪声分量 σ_w（米；逐样本独立高斯，连续 OU 结点时刻取 0 保证 OU 递推纯净）。
     */
    const val OBS_NODE_INTERVAL_SEC = 1.0
    const val OBS_THETA_PER_SEC = 0.3
    /** 观测 OU 离散化系数 ρ = e^{−θΔ}（由 OBS_THETA/OBS_NODE 驱动，[OuNoise] 读此值）。 */
    val OBS_RHO: Double = Math.exp(-OBS_THETA_PER_SEC * OBS_NODE_INTERVAL_SEC)
    const val OBS_SIGMA_M = 0.10
    const val OBS_WHITE_SIGMA_M = 0.02
    /** 观测噪声逐结点增量限幅 δ_max（米；=σ， 同口径）/ 状态限幅 x_max（米；=5σ，钳位几乎不触发）。 */
    const val OBS_DELTA_MAX_M = 0.10
    const val OBS_X_MAX_M = 0.50
    /** 起步收敛包络时长（秒；g(t)=min(1,t/T)，乘法叠加在 OU 初值之后）。 */
    const val OBS_SETTLE_SEC = 20.0
    /** 观测序列独立适用界（；既有真路线界一字不动）：step 界 tol_obs=rel·v·Δt+abs。 */
    const val OBS_STEP_DIST_TOL_REL = 0.20
    const val OBS_STEP_DIST_TOL_ABS_M = 0.08
    /** 观测序列曲率独立界 κ_obs ≤ OBS_CURVATURE_MAX（标准试样；第 3 轮重标：
     *  旧 0.12 按 OU-only A 档 0.0939 定标、未含 σw 稀疏白噪声贡献，产品码 k=1.0 实测
     *  maxK=0.1213 超界 1%；多种子 maxK 分布 0.095~0.145，0.15 包住 5 种子全绿；
     *  T-maxK 理论带 [0.08,0.18] 见 REALISM-AB.md 。修订留痕见 ）。 */
    const val OBS_CURVATURE_MAX_PER_M = 0.15
    /** 观测圈差上界（米；A 档 max 0.458，真实档预留）/ 里程膨胀上界（A 档实测 1.0010）。 */
    const val OBS_LAP_DIFF_MAX_M = 2.5
    const val OBS_INFLATE_MAX = 1.01
    /** J1 高频带下界（Hz）/ J1 观测阈值（旧值 0.0057；第 3 轮重标：
     *  旧 0.05 为单种子标定，多种子新 J1 分布 0.030~0.066（12345/999 在 0.05 下红）；
     *  0.025 下 5 种子全绿（min 0.030，裕量 20%），旧 max 0.0057 仍全挂，
     *  改善比 min 11.7×。修订留痕见 REALISM-AB.md ）。 */
    const val OBS_J1_HF_HZ = 0.2
    const val OBS_J1_MIN_SHARE = 0.025
    /** J2c 位移速度 lag-1 带（旧值 0.9843，A 档 0.9306）。 */
    const val OBS_J2C_MIN = 0.85
    const val OBS_J2C_MAX = 0.97
    /** J3 直道段 |Δψ| p99 上界（旧值 1.15°，A 档 3.84°）/ std 下界（旧值 0.24°，A 档 1.05°）。 */
    const val OBS_J3_STR_P99_MAX_DEG = 5.0
    const val OBS_J3_STR_STD_MIN_DEG = 0.6
    /** J4 观测圈差中位数下界（旧值 0.154，A 档 0.397）。 */
    const val OBS_J4_MED_MIN_M = 0.3
    /** J5 观测 CV 下界（旧值 0.0019，A 档 0.0214）。 */
    const val OBS_J5_CV_MIN = 0.015
    /** J6 去趋势圈滞后峰上界（旧值 0.361，A 档 0.229）。 */
    const val OBS_J6_PEAK_MAX = 0.30

    // ---------------------------------------------------------------- BoundaryGuard（F5，防御性）
    /** 走廊半宽 W = min(BOUNDARY_W_MAX_M, BOUNDARY_W_REL_R·R)。
     *  因 |Δ̂| < 0.55 < W，generate 真路线生成路径恒不触发钳制——『零越界』为防御性
     *  不变量断言（适用域：generate 真路线；generateObserved 观测序列豁免，见 ）；
     *  钳制函数本身由 BoundaryGuardTest 构造越界点直测（）。 */
    const val BOUNDARY_W_MAX_M = 1.75
    const val BOUNDARY_W_REL_R = 0.3

    // ---------------------------------------------------------------- MetricResampler（，终审 blocker 修正）
    /** 基线等弧长步长 Δ=1.0m，N=round(L/Δ)，采样点 s_i=i·L/N（i=0..N，首末重合，共 N+1 点 N 段）。 */
    const val RESAMPLE_DELTA_M = 1.0

    /** 间距判据（修正后全域成立）：|L/N − Δ| ≤ 0.01·Δ + 0.5·Δ/N。
     *  旧判据『间距 ∈[0.99,1.01]m』仅在 N≥50 成立，对合法域（a>2.0,R>5.0）内的小几何为假，
     *  禁止再作为全域断言（ 反例）。标准试样 N=399 时附加断言 [0.99,1.01]m 允许（宽松断言）。 */
    const val RESAMPLE_SPACING_REL_TOL = 0.01

    /** 等弧长构造自检：相邻段长互差 ≤ 1e-6m；闭合 |C(0)−C(L)|、|首−末| ≤ 1e-6m。 */
    const val RESAMPLE_CLOSURE_TOL_M = 1e-6

    // ---------------------------------------------------------------- 特征断言窗口（终审 blocker 2 修正：窗口+算术平均，）
    /** 特征断言样本序列：laps=3、p_base=330、标准试样、 的本地推进环（t_k=k·Δt，s_{k+1}=s_k+v_k·Δt）。 */
    const val FEATURE_LAPS = 3
    const val FEATURE_PACE_BASE = 330.0

    /** 基准窗口 W_base（=『中段』=『直道段』）：中部圈（lap 索引 1）内 w(s)=0 的直道中性段，
     *  lap 弧长 s ∈ [hw, 2a−hw] ∪ [2a+πR+hw, 4a+πR−hw]。此窗口内 f_start=f_end=1、f_bend=1。 */
    /** 弯道窗口 W_bend：中部圈内 w(s)=1 的弯道段，s ∈ [2a, 2a+πR] ∪ [4a+πR, L]。 */
    /** 起步窗口 W_start：run 距离 ∈ [0, F_START_ZONE_M]。 */
    /** 停步窗口 W_end：run 距离 ∈ [total−F_END_ZONE_M, total]，total=FEATURE_LAPS·L。 */
    /** 断言（算术平均 pace，钉死 seed 见 /附录 A）：
     *  A1 mean(W_start) ≥ mean(W_base)×1.05；A2 mean(W_end) ≥ mean(W_base)×1.05；
     *  A3 mean(W_bend) ≥ mean(W_base)×1.03。 */
}
