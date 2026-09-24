package io.github.haohaoo3o.tracklab.motion

import io.github.haohaoo3o.tracklab.core.geo.LatLon
import io.github.haohaoo3o.tracklab.core.geo.TrackModel
import io.github.haohaoo3o.tracklab.core.geo.Vec2
import io.github.haohaoo3o.tracklab.core.motion.BendWeight
import io.github.haohaoo3o.tracklab.core.motion.LapPerturber
import io.github.haohaoo3o.tracklab.core.motion.MotionContracts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 断言契约见 docs/CONTRACTS.md（AR(1)+tanh 软钳；|Δ̂|<0.55m；任意两圈 max_s|Δ̂_j−Δ̂_k|
 * ∈[0.005,1.1)——上界为推论，有效断言=下界防逐圈严格重合，且须使用钉死的 seed）。
 *
 * 覆盖：逐圈差异（seed=42 已实测满足下界——附录 A：15 对 min=0.061512、max=0.204339）、
 * |Δ̂|<0.55 软钳界、AR(1) 递推可复现性（含 7 个/圈消耗次序的独立复算）、同 seed 逐点一致 /
 * 不同 seed 有差异、谐波平滑性（Lipschitz 增量界）、走廊内（|Δ̂|<0.55<W）。
 */
class LapPerturberTest {

    // ---------------------------------------------------------------- 逐圈差异（seed=42 钉死）

    @Test
    fun perLapOffsetsDifferButBoundedWithPinnedSeed42() {
        // seed=42 已实测满足下界（实测参数：Random(44)，K=6 圈，网格 0.05m：
        // 15 对两圈差异 min=0.061512、max=0.204339，max|Δ̂|=0.270123）——若 RNG 消耗次序变更须重挑 seed 并回填。
        val p = stdPerturber(42L)
        val l = LENGTH_M
        val grid = MotionContracts.LAP_DIFF_GRID_STEP_M
        val n = Math.ceil(l / grid).toInt()
        val offsets = Array(MotionContracts.LAP_DIFF_TEST_LAPS) { lap ->
            DoubleArray(n) { i -> p.offsetAt(lap, i * grid) }
        }
        var maxAbs = 0.0
        for (k in 0 until MotionContracts.LAP_DIFF_TEST_LAPS) {
            for (i in 0 until n) maxAbs = max(maxAbs, abs(offsets[k][i]))
        }
        var pairMin = Double.MAX_VALUE
        var pairMax = 0.0
        for (j in 0 until MotionContracts.LAP_DIFF_TEST_LAPS) {
            for (k in j + 1 until MotionContracts.LAP_DIFF_TEST_LAPS) {
                var d = 0.0
                for (i in 0 until n) d = max(d, abs(offsets[j][i] - offsets[k][i]))
                assertTrue(
                    "圈 $j vs $k：max_s|Δ̂_j−Δ̂_k|=$d ∈ [${MotionContracts.LAP_DIFF_MIN_M}, ${MotionContracts.LAP_DIFF_MAX_EXCLUSIVE_M})",
                    d >= MotionContracts.LAP_DIFF_MIN_M && d < MotionContracts.LAP_DIFF_MAX_EXCLUSIVE_M
                )
                pairMin = min(pairMin, d)
                pairMax = max(pairMax, d)
            }
        }
        assertTrue("|Δ̂|<0.55：max|Δ̂|=$maxAbs", maxAbs < MotionContracts.SOFT_CLAMP_SCALE_M)
        println("[CALIB] lapPairs min=$pairMin max=$pairMax maxAbs=$maxAbs")
        // 附录 A 实测回归护栏（印刷精度 1e-6）
        assertEquals("15 对差异 min（附录 A 0.061512）", 0.061512, pairMin, 1e-6)
        assertEquals("15 对差异 max（附录 A 0.204339）", 0.204339, pairMax, 1e-6)
        assertEquals("max|Δ̂|（附录 A 0.270123）", 0.270123, maxAbs, 1e-6)
    }

    // ---------------------------------------------------------------- 软钳与走廊界

    @Test
    fun softClampKeepsOffsetUnderScaleAndInsideCorridor() {
        val p = stdPerturber(42L)
        val w = min(MotionContracts.BOUNDARY_W_MAX_M, MotionContracts.BOUNDARY_W_REL_R * STD_R)
        for (lap in 0 until 12) {
            var i = 0
            while (i * 0.05 < LENGTH_M) {
                val s = i * 0.05
                val off = p.offsetAt(lap, s)
                assertTrue("lap=$lap s=$s：|Δ̂|=${abs(off)} < 0.55", abs(off) < MotionContracts.SOFT_CLAMP_SCALE_M)
                // |Δ̂| < 0.55 < W ⇒ 轨迹留在测试边界内
                assertTrue("lap=$lap s=$s：|Δ̂|=$off 在走廊 |t|≤W=$w 内", abs(off) <= w)
                i++
            }
        }
    }

    // ---------------------------------------------------------------- AR(1) 递推可复现性（含消耗次序）

    @Test
    fun coefficientsFollowAr1RecurrenceWithPinnedConsumptionOrder() {
        // 确定性断言（不依赖 seed 挑选）：(a,b,d) 序列严格按递推可复现（含 7 个 nextGaussian/圈消耗次序）
        val seed = 42L
        val p = stdPerturber(seed)
        val rnd = Random(seed + MotionContracts.SEED_OFFSET_LAP)
        val rho = MotionContracts.LAP_AR_RHO
        val sr = Math.sqrt(1.0 - rho * rho)
        var prevA = DoubleArray(MotionContracts.LAP_HARMONICS)
        var prevB = DoubleArray(MotionContracts.LAP_HARMONICS)
        var prevD = 0.0
        for (k in 0 until MotionContracts.LAP_DIFF_TEST_LAPS) {
            val c = p.coefficients(k)
            val a = DoubleArray(MotionContracts.LAP_HARMONICS)
            val b = DoubleArray(MotionContracts.LAP_HARMONICS)
            for (m in 0 until MotionContracts.LAP_HARMONICS) {
                val g = rnd.nextGaussian() // 消耗次序：a_{k,1}, a_{k,2}, a_{k,3}
                a[m] = if (k == 0) MotionContracts.LAP_SIGMA_M[m] * g
                else rho * prevA[m] + MotionContracts.LAP_SIGMA_M[m] * sr * g
            }
            for (m in 0 until MotionContracts.LAP_HARMONICS) {
                val g = rnd.nextGaussian() // 消耗次序：b_{k,1}, b_{k,2}, b_{k,3}
                b[m] = if (k == 0) MotionContracts.LAP_SIGMA_M[m] * g
                else rho * prevB[m] + MotionContracts.LAP_SIGMA_M[m] * sr * g
            }
            val gD = rnd.nextGaussian() // 消耗次序：d_k
            val d = if (k == 0) MotionContracts.LAP_D_SIGMA_M * gD
            else rho * prevD + MotionContracts.LAP_D_SIGMA_M * sr * gD

            for (m in 0 until MotionContracts.LAP_HARMONICS) {
                assertEquals("k=$k a_{k,${m + 1}} 递推可复现", a[m], c.aM[m], 0.0)
                assertEquals("k=$k b_{k,${m + 1}} 递推可复现", b[m], c.bM[m], 0.0)
            }
            assertEquals("k=$k d_k 递推可复现", d, c.bendDiffM, 0.0)
            prevA = a
            prevB = b
            prevD = d
        }

        // δ_k(s) 谐波公式独立复算一致
        for (k in 0 until MotionContracts.LAP_DIFF_TEST_LAPS) {
            val c = p.coefficients(k)
            for (i in 0 until 40) {
                val s = i * LENGTH_M / 40.0
                var expect = 0.0
                for (m in 1..MotionContracts.LAP_HARMONICS) {
                    val phase = 2.0 * Math.PI * m * s / LENGTH_M
                    expect += c.aM[m - 1] * Math.cos(phase) + c.bM[m - 1] * Math.sin(phase)
                }
                assertEquals("k=$k s=$s δ_k(s) 谐波复算", expect, p.harmonicOffset(k, s), 1e-12)
                val soft = MotionContracts.SOFT_CLAMP_SCALE_M *
                    Math.tanh((expect + c.bendDiffM * BendWeight.weight(s, LENGTH_M, STD_A, STD_R)) /
                        MotionContracts.SOFT_CLAMP_SCALE_M)
                assertEquals("k=$k s=$s Δ̂_k(s) 软钳复算", soft, p.offsetAt(k, s), 1e-12)
            }
        }
    }

    @Test
    fun reproducibleWithSameSeedAndDifferentSeedsDiffer() {
        val a1 = stdPerturber(42L)
        val a2 = stdPerturber(42L)
        val b = stdPerturber(43L)
        var diff = 0
        for (k in 0 until 6) {
            for (i in 0 until 400) {
                val s = i * LENGTH_M / 400.0
                val o1 = a1.offsetAt(k, s)
                assertEquals("k=$k i=$i 同 seed 逐点一致", o1, a2.offsetAt(k, s), 0.0)
                if (abs(o1 - b.offsetAt(k, s)) > 1e-12) diff++
            }
        }
        assertTrue("不同 seed 应产生差异（diff=$diff）", diff > 0)
    }

    // ---------------------------------------------------------------- 平滑性（低频谐波：相邻增量 Lipschitz 界）

    @Test
    fun offsetIsSmoothWithinHarmonicLipschitzBound() {
        // Δ̂ = S·tanh(x/S)（1-Lipschitz）⇒ |Δ̂'| ≤ |x'|，|x'| ≤ Σ_m (2πm/L)(|a_m|+|b_m|) + |d|·(π/2hw)
        val p = stdPerturber(42L)
        val hw = BendWeight.halfWidthM(STD_A, STD_R)
        val step = MotionContracts.LAP_DIFF_GRID_STEP_M
        for (k in 0 until MotionContracts.LAP_DIFF_TEST_LAPS) {
            val c = p.coefficients(k)
            var bound = 0.0
            for (m in 1..MotionContracts.LAP_HARMONICS) {
                bound += (2.0 * Math.PI * m / LENGTH_M) * (abs(c.aM[m - 1]) + abs(c.bM[m - 1]))
            }
            bound += abs(c.bendDiffM) * (Math.PI / 2.0) / hw
            var i = 0
            while ((i + 1) * step < LENGTH_M) {
                val d = abs(p.offsetAt(k, (i + 1) * step) - p.offsetAt(k, i * step))
                assertTrue(
                    "k=$k i=$i：增量 $d ≤ 步长 $step × Lipschitz $bound（平滑横向偏移，无逐点白噪声跳变）",
                    d <= step * bound + 1e-9
                )
                i++
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun stdPerturber(seed: Long) = LapPerturber(seed, LENGTH_M, STD_A, STD_R)

    companion object {
        private const val STD_A = 42.5
        private const val STD_R = 36.5
        private val LENGTH_M = 4.0 * STD_A + 2.0 * Math.PI * STD_R
    }
}
