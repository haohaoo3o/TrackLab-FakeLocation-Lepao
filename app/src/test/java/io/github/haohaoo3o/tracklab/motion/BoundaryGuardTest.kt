package io.github.haohaoo3o.tracklab.motion

import io.github.haohaoo3o.tracklab.core.geo.LatLon
import io.github.haohaoo3o.tracklab.core.geo.TrackModel
import io.github.haohaoo3o.tracklab.core.geo.Vec2
import io.github.haohaoo3o.tracklab.core.motion.BoundaryGuard
import io.github.haohaoo3o.tracklab.core.motion.LapPerturber
import io.github.haohaoo3o.tracklab.core.motion.MotionContracts
import io.github.haohaoo3o.tracklab.core.motion.TrajectoryGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 断言契约（防御性钳制 W=min(1.75m, 0.3R)；生成路径『零越界』
 * 不变量断言 + 构造越界点直测钳制函数）。
 *
 * 覆盖：W=min(1.75, 0.3R) 公式、边界谓词（|t|≤W 含端点）、构造越界残差点直测 clampLateral 钳到边界、
 * 退化输入、生成序列『零越界/零点被钳』防御性不变量（标准试样 seed=42 全序列残差在走廊内且与
 * Δ̂ 逐点一致 ⇒ 硬夹恒为恒等映射）。
 */
class BoundaryGuardTest {

    // ---------------------------------------------------------------- W = min(1.75m, 0.3R)

    @Test
    fun halfWidthIsMinOfPinnedConstantAndRelativeRadius() {
        assertEquals("标准试样 R=36.5：0.3R=10.95 ⇒ W=1.75", 1.75, BoundaryGuard.halfWidth(STD_R), 1e-12)
        assertEquals("小半径 R=5.5：W=0.3R", 1.65, BoundaryGuard.halfWidth(5.5), 1e-12)
        assertEquals("R=5.0+：W=0.3R", 0.3 * 5.01, BoundaryGuard.halfWidth(5.01), 1e-12)
        assertEquals("R=20：W=1.75", MotionContracts.BOUNDARY_W_MAX_M, BoundaryGuard.halfWidth(20.0), 1e-12)
        for (r in listOf(5.01, 5.5, 6.0, 8.0, 36.5, 100.0)) {
            val w = BoundaryGuard.halfWidth(r)
            assertTrue("W=$w ≤ 1.75（R=$r）", w <= MotionContracts.BOUNDARY_W_MAX_M + 1e-12)
            assertEquals("W=min(1.75, 0.3R)（R=$r）",
                minOf(MotionContracts.BOUNDARY_W_MAX_M, MotionContracts.BOUNDARY_W_REL_R * r), w, 1e-12)
        }
    }

    @Test
    fun boundaryPredicateIncludesEndpoints() {
        val r = STD_R
        val w = BoundaryGuard.halfWidth(r)
        assertTrue("t=0 在走廊内", BoundaryGuard.isWithinBoundary(0.0, r))
        assertTrue("t=+W 在走廊内（含端点）", BoundaryGuard.isWithinBoundary(w, r))
        assertTrue("t=−W 在走廊内（含端点）", BoundaryGuard.isWithinBoundary(-w, r))
        assertFalse("t=W+1e-6 越界", BoundaryGuard.isWithinBoundary(w + 1e-6, r))
        assertFalse("t=−(W+0.3) 越界", BoundaryGuard.isWithinBoundary(-w - 0.3, r))
    }

    // ---------------------------------------------------------------- 构造越界点直测钳制函数

    @Test
    fun clampLateralClampsConstructedOutOfRangeResidualsToBoundary() {
        for (r in listOf(STD_R, 5.5, 12.0)) {
            val w = BoundaryGuard.halfWidth(r)
            // 喂入 |t|>W 的残差点 ⇒ 钳到边界
            for (t in listOf(w + 1e-6, w + 0.3, w + 5.0, -w - 1e-6, -w - 1.25, -w - 5.0)) {
                val clamped = BoundaryGuard.clampLateral(t, r)
                assertEquals("t=$t R=$r：钳到边界 ±W", if (t > 0) w else -w, clamped, 0.0)
                assertTrue("钳后必在走廊内", BoundaryGuard.isWithinBoundary(clamped, r))
            }
            // 走廊内的残差点保持不变
            for (t in listOf(0.0, 0.55, -0.55, w, -w, w / 2, -w / 2)) {
                assertEquals("t=$t R=$r：走廊内恒等", t, BoundaryGuard.clampLateral(t, r), 0.0)
            }
        }
    }

    @Test
    fun degenerateRadiusRejected() {
        for (bad in listOf(0.0, -36.5)) {
            try {
                BoundaryGuard.halfWidth(bad)
                throw AssertionError("期望 IllegalArgumentException（R=$bad）")
            } catch (e: IllegalArgumentException) {
                assertTrue("中文可读错误：" + e.message, e.message!!.contains("R"))
            }
        }
    }

    // ---------------------------------------------------------------- 生成路径『零越界』防御性不变量

    @Test
    fun generatedSequenceNeverClampedZeroBoundaryCrossing() {
        // 标准试样、laps=3、p_base=330、seed=42：逐点残差 |t|≤W 且与 Δ̂ 逐点一致 ⇒ 硬夹恒不触发
        val model = stdModel()
        val samples = TrajectoryGenerator().generate(model, 3, MotionContracts.FEATURE_PACE_BASE, 42L)
        val perturber = LapPerturber(42L, model.lengthM, model.a, model.r)
        val w = BoundaryGuard.halfWidth(model.r)
        var run = 0.0
        var clampedCount = 0
        for ((i, smp) in samples.withIndex()) {
            val arc = model.wrapArc(run)
            val lap = Math.floor(run / model.lengthM).toInt().coerceAtMost(2)
            val enu = model.toEnu(LatLon(smp.latitudeDeg, smp.longitudeDeg))
            val residual = (enu - model.point(arc)).dot(model.normal(arc))
            val raw = perturber.offsetAt(lap, arc)
            assertTrue("i=$i：残差 |t|=${abs(residual)} ≤ W=$w（零越界）", abs(residual) <= w + 1e-6)
            assertEquals("i=$i：硬夹未触发（残差=Δ̂）", raw, residual, 1e-6)
            if (abs(BoundaryGuard.clampLateral(raw, model.r) - raw) > 0.0) clampedCount++
            run += smp.speedMps * MotionContracts.DELTA_T_SEC
        }
        assertEquals("生成序列零点被钳（防御性不变量）", 0, clampedCount)
    }

    // ---------------------------------------------------------------- helpers

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
