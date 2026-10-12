package io.github.haohaoo3o.tracklab.core.motion

import java.util.Random
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * GNSS 观测噪声输出覆盖层（，第 2 轮增补；纯 JVM，无 Android 依赖）。
 *
 * - 物理模型：观测 ENU = 真 ENU + n(t)，n(t) = OU 相关分量 + 白噪声分量（两路：E/N 独立）。
 *   相关分量走与  同款 OU 离散化（结点间隔 [MotionContracts.OBS_NODE_INTERVAL_SEC]=1.0s，
 *   结点间线性插值，递推 `x_{k+1} = clamp(x_k + clamp(ρ·x_k + σ√(1−ρ²)·g − x_k, ±δ_max), ±x_max)`，
 *   ρ=[MotionContracts.OBS_RHO]（由 OBS_THETA/OBS_NODE 驱动，非装饰常量，C3）；
 *   [OuNoise] 的 interval/rho 形参由 OBS 常量显式传入，改 θ/Δ 即生效）；
 *   白噪声分量为**奇样本独立** `w_i = σ_w·g_i`（σ_w=[MotionContracts.OBS_WHITE_SIGMA_M]），
 *   在 OU 结点时刻（偶数样本 i=2k）取 0（保证 OU 递推纯净、可与 [OuNoise] 逐项对账；C3）。
 * - 首样本指纹：i=0 时 envelope(0)=0 且 white(0)=0 ⇒ n(0)=0，观测首样本恒等于真值
 *   （实测 firstSampleDiffM=0.0，见 REALISM-AB.md  对账行；非缺陷，为确定性初值）。
 * - 随机源（F4）：E 路 `Random(seed + [MotionContracts.SEED_OFFSET_OBS_E])`（+5）、
 *   N 路 `Random(seed + [MotionContracts.SEED_OFFSET_OBS_N])`（+6）——两路**独立偏移**，
 *   经核验在轨迹种子空间（motion +0/+1/+2/+3/+4/+7（含跑走档新增），合成器 +11..16）内空闲；每路内部 OU 与白噪声
 *   共用一路 Random，按「OU 先、每结点 1 个 g；白噪声后、每样本 1 个 g（结点时刻跳过）」
 *   的固定次序消耗（防同 seed 分叉，barrier4）。
 * - 起步收敛包络：`g(t) = min(1, t/[MotionContracts.OBS_SETTLE_SEC])`，乘法叠加在 OU 初值
 *   之后（`n_corr(t) = g(t)·ou(t)`；白噪声分量不包络——模拟接收机起步瞬态，N=20s 钉死）。
 * - E/N 互相关系数：两路独立 Random ⇒ 期望 0（由测试断言 |r|<0.2 实证）。
 * - 可复现：OU 结点按时间单调消耗（[OuNoise] 同款），`eastAt/northAt(t)` 任意查询序下
 *   同 seed 逐点可复现；白噪声按样本索引单调消耗，`whiteAt(i)` 同 seed 逐点可复现。
 */
class GnssObservationNoise(seed: Long) {

    /** E 路 OU（子流 seed+5；σ/δ_max/x_max/θ/结点间隔全取 MotionContracts OBS 常量，C3）。 */
    private val ouEast = OuNoise(
        Random(seed + MotionContracts.SEED_OFFSET_OBS_E),
        MotionContracts.OBS_SIGMA_M,
        MotionContracts.OBS_DELTA_MAX_M,
        MotionContracts.OBS_X_MAX_M,
        MotionContracts.OBS_NODE_INTERVAL_SEC,
        MotionContracts.OBS_RHO,
    )

    /** N 路 OU（子流 seed+6，与 E 路独立）。 */
    private val ouNorth = OuNoise(
        Random(seed + MotionContracts.SEED_OFFSET_OBS_N),
        MotionContracts.OBS_SIGMA_M,
        MotionContracts.OBS_DELTA_MAX_M,
        MotionContracts.OBS_X_MAX_M,
        MotionContracts.OBS_NODE_INTERVAL_SEC,
        MotionContracts.OBS_RHO,
    )

    /**
     * 白噪声流（与 OU **同一路 Random**：OU 构造期已消耗 g_0（[OuNoise.init]），
     * 白噪声从该路的下一个 g 开始、按样本索引单调消耗；OU 结点时刻（t=k·Δ_node，
     * 即偶数样本 i=2k）取 0——该时刻的 g 配额归 OU 结点递推，保证 OU 递推纯净）。
     * 注意：[OuNoise] 不暴露其内部 Random，故白噪声用**同种子**的第二路 Random
     * 实现：`Random(seed+OFF)` 的 g 序列与 OU 路的 g 序列逐项一致，白噪声取其中
     * 第 i 个（i 从 1 开始，跳过 OU 已消耗的 g_0；偶数样本取 0 不消耗）。
     * 同 seed 逐点可复现（含跳过规则），不同 seed 有差异。
     */
    private val whiteEast = WhiteStream(seed + MotionContracts.SEED_OFFSET_OBS_E)
    private val whiteNorth = WhiteStream(seed + MotionContracts.SEED_OFFSET_OBS_N)

    /** 起步收敛包络 g(t) = min(1, t/OBS_SETTLE_SEC)（t≥0）。 */
    fun envelope(tSec: Double): Double {
        require(tSec >= 0.0) { "观测噪声时间必须非负（t=$tSec）" }
        return minOf(1.0, tSec / MotionContracts.OBS_SETTLE_SEC)
    }

    /** E 路总噪声 n_E(t, i) = g(t)·ou_E(t) + w_E(i)（米；t=样本时刻秒，i=样本索引）。 */
    fun eastAt(tSec: Double, sampleIndex: Long): Double {
        require(tSec >= 0.0) { "观测噪声时间必须非负（t=$tSec）" }
        require(sampleIndex >= 0L) { "样本索引必须非负（i=$sampleIndex）" }
        return envelope(tSec) * ouEast.value(tSec) + whiteEast.at(sampleIndex)
    }

    /** N 路总噪声 n_N(t, i) = g(t)·ou_N(t) + w_N(i)（米）。 */
    fun northAt(tSec: Double, sampleIndex: Long): Double {
        require(tSec >= 0.0) { "观测噪声时间必须非负（t=$tSec）" }
        require(sampleIndex >= 0L) { "样本索引必须非负（i=$sampleIndex）" }
        return envelope(tSec) * ouNorth.value(tSec) + whiteNorth.at(sampleIndex)
    }

    /** E 路 OU 相关分量（测试/对账直读；含包络）。 */
    fun correlatedEastAt(tSec: Double): Double = envelope(tSec) * ouEast.value(tSec)

    /** N 路 OU 相关分量（测试/对账直读；含包络）。 */
    fun correlatedNorthAt(tSec: Double): Double = envelope(tSec) * ouNorth.value(tSec)

    /** OU 结点值直读（E 路；测试复算可直读）。 */
    fun nodeEastAt(index: Int): Double = ouEast.nodeAt(index)

    /** OU 结点值直读（N 路；测试复算可直读）。 */
    fun nodeNorthAt(index: Int): Double = ouNorth.nodeAt(index)

    /**
     * 白噪声子流：**奇样本独立**、偶数样本（OU 结点时刻 i=2k）置零（C3 名实修正：
     * 非逐样本独立白谱，而是 1Hz 斩波稀疏白噪声；J1 对此敏感，见 REALISM-AB.md ）。
     * 与同种子 OU 路逐项一致的 g 序列（跳过 g_0），`at(i)`（i≥0）：奇数 i 消耗一个
     * nextGaussian 并返回 σ_w·g；偶数 i 返回 0.0 不消耗。
     * 按样本索引单调延长 ⇒ 同 seed 任意查询序逐点可复现（内部按最大已查询索引推进）。
     */
    private class WhiteStream(seed: Long) {
        private val random = Random(seed)
        private val values = ArrayList<Double>()

        init {
            // 跳过 g_0（OU 构造期 x_0 = clamp(σ·g_0) 已消耗）。
            random.nextGaussian()
            // values[k] 对应样本 i=2k+1（奇数样本）；偶数样本恒 0 不存储。
            values.add(random.nextGaussian() * MotionContracts.OBS_WHITE_SIGMA_M)
        }

        fun at(sampleIndex: Long): Double {
            require(sampleIndex >= 0L) { "样本索引必须非负（i=$sampleIndex）" }
            if (sampleIndex % 2L == 0L) return 0.0
            val k = (sampleIndex / 2L).toInt()
            while (values.size <= k) {
                values.add(random.nextGaussian() * MotionContracts.OBS_WHITE_SIGMA_M)
            }
            return values[k]
        }
    }
}
