package io.github.haohaoo3o.tracklab.core.motion

import java.util.Random
import kotlin.math.abs

/**
 * 步频剖面（docs/CONTRACTS.md，解耦设计）与步幅派生量。
 *
 * 先对速度做 EMA 平滑（每样本步更新一次，τ=[MotionContracts.EMA_TAU_SEC]）：
 * `v̄ ← v̄ + (1−e^{−Δt/τ})·(v − v̄)`（首步 v̄ = v），再
 *
 * ```
 * c = clamp( clamp(60·v̄/(0.9+0.15·v̄), 150, 200) + η, 150, 200 )
 * S = 60·v / c            // 步幅为派生量，用瞬时 v ⇒ 恒等式精确（TrackSample 不含步幅字段）
 * ```
 *
 * - η：[OuNoise]（子流 `Random(seed + [MotionContracts.SEED_OFFSET_CADENCE])`）。
 * - |Δc| 推导：内层 [150,200] 夹紧使 g(v̄)=60v̄/(0.9+0.15v̄) 的有效导数在
 *   v̄∈(3.6,6) 之外恒为 0，区间内 g'(v̄)=54/(0.9+0.15v̄)² ≤ 26.0 ⇒ |Δc_smooth| ≤ 26.0×0.0488×0.8 ≈ 1.02，
 *   加 |Δη| ≤ 0.5 ⇒ ≤ 1.52 ≤ [MotionContracts.CADENCE_STEP_MAX_DELTA_SPM]=4.0（含裕量）。
 * - 不变量：|v − v̄| ≤ [MotionContracts.SPEED_EMA_MAX_GAP_MPS]；S ∈ [0.45, 2.25]m 且
 *   |S − (0.9+0.15v)| ≤ 0.65m；|v − S·c/60| < 1e-9；|pace − 1000/v| < 1e-9（断言全集）。
 */
class CadenceProfile(seed: Long) {

    /** 单步输出：步频 c（spm）、EMA 速度 v̄（m/s）、步幅 S（m，派生量）。 */
    data class Step(val cadenceSpm: Double, val speedEmaMps: Double, val strideM: Double)

    /** η 子流（§3：Random(seed+1)）。 */
    private val noise = OuNoise(
        Random(seed + MotionContracts.SEED_OFFSET_CADENCE),
        MotionContracts.CADENCE_NOISE_SIGMA_SPM,
        MotionContracts.CADENCE_NOISE_DELTA_MAX_SPM,
        MotionContracts.CADENCE_NOISE_X_MAX_SPM
    )

    /** EMA 状态（首步以 v_0 初始化；未推进前为 null）。 */
    private var emaMps: Double? = null

    /**
     * 每样本步推进：EMA 更新 → 取 c（内层夹紧 + η + 外层夹紧）→ 派生 S = 60v/c。
     *
     * @param speedMps 瞬时速度 v（米/秒，非负）
     * @param elapsedSec 起点累计时刻（η 自变量）
     */
    fun next(speedMps: Double, elapsedSec: Double): Step {
        require(speedMps >= 0.0) { "速度必须非负（speedMps=$speedMps）" }
        val prev = emaMps
        val vBar = if (prev == null) speedMps else prev + emaAlpha() * (speedMps - prev)
        emaMps = vBar
        val eta = noise.value(elapsedSec)
        val cadence = (smoothCadence(vBar) + eta)
            .coerceIn(MotionContracts.CADENCE_MIN_SPM, MotionContracts.CADENCE_MAX_SPM)
        return Step(cadence, vBar, strideOf(speedMps, cadence))
    }

    /** 步频噪声 η(t)（复算/测试直读）。*/
    fun noiseAt(elapsedSec: Double): Double = noise.value(elapsedSec)

    companion object {

        /** EMA 系数 1−e^{−Δt/τ} ≈ 0.0488。*/
        fun emaAlpha(): Double =
            1.0 - Math.exp(-MotionContracts.DELTA_T_SEC / MotionContracts.EMA_TAU_SEC)

        /** 内层平滑步频 g(v̄) = clamp(60·v̄/(0.9+0.15·v̄), 150, 200)（有效导数 ≤ 26.0）。*/
        fun smoothCadence(speedEmaMps: Double): Double {
            val g = 60.0 * speedEmaMps /
                (MotionContracts.STRIDE_REF_A_M + MotionContracts.STRIDE_REF_B_SEC * speedEmaMps)
            return g.coerceIn(MotionContracts.CADENCE_MIN_SPM, MotionContracts.CADENCE_MAX_SPM)
        }

        /** 步幅派生量 S = 60·v/c（用瞬时 v ⇒ |v − S·c/60| < 1e-9）。 */
        fun strideOf(speedMps: Double, cadenceSpm: Double): Double {
            require(cadenceSpm > 0.0) { "步频必须为正（cadenceSpm=$cadenceSpm）" }
            return 60.0 * speedMps / cadenceSpm
        }

        /** 恒等式校验（断言全集）：|v − S·c/60| < [tol]。*/
        fun identityHolds(speedMps: Double, cadenceSpm: Double, strideM: Double, tol: Double = 1e-9): Boolean =
            abs(speedMps - strideM * cadenceSpm / 60.0) < tol
    }
}
