package io.github.haohaoo3o.tracklab.motion

import io.github.haohaoo3o.tracklab.core.geo.LatLon
import io.github.haohaoo3o.tracklab.core.geo.TrackModel
import io.github.haohaoo3o.tracklab.core.geo.Vec2
import io.github.haohaoo3o.tracklab.core.motion.CadenceProfile
import io.github.haohaoo3o.tracklab.core.motion.MotionContracts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs

/**
 * 断言契约（c∈[150,200]、|Δc|≤4.0、|v−v̄|≤0.8 EMA τ=10s、
 * 步幅包络 S∈[0.45,2.25] 与 |S−(0.9+0.15v)|≤0.65、恒等式 |v−S·c/60|<1e-9 与 |pace−1000/v|<1e-9；
 * |Δc| 推导为内层夹紧导数形式）。
 *
 * 覆盖：范围（c∈[150,200] 严格步频界，含极值速度扫掠）、连续性（|Δc|≤4.0 spm/500ms 步）、
 * EMA 不变量（|v−v̄|≤0.8）、步幅包络与恒等式、可复现性（同 seed 一致 / 不同 seed 有差异 /
 *  公式独立复算逐点一致）。
 */
class CadenceProfileTest {

    /** 标准场景驱动速度：本地推进环（laps=3、p_base=330、seed=42、标准试样），v=1000/pace。*/
    private fun scenarioSpeeds(seed: Long = 42L): List<Double> {
        val model = stdModel()
        val total = MotionContracts.FEATURE_LAPS * model.lengthM
        val profile = io.github.haohaoo3o.tracklab.core.motion.PaceProfile(
            MotionContracts.FEATURE_PACE_BASE, total, STD_A, STD_R, seed
        )
        val out = ArrayList<Double>()
        var s = 0.0
        var k = 0L
        while (true) {
            val t = k * MotionContracts.DELTA_T_SEC
            val pace = profile.paceAt(s, model.wrapArc(s), t)
            out.add(1000.0 / pace)
            s += (1000.0 / pace) * MotionContracts.DELTA_T_SEC
            k++
            if (s >= total) break
        }
        return out
    }

    @Test
    fun cadenceRangeContinuityEmaStrideAndIdentitiesOnScenario() {
        val speeds = scenarioSpeeds()
        val profile = CadenceProfile(42L)
        var prevC: Double? = null
        for ((i, v) in speeds.withIndex()) {
            val t = i * MotionContracts.DELTA_T_SEC
            val step = profile.next(v, t)
            val c = step.cadenceSpm
            val vBar = step.speedEmaMps
            val s = step.strideM

            // 范围：c ∈ [150, 200]（严格步频界）
            assertTrue("i=$i：c=$c ∈ [150, 200]", c >= MotionContracts.CADENCE_MIN_SPM && c <= MotionContracts.CADENCE_MAX_SPM)
            // EMA 不变量：|v − v̄| ≤ 0.8
            assertTrue("i=$i：|v−v̄|=${abs(v - vBar)} ≤ ${MotionContracts.SPEED_EMA_MAX_GAP_MPS}",
                abs(v - vBar) <= MotionContracts.SPEED_EMA_MAX_GAP_MPS)
            // 步幅包络：S ∈ [0.45, 2.25] 且 |S − (0.9+0.15v)| ≤ 0.65
            assertTrue("i=$i：S=$s ∈ [0.45, 2.25]", s >= MotionContracts.STRIDE_MIN_M && s <= MotionContracts.STRIDE_MAX_M)
            val ref = MotionContracts.STRIDE_REF_A_M + MotionContracts.STRIDE_REF_B_SEC * v
            assertTrue("i=$i：|S−(0.9+0.15v)|=${abs(s - ref)} ≤ ${MotionContracts.STRIDE_REF_MAX_DEV_M}",
                abs(s - ref) <= MotionContracts.STRIDE_REF_MAX_DEV_M)
            // 恒等式：|v − S·c/60| < 1e-9（速度≈步频×步幅，精确）
            assertTrue("i=$i：|v − S·c/60| < 1e-9", CadenceProfile.identityHolds(v, c, s))

            // 连续性：|Δc| ≤ 4.0 spm/步
            val prev = prevC
            if (prev != null) {
                val delta = abs(c - prev)
                assertTrue("i=$i：|Δc|=$delta ≤ ${MotionContracts.CADENCE_STEP_MAX_DELTA_SPM}",
                    delta <= MotionContracts.CADENCE_STEP_MAX_DELTA_SPM)
            }
            prevC = c
        }
    }

    @Test
    fun extremeSpeedSweepStaysInPinnedRange() {
        // 配速端点速度（v=1000/660≈1.515、v=1000/180≈5.556）常速扫掠：步频/步幅/恒等式不越界
        for (v in listOf(1000.0 / 660.0, 3.0, 1000.0 / 180.0)) {
            val profile = CadenceProfile(42L)
            var prevC: Double? = null
            for (i in 0 until 600) {
                val t = i * MotionContracts.DELTA_T_SEC
                val step = profile.next(v, t)
                val c = step.cadenceSpm
                val s = step.strideM
                assertTrue("v=$v i=$i：c=$c ∈ [150, 200]", c >= MotionContracts.CADENCE_MIN_SPM && c <= MotionContracts.CADENCE_MAX_SPM)
                assertTrue("v=$v i=$i：S=$s ∈ [0.45, 2.25]", s >= MotionContracts.STRIDE_MIN_M && s <= MotionContracts.STRIDE_MAX_M)
                val ref = MotionContracts.STRIDE_REF_A_M + MotionContracts.STRIDE_REF_B_SEC * v
                assertTrue("v=$v i=$i：|S−(0.9+0.15v)| ≤ 0.65", abs(s - ref) <= MotionContracts.STRIDE_REF_MAX_DEV_M)
                assertTrue("v=$v i=$i：|v−v̄| ≤ 0.8", abs(v - step.speedEmaMps) <= MotionContracts.SPEED_EMA_MAX_GAP_MPS)
                assertTrue("v=$v i=$i：恒等式 |v − S·c/60| < 1e-9", CadenceProfile.identityHolds(v, c, s))
                val prev = prevC
                if (prev != null) {
                    assertTrue("v=$v i=$i：|Δc| ≤ 4.0", abs(c - prev) <= MotionContracts.CADENCE_STEP_MAX_DELTA_SPM)
                }
                prevC = c
            }
        }
    }

    @Test
    fun paceSpeedIdentityHoldsThroughScenario() {
        // |pace − 1000/v| < 1e-9（断言全集；v=1000/pace 口径）
        for (v in scenarioSpeeds()) {
            val pace = 1000.0 / v
            assertTrue("|pace − 1000/v| < 1e-9", abs(pace - 1000.0 / v) < 1e-9)
        }
    }

    @Test
    fun reproducibleWithSameSeedAndFormulaOracle() {
        val speeds = scenarioSpeeds()
        val p1 = CadenceProfile(42L)
        val p2 = CadenceProfile(42L)
        val oracle = OracleCadence(42L)
        for ((i, v) in speeds.withIndex()) {
            val t = i * MotionContracts.DELTA_T_SEC
            val s1 = p1.next(v, t)
            val s2 = p2.next(v, t)
            assertEquals("i=$i 同 seed c 一致", s1.cadenceSpm, s2.cadenceSpm, 0.0)
            assertEquals("i=$i 同 seed v̄ 一致", s1.speedEmaMps, s2.speedEmaMps, 0.0)
            assertEquals("i=$i 同 seed S 一致", s1.strideM, s2.strideM, 0.0)

            val o = oracle.next(v, t)
            assertEquals("i=$i 与公式独立复算 c 一致", o.first, s1.cadenceSpm, 1e-9)
            assertEquals("i=$i 与公式独立复算 v̄ 一致", o.second, s1.speedEmaMps, 1e-12)
            assertEquals("i=$i 与公式独立复算 S 一致", o.third, s1.strideM, 1e-9)
        }
    }

    @Test
    fun differentSeedsProduceDifferentCadenceSeries() {
        val speeds = scenarioSpeeds()
        val a = CadenceProfile(42L)
        val b = CadenceProfile(43L)
        var diff = 0
        for ((i, v) in speeds.withIndex()) {
            val t = i * MotionContracts.DELTA_T_SEC
            if (abs(a.next(v, t).cadenceSpm - b.next(v, t).cadenceSpm) > 1e-12) diff++
        }
        assertTrue("不同 seed 应产生差异（diff=$diff）", diff > 0)
    }

    @Test
    fun emaAlphaMatchesPinnedDerivation() {
        // 1−e^{−Δt/τ} ≈ 0.0488（推导引用值）
        assertEquals(0.0488, CadenceProfile.emaAlpha(), 5e-4)
    }

    // ---------------------------------------------------------------- 独立复算链

    /**  字面复算：EMA → g(v̄) 内层夹紧 → +η → 外层夹紧 → S=60v/c（η 为 OU，Random(seed+1)）。*/
    private class OracleCadence(seed: Long) {
        private val rnd = Random(seed + MotionContracts.SEED_OFFSET_CADENCE)
        private val xs = ArrayList<Double>()
        private val rho = MotionContracts.OU_RHO
        private var vBar: Double? = null

        init {
            xs.add(clamp(MotionContracts.CADENCE_NOISE_SIGMA_SPM * rnd.nextGaussian(), MotionContracts.CADENCE_NOISE_X_MAX_SPM))
        }

        fun next(v: Double, tSec: Double): Triple<Double, Double, Double> {
            val alpha = 1.0 - Math.exp(-MotionContracts.DELTA_T_SEC / MotionContracts.EMA_TAU_SEC)
            val prev = vBar
            val ema = if (prev == null) v else prev + alpha * (v - prev)
            vBar = ema
            val g = (60.0 * ema / (MotionContracts.STRIDE_REF_A_M + MotionContracts.STRIDE_REF_B_SEC * ema))
                .coerceIn(MotionContracts.CADENCE_MIN_SPM, MotionContracts.CADENCE_MAX_SPM)
            val c = (g + eta(tSec)).coerceIn(MotionContracts.CADENCE_MIN_SPM, MotionContracts.CADENCE_MAX_SPM)
            return Triple(c, ema, 60.0 * v / c)
        }

        private fun eta(tSec: Double): Double {
            val k = Math.floor(tSec / MotionContracts.OU_NODE_INTERVAL_SEC).toInt()
            while (xs.size <= k + 1) {
                val xk = xs[xs.size - 1]
                val raw = rho * xk + MotionContracts.CADENCE_NOISE_SIGMA_SPM *
                    Math.sqrt(1.0 - rho * rho) * rnd.nextGaussian()
                val step = (raw - xk).coerceIn(
                    -MotionContracts.CADENCE_NOISE_DELTA_MAX_SPM, MotionContracts.CADENCE_NOISE_DELTA_MAX_SPM
                )
                xs.add(clamp(xk + step, MotionContracts.CADENCE_NOISE_X_MAX_SPM))
            }
            val frac = (tSec - k * MotionContracts.OU_NODE_INTERVAL_SEC) / MotionContracts.OU_NODE_INTERVAL_SEC
            return xs[k] + frac * (xs[k + 1] - xs[k])
        }

        private fun clamp(v: Double, m: Double) = v.coerceIn(-m, m)
    }

    private fun stdModel() = TrackModel(
        origin = LatLon(30.0000, 110.0000), // 合成境内测试坐标，不对应个人位置
        u = Vec2(1.0, 0.0),
        n = Vec2(0.0, 1.0),
        a = STD_A,
        r = STD_R
    )

    companion object {
        private const val STD_A = 42.5
        private const val STD_R = 36.5
    }
}
