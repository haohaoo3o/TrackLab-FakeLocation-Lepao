package io.github.haohaoo3o.tracklab.core.motion

import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * 逐圈扰动（docs/CONTRACTS.md：AR(1) + tanh 软钳）。
 *
 * - 逐圈横向偏移：δ_k(s) = Σ_{m=1..3} [a_{k,m}·cos(2πms/L) + b_{k,m}·sin(2πms/L)]（平滑谐波，无白噪声）。
 * - (a,b) 跨圈 AR(1)（ρ=[MotionContracts.LAP_AR_RHO]）：`coef_k = ρ·coef_{k−1} + σ_m·√(1−ρ²)·g`，
 *   初值 `σ_m·g`；σ_m=[MotionContracts.LAP_SIGMA_M]。弯道差异 d_k 同 AR(1)
 *   （σ=[MotionContracts.LAP_D_SIGMA_M]，单位米），经 w(s) 注入过渡段差异（与 PaceProfile f_bend 同源）。
 * - 消耗次序（钉死）：每圈 k 依次消耗 7 个 nextGaussian——(a_{k,1}, a_{k,2}, a_{k,3}, b_{k,1}, b_{k,2},
 *   b_{k,3}, d_k)；k=0 为初值，k≥1 为创新。子流 `Random(seed + [MotionContracts.SEED_OFFSET_LAP])`。
 * - 总偏移显式软钳：**Δ̂_k(s) = 0.55·tanh((δ_k(s) + d_k·w(s)) / 0.55)** ⇒ |Δ̂| < 0.55m
 *   （[MotionContracts.SOFT_CLAMP_SCALE_M]）。
 * - 逐圈差异（固定 seed）：任意两圈 max_s|Δ̂_j − Δ̂_k| ∈ [0.005, 1.1) m——上界是 |Δ̂|<0.55 的推论，
 *   有效断言是下界 0.005（防逐圈严格重合）；seed 性质（测试钉死 seed=42）。
 */
class LapPerturber(
    seed: Long,
    private val lengthM: Double,
    private val a: Double,
    private val r: Double,
) {

    /** 第 k 圈的 (a, b, d) 系数（a/b 各 [MotionContracts.LAP_HARMONICS] 个谐波振幅，米）。 */
    data class LapCoefficients(
        val aM: DoubleArray,
        val bM: DoubleArray,
        val bendDiffM: Double,
    )

    /** (a,b,d) 子流（§3：Random(seed+2)）。 */
    private val random = Random(seed + MotionContracts.SEED_OFFSET_LAP)

    /** 已生成的逐圈系数（按圈号单调延长，保证 7 个/圈的消耗次序钉死）。 */
    private val laps: MutableList<LapCoefficients> = ArrayList()

    /**
     * 第 [lapIndex] 圈系数（AR(1) 递推，消耗次序：a_{1..3} → b_{1..3} → d）。
     * k=0 为初值 σ·g，k≥1 为创新 ρ·prev + σ√(1−ρ²)·g。
     */
    fun coefficients(lapIndex: Int): LapCoefficients {
        require(lapIndex >= 0) { "圈序号必须非负（lapIndex=$lapIndex）" }
        while (laps.size <= lapIndex) {
            val prev = laps.lastOrNull()
            val aM = DoubleArray(MotionContracts.LAP_HARMONICS)
            val bM = DoubleArray(MotionContracts.LAP_HARMONICS)
            for (m in 0 until MotionContracts.LAP_HARMONICS) {
                aM[m] = evolve(prev?.aM?.get(m), MotionContracts.LAP_SIGMA_M[m])
            }
            for (m in 0 until MotionContracts.LAP_HARMONICS) {
                bM[m] = evolve(prev?.bM?.get(m), MotionContracts.LAP_SIGMA_M[m])
            }
            val d = evolve(prev?.bendDiffM, MotionContracts.LAP_D_SIGMA_M)
            laps.add(LapCoefficients(aM, bM, d))
        }
        return laps[lapIndex]
    }

    /** 谐波偏移 δ_k(s)（米；未软钳）。 */
    fun harmonicOffset(lapIndex: Int, lapArcS: Double): Double {
        val c = coefficients(lapIndex)
        var sum = 0.0
        for (m in 1..MotionContracts.LAP_HARMONICS) {
            val phase = 2.0 * PI * m * lapArcS / lengthM
            sum += c.aM[m - 1] * cos(phase) + c.bM[m - 1] * sin(phase)
        }
        return sum
    }

    /** 弯道/过渡段差异项 d_k·w(s)（米）。 */
    fun bendTerm(lapIndex: Int, lapArcS: Double): Double =
        coefficients(lapIndex).bendDiffM *
            BendWeight.weight(lapArcS, lengthM, a, r)

    /** 总横向偏移 Δ̂_k(s) = S·tanh((δ_k(s)+d_k·w(s))/S)（S=[MotionContracts.SOFT_CLAMP_SCALE_M]，|Δ̂|<S）。 */
    fun offsetAt(lapIndex: Int, lapArcS: Double): Double {
        val x = harmonicOffset(lapIndex, lapArcS) + bendTerm(lapIndex, lapArcS)
        return MotionContracts.SOFT_CLAMP_SCALE_M *
            tanh(x / MotionContracts.SOFT_CLAMP_SCALE_M)
    }

    /** AR(1) 递推一步（k=0 初值 σ·g；k≥1 创新 ρ·prev + σ√(1−ρ²)·g）。*/
    private fun evolve(prev: Double?, sigma: Double): Double {
        val g = random.nextGaussian()
        return if (prev == null) {
            sigma * g
        } else {
            MotionContracts.LAP_AR_RHO * prev +
                sigma * sqrt(1.0 - MotionContracts.LAP_AR_RHO * MotionContracts.LAP_AR_RHO) * g
        }
    }
}
