package io.github.haohaoo3o.tracklab.geo

import io.github.haohaoo3o.tracklab.core.geo.LatLon
import io.github.haohaoo3o.tracklab.core.geo.MetricResampler
import io.github.haohaoo3o.tracklab.core.geo.TrackModel
import io.github.haohaoo3o.tracklab.core.geo.Vec2
import io.github.haohaoo3o.tracklab.core.motion.MotionContracts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.abs

/**
 * 断言契约（等弧长构造 s_i=i·L/N、间距判据 |L/N−Δ|≤0.01Δ+0.5Δ/N——
 * 全域成立、闭合 ≤1e-6m）。
 *
 * 覆盖：采样间距（新判据全域 + 标准试样附加宽松 [0.99,1.01]m）、闭合误差（|首−末| 与 |C(0)−C(L)|）、
 * 等弧长构造自检（相邻段长互差 ≤1e-6m）、点间距近似均匀（弦长）、退化输入（deltaM ≤ 0）。
 * 旧判据『间距 ∈[0.99,1.01]m』不得作为全域断言——三组反例用于回归护栏。
 */
class MetricResamplerTest {

    @Test
    fun standardSampleHasUniformArcSpacingAndContractCount() {
        val model = model(STD_A, STD_R)
        val pts = MetricResampler.resample(model)
        val n = pts.size - 1

        // N = round(L/Δ)，N+1 点 N 段
        assertEquals(Math.round(model.lengthM / MotionContracts.RESAMPLE_DELTA_M).toInt(), n)
        assertEquals(n + 1, pts.size)

        // s_i = i·L/N
        for (i in 0..n) {
            assertEquals("s_$i = i·L/N", i * model.lengthM / n, pts[i].arcS, 1e-9)
            assertVecEquals(model.point(pts[i].arcS), pts[i].position, 1e-9)
        }

        // 等弧长构造自检：相邻段长互差 ≤ 1e-6m
        val steps = (0 until n).map { pts[it + 1].arcS - pts[it].arcS }
        for (i in 1 until steps.size) {
            assertTrue(
                "相邻段长互差 ≤ 1e-6m：|Δs_$i − Δs_${i - 1}|=${abs(steps[i] - steps[i - 1])}",
                abs(steps[i] - steps[i - 1]) <= MotionContracts.RESAMPLE_CLOSURE_TOL_M
            )
        }

        // 间距判据（修正版、全域成立）：|L/N − Δ| ≤ 0.01·Δ + 0.5·Δ/N
        val spacing = model.lengthM / n
        assertTrue(
            "间距判据 |L/N−Δ|≤0.01Δ+0.5Δ/N",
            MetricResampler.spacingSatisfiesContract(model.lengthM, pts.size)
        )
        // 标准试样附加宽松断言（仅供回归提示，明示允许）
        assertTrue("标准试样间距 $spacing ∈ [0.99,1.01]m（附加断言）", spacing in 0.99..1.01)

        // 点间距近似均匀：相邻弦长 ∈ [0.99, 1.01]×(L/N)
        for (i in 0 until n) {
            val chord = (pts[i + 1].position - pts[i].position).norm()
            assertTrue(
                "弦长 #$i=$chord 应近似均匀（×L/N=$spacing）",
                chord in 0.99 * spacing..1.01 * spacing
            )
        }
    }

    @Test
    fun closureWithinOneMicrometer() {
        // 闭合误差：|C(0)−C(L)| ≤ 1e-6m（两公式路径）且重采样 |首−末| ≤ 1e-6m
        for ((a, r) in listOf(STD_A to STD_R, 2.2 to 5.2, 30.0 to 25.0)) {
            val model = model(a, r)
            assertTrue(
                "a=$a R=$r：|C(0)−C(L)| ≤ 1e-6m",
                (model.point(0.0) - model.point(model.lengthM)).norm() <= MotionContracts.RESAMPLE_CLOSURE_TOL_M
            )
            val pts = MetricResampler.resample(model)
            assertTrue(
                "a=$a R=$r：|首−末| ≤ 1e-6m",
                (pts.first().position - pts.last().position).norm() <= MotionContracts.RESAMPLE_CLOSURE_TOL_M
            )
            assertEquals(model.lengthM, pts.last().arcS, 1e-9)
        }
    }

    @Test
    fun smallGeometryPassesNewSpacingCriterionOnly() {
        // 间距判据三组反例：旧判据 [0.99,1.01] 全部越界、新判据全部通过——防止回退旧判据
        for ((a, r) in listOf(2.2 to 5.2, 2.01 to 5.01, 2.01 to 5.001)) {
            val model = model(a, r)
            val pts = MetricResampler.resample(model)
            val spacing = model.lengthM / (pts.size - 1)
            assertTrue(
                "a=$a R=$r：新判据必须成立（spacing=$spacing）",
                MetricResampler.spacingSatisfiesContract(model.lengthM, pts.size)
            )
            assertFalse(
                "a=$a R=$r：旧判据 [0.99,1.01] 本例为假（spacing=$spacing），不得作为全域断言",
                spacing in 0.99..1.01
            )
        }
    }

    @Test
    fun degenerateDeltaRejected() {
        val model = model(STD_A, STD_R)
        val e = try {
            MetricResampler.resample(model, 0.0)
            fail("期望 IllegalArgumentException（可读中文错误）")
            throw AssertionError("unreachable")
        } catch (ex: IllegalArgumentException) {
            ex
        }
        assertTrue("应提示步长退化：" + e.message, e.message!!.contains("步长"))
    }

    // ---------------------------------------------------------------- helpers

    @Test
    fun lastSampleExactlyAtLengthAvoidsFpDomainOverflow() {
        // a=2.0、R=8.43 ⇒ L=60.967252139523916、N=round(L)=61；N·(L/N) 因浮点舍入比 L 大约
        // 1 ulp（≈7.1e-15m），直接喂 C(s) 会越域抛错。六点微调后 L 变化即可触发该组合，
        // 故末点必须精确取 L（六点微调链路回归护栏，等弧长构造不变）。
        val model = model(2.0, 8.43)
        val pts = MetricResampler.resample(model)
        val n = pts.size - 1

        // 末点弧长精确等于 L（不得越域）
        assertEquals("末点 s_N 必须精确等于 L", model.lengthM, pts[n].arcS, 0.0)
        // hazard 确实存在：N·(L/N) > L（否则本护栏失效）
        assertTrue(
            "N·(L/N) 应大于 L（fp 舍入）",
            n * model.lengthM / n > model.lengthM
        )
        // 等弧长自检仍成立：相邻段长互差 ≤ 1e-6m
        val steps = (0 until n).map { pts[it + 1].arcS - pts[it].arcS }
        for (i in 1 until steps.size) {
            assertTrue(
                "相邻段长互差 ≤ 1e-6m：|Δs_$i − Δs_${i - 1}|=${abs(steps[i] - steps[i - 1])}",
                abs(steps[i] - steps[i - 1]) <= MotionContracts.RESAMPLE_CLOSURE_TOL_M
            )
        }
    }

    private fun model(a: Double, r: Double) = TrackModel(
        origin = LatLon(30.0000, 110.0000), // 合成境内测试坐标，不对应个人位置
        u = Vec2(1.0, 0.0),
        n = Vec2(0.0, 1.0),
        a = a,
        r = r
    )

    private fun assertVecEquals(expected: Vec2, actual: Vec2, tol: Double) {
        assertTrue("期望 $expected 实际 $actual（容差 $tol）", (expected - actual).norm() <= tol)
    }

    companion object {
        private const val STD_A = 42.5
        private const val STD_R = 36.5
    }
}
