package io.github.haohaoo3o.tracklab.core.motion

import java.util.Random

/**
 * 配速剖面（docs/CONTRACTS.md；特征断言窗口）。
 *
 * ```
 * p_int = p_base · f_start · f_bend · f_end · (1+ν)
 * pace  = clamp(p_int, 180, 660)        // 唯一夹紧点
 * ```
 *
 * - f_start：前 [MotionContracts.F_START_ZONE_M] 米 smoothstep 1.15→1.00（起步加速：起始更慢）；
 * - f_end：后 [MotionContracts.F_END_ZONE_M] 米 smoothstep 1.00→1.35（停步减速）；
 * - f_bend = 1 + 0.05·w(s)（弯道适度降速；w(s) 见 [BendWeight]，与 LapPerturber 过渡口径同源）；
 * - ν：[OuNoise]（子流 `Random(seed + [MotionContracts.SEED_OFFSET_PACE])`）。
 *
 * 连续性（推导，标准试样）：任意 500ms 步相对变化 ≤ 0.0273（弯道过渡）+ 0.0097（停步段）
 * + 0.0105（噪声） = 0.0475 ⇒ ×660 = 31.4 ⇒ |Δpace| ≤ [MotionContracts.PACE_STEP_MAX_DELTA]（含裕量）。
 *
 * 已知行为：p_base 取 180/660 端点时起步/停步特征被硬夹削平；特征断言固定 p_base=330。
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

    /** ν 子流（§3：Random(seed+0)）。 */
    private val noise = OuNoise(
        Random(seed + MotionContracts.SEED_OFFSET_PACE),
        MotionContracts.PACE_NOISE_SIGMA,
        MotionContracts.PACE_NOISE_DELTA_MAX,
        MotionContracts.PACE_NOISE_X_MAX
    )

    init {
        require(pBase >= MotionContracts.PACE_MIN_S_PER_KM && pBase <= MotionContracts.PACE_MAX_S_PER_KM) {
            "基准配速 p_base 必须在 [180, 660] s/km（p_base=$pBase）"
        }
        require(totalDistanceM > 0.0) { "总距离必须为正（totalDistanceM=$totalDistanceM）" }
    }

    /**
     * 取值。
     *
     * @param runDistanceM 起点累计跑距（f_start/f_end 自变量）
     * @param lapArcS 圈内弧长 s（f_bend 自变量，[0, L)）
     * @param elapsedSec 起点累计时刻（ν 自变量）
     */
    fun paceAt(runDistanceM: Double, lapArcS: Double, elapsedSec: Double): Double {
        val nu = noise.value(elapsedSec)
        val pInt = pBase * fStart(runDistanceM) * fBend(lapArcS) * fEnd(runDistanceM) * (1.0 + nu)
        return pInt.coerceIn(MotionContracts.PACE_MIN_S_PER_KM, MotionContracts.PACE_MAX_S_PER_KM)
    }

    /** 配速噪声 ν(t)（复算/测试直读）。*/
    fun noiseAt(elapsedSec: Double): Double = noise.value(elapsedSec)

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

        /** 标准 smoothstep s(x) = x²(3−2x)（x∈[0,1]；端点 0/1，中点 0.5，max 斜率 1.5）。 */
        fun smoothstep(x: Double): Double {
            val t = x.coerceIn(0.0, 1.0)
            return t * t * (3.0 - 2.0 * t)
        }
    }
}
