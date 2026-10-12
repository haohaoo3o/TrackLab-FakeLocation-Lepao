package io.github.haohaoo3o.tracklab.core.motion

import java.util.Random
import kotlin.math.abs
import kotlin.math.floor

/**
 * 配速剖面（docs/CONTRACTS.md；特征断言窗口）。
 *
 * ```
 * p_int = p_base · f_start · f_bend · f_end · f_rw · (1+ν_eff)
 * pace  = clamp(p_int, 180, 660)        // 唯一夹紧点
 * ```
 *
 * - f_start：前 [MotionContracts.F_START_ZONE_M] 米 smoothstep 1.15→1.00（起步加速：起始更慢）；
 * - f_end：后 [MotionContracts.F_END_ZONE_M] 米 smoothstep 1.00→1.35（停步减速）；
 * - f_bend = 1 + 0.05·w(s)（弯道适度降速；w(s) 见 [BendWeight]，与 LapPerturber 过渡口径同源）；
 * - f_rw：跑走周期：以圈内弧长 s 为自变量、周期为整圈，每圈一个跑段+一个走段。
 *   跑段因子 [MotionContracts.F_RW_RUN]=0.9（快）、走段因子 [MotionContracts.F_RW_WALK]=1.6（慢）；
 *   切换处 smoothstep 过渡，带宽 [MotionContracts.RUN_WALK_BLEND_M]=15m；
 *   走段中心每圈由 seed 派生（相位流 `Random(seed + [MotionContracts.SEED_OFFSET_RW_PHASE])`，
 *   每圈恰消耗 1 个 nextDouble，圈号顺序消耗；走中心 ∈ [0.3L, 0.7L]，走长 0.4L，
 *   过渡带恒不触圈界 ⇒ 圈界处恒为跑段，跨圈连续）；
 * - ν_eff = (1−m)·ν_run + m·ν_walk：快慢各自独立 OU 抖动（复用 [OuNoise]，同 口径；
 *   ν_run 子流 `Random(seed + [MotionContracts.SEED_OFFSET_PACE_RUN])`（seed+3），
 *   ν_walk 子流 `Random(seed + [MotionContracts.SEED_OFFSET_PACE_WALK])`（seed+4）；
 *   两 OU 路各自按结点序单调消耗；m 为跑走混合系数（0=跑，1=走）；旧 seed+0 全局 ν 已由本机制取代）。
 *
 * 连续性（ 推导，标准试样，含跑走过渡；最坏情况重推导见）：
 * |Δpace| ≤ [MotionContracts.PACE_STEP_MAX_DELTA]（实测 max 见附录 A）。
 *
 * 已知行为：p_base 取 180/660 端点时起步/停步特征被硬夹削平；特征断言固定 p_base=330。
 * 跑走调制下特征窗口均值关系见（seed=42 钉死实测）。
 */
class PaceProfile(
    private val pBase: Double,
    private val totalDistanceM: Double,
    private val a: Double,
    private val r: Double,
    seed: Long,
) {

    /** 圈周长 L = 4a + 2πR（由几何现算，禁止硬编码）。 */
    private val lengthM: Double = 4.0 * a + 2.0 * Math.PI * r

    /** ν_run 子流（：Random(seed+3)，快段抖动）。 */
    private val noiseRun = OuNoise(
        Random(seed + MotionContracts.SEED_OFFSET_PACE_RUN),
        MotionContracts.PACE_NOISE_SIGMA,
        MotionContracts.PACE_NOISE_DELTA_MAX,
        MotionContracts.PACE_NOISE_X_MAX
    )

    /** ν_walk 子流（：Random(seed+4)，慢段抖动）。 */
    private val noiseWalk = OuNoise(
        Random(seed + MotionContracts.SEED_OFFSET_PACE_WALK),
        MotionContracts.PACE_NOISE_SIGMA,
        MotionContracts.PACE_NOISE_DELTA_MAX,
        MotionContracts.PACE_NOISE_X_MAX
    )

    /** 跑走相位流（：Random(seed+7)；每圈 1 个 nextDouble，圈号顺序消耗）。 */
    private val phaseRandom = Random(seed + MotionContracts.SEED_OFFSET_RW_PHASE)

    /** 已派生的每圈走中心相位 u_k ∈ [0,1)（按圈号单调延长，保证消耗次序钉死）。 */
    private val phases: MutableList<Double> = ArrayList()

    init {
        require(pBase >= MotionContracts.PACE_MIN_S_PER_KM && pBase <= MotionContracts.PACE_MAX_S_PER_KM) {
            "基准配速 p_base 必须在 [180, 660] s/km（p_base=$pBase）"
        }
        require(totalDistanceM > 0.0) { "总距离必须为正（totalDistanceM=$totalDistanceM）" }
    }

    /**
     * 取值。
     *
     * @param runDistanceM 起点累计跑距（f_start/f_end 自变量；圈号 = ⌊run/L⌋，相位按圈号顺序消耗，
     * 调用方须按跑距非减顺序调用——TrajectoryGenerator/特征推进环满足此条件）
     * @param lapArcS 圈内弧长 s（f_bend/f_rw 自变量，[0, L)）
     * @param elapsedSec 起点累计时刻（ν_run/ν_walk 自变量）
     */
    fun paceAt(runDistanceM: Double, lapArcS: Double, elapsedSec: Double): Double {
        val lapIndex = floor(runDistanceM / lengthM).toInt().coerceAtLeast(0)
        val m = rwMix(lapArcS, lapIndex)
        val fRw = MotionContracts.F_RW_RUN + (MotionContracts.F_RW_WALK - MotionContracts.F_RW_RUN) * m
        val nuEff = (1.0 - m) * noiseRun.value(elapsedSec) + m * noiseWalk.value(elapsedSec)
        val pInt = pBase * fStart(runDistanceM) * fBend(lapArcS) * fEnd(runDistanceM) * fRw * (1.0 + nuEff)
        return pInt.coerceIn(MotionContracts.PACE_MIN_S_PER_KM, MotionContracts.PACE_MAX_S_PER_KM)
    }

    /** 跑段配速噪声 ν_run(t)（；复算/测试直读）。 */
    fun noiseRunAt(elapsedSec: Double): Double = noiseRun.value(elapsedSec)

    /** 走段配速噪声 ν_walk(t)（；复算/测试直读）。 */
    fun noiseWalkAt(elapsedSec: Double): Double = noiseWalk.value(elapsedSec)

    /**
     * 第 [lapIndex] 圈的走中心相位 u_k ∈ [0,1)（seed+7，每圈 1 个 nextDouble，圈号顺序消耗）。
     * 走中心 c_k = L·(0.3 + 0.4·u_k) ∈ [0.3L, 0.7L]。
     */
    fun phaseAt(lapIndex: Int): Double {
        require(lapIndex >= 0) { "圈序号必须非负（lapIndex=$lapIndex）" }
        while (phases.size <= lapIndex) {
            phases.add(phaseRandom.nextDouble())
        }
        return phases[lapIndex]
    }

    /**
     * 跑走混合系数 m(s)（0=跑，1=走；）。
     * 走段为中心 c_k、半长 0.4L/2=0.2L 的 smooth-box，两边各 [MotionContracts.RUN_WALK_BLEND_M]/2 过渡；
     * 走段（含过渡）恒不触圈界 ⇒ m(0)=m(L)=0（圈界恒为跑段，跨圈连续）。
     */
    fun rwMix(lapArcS: Double, lapIndex: Int): Double {
        val c = lengthM * (RW_CENTER_MIN_FRAC + RW_CENTER_SPAN_FRAC * phaseAt(lapIndex))
        val walkHalf = RW_WALK_FRAC * lengthM / 2.0
        val b = MotionContracts.RUN_WALK_BLEND_M / 2.0
        val d = abs(lapArcS - c)
        return when {
            d <= walkHalf - b -> 1.0
            d >= walkHalf + b -> 0.0
            d < walkHalf -> 1.0 - smoothstep((d - (walkHalf - b)) / (2.0 * b))
            else -> smoothstep(((walkHalf + b) - d) / (2.0 * b))
        }
    }

    /** f_rw 跑走因子（测试/复算直读）：F_RUN + (F_WALK−F_RUN)·m。 */
    fun fRunWalk(lapArcS: Double, lapIndex: Int): Double {
        val m = rwMix(lapArcS, lapIndex)
        return MotionContracts.F_RW_RUN + (MotionContracts.F_RW_WALK - MotionContracts.F_RW_RUN) * m
    }

    /** f_start：前 [MotionContracts.F_START_ZONE_M] 米 smoothstep [MotionContracts.F_START_FROM]→[MotionContracts.F_START_TO]。 */
    fun fStart(runDistanceM: Double): Double {
        val x = (runDistanceM / MotionContracts.F_START_ZONE_M).coerceIn(0.0, 1.0)
        return MotionContracts.F_START_FROM +
            (MotionContracts.F_START_TO - MotionContracts.F_START_FROM) * smoothstep(x)
    }

    /** f_end：后 [MotionContracts.F_END_ZONE_M] 米 smoothstep [MotionContracts.F_END_FROM]→[MotionContracts.F_END_TO]（总距 [totalDistanceM] 为终点）。 */
    fun fEnd(runDistanceM: Double): Double {
        val x = ((runDistanceM - (totalDistanceM - MotionContracts.F_END_ZONE_M)) /
            MotionContracts.F_END_ZONE_M).coerceIn(0.0, 1.0)
        return MotionContracts.F_END_FROM +
            (MotionContracts.F_END_TO - MotionContracts.F_END_FROM) * smoothstep(x)
    }

    /** f_bend = 1 + A_b·w(s)（弯道适度降速）。 */
    fun fBend(lapArcS: Double): Double =
        1.0 + MotionContracts.F_BEND_AMPLITUDE * BendWeight.weight(lapArcS, lengthM, a, r)

    companion object {

        /** 走段长度占整圈比例（0.4L；跑段占 0.6L，在环上为一段连续跑弧+一段连续走弧）。 */
        const val RW_WALK_FRAC = 0.4

        /** 走中心下界比例（c_k ≥ 0.3L）。 */
        const val RW_CENTER_MIN_FRAC = 0.3

        /** 走中心跨度比例（c_k ∈ [0.3L, 0.7L]；走段±(0.2L+7.5m)恒不触圈界，标准试样下裕量 ≥ 32m）。 */
        const val RW_CENTER_SPAN_FRAC = 0.4

        /** 标准 smoothstep s(x) = x²(3−2x)（x∈[0,1]；端点 0/1，中点 0.5，max 斜率 1.5）。 */
        fun smoothstep(x: Double): Double {
            val t = x.coerceIn(0.0, 1.0)
            return t * t * (3.0 - 2.0 * t)
        }
    }
}
