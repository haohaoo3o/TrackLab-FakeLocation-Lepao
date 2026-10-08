package io.github.haohaoo3o.tracklab.geo

import io.github.haohaoo3o.tracklab.core.geo.LatLon
import io.github.haohaoo3o.tracklab.core.geo.LocalTangentPlane
import io.github.haohaoo3o.tracklab.core.geo.TrackFitter
import io.github.haohaoo3o.tracklab.core.geo.TrackPreset
import io.github.haohaoo3o.tracklab.core.geo.TrackPresetLibrary
import io.github.haohaoo3o.tracklab.core.geo.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

/**
 * 预制跑道线路断言（TrackPreset / TrackPresetLibrary；纯 JVM，无 Android 依赖）。
 *
 * 契约口径与 docs/CONTRACTS.md §2.1–§2.5 同源：
 * - 六点顺序固定 p0..p5，恰落在 §2.5 残差槽位 ⇒ TrackFitter 零残差必过、a/R 原值恢复；
 * - u 方位角（headingDeg，自北顺时针）经 fitter 反解的 u/n 基逐一核对（模 180°）；
 * - 整体变换（平移/缩放/旋转）保持几何性质且仍可零残差拟合；
 * - 标准 400m 工厂可放置到任意中心。
 */
class TrackPresetTest {

    private val stdA = 42.5
    private val stdR = 36.5

    // ---------------------------------------------------------------- 正常输入

    @Test
    fun presetSixPointsFitWithZeroResidual() {
        val preset = TrackPreset("t", LatLon(30.0500, 110.0500), stdA, stdR, 181.0)
        val pts = preset.sixPoints()

        // §2.1 固定顺序 6 点；预制 ⇒ fitter 零残差必过（不会抛 IllegalArgumentException）
        val model = TrackFitter.fit(pts)
        assertEquals(stdA, model.a, 1e-6)
        assertEquals(stdR, model.r, 1e-6)
        assertEquals(4.0 * stdA + 2.0 * PI * stdR, model.lengthM, 1e-6)

        // 槽位落点核对：p5 在 s=0、p0 在 s=a、p1 在 s=2a（§2.5 表，ENU 平面）
        val ltp = LocalTangentPlane(preset.center)
        val enu = pts.map { ltp.toEnu2(it) }
        val u = preset.uVec()
        val n = preset.nVec()
        listOf(0.0, stdA, 2.0 * stdA, 3.0 * stdA + PI * stdR, 4.0 * stdA + PI * stdR).forEachIndexed { i, s ->
            val expectedIdx = when (i) {
                0 -> 5; 1 -> 0; 2 -> 1; 3 -> 3; else -> 4
            }
            assertVecEquals(model.point(s), enu[expectedIdx], 1e-6)
        }
        // u/n 与 fitter 反解基一致（预制的 u 即长轴；n = rot90(u)）
        assertVecEquals(u, model.u, 1e-9)
        assertVecEquals(n, model.n, 1e-9)
    }

    @Test
    fun presetRecoversHeadingFromFitterBasis() {
        // 每个方位角下，fitter 反解的 u 与预设 u 平行（模 180° 二义 ⇒ |dot| = 1）
        for (heading in listOf(0.0, 37.5, 90.0, 181.0, 270.0, 359.0)) {
            val preset = TrackPreset("h", LatLon(30.0000, 110.0000), stdA, stdR, heading)
            val model = TrackFitter.fit(preset.sixPoints())
            val dot = abs(preset.uVec().dot(model.u))
            assertEquals("heading=$heading 反解 u 应平行", 1.0, dot, 1e-9)
            assertEquals("heading=$heading 反解 n 应垂直", 0.0, preset.uVec().dot(model.n), 1e-9)
        }
    }

    @Test
    fun standard400FactoryPlacesAtGivenCenter() {
        val center = LatLon(30.0100, 110.0100)
        val preset = TrackPresetLibrary.standard400(center)
        assertEquals(TrackPresetLibrary.ID_STANDARD_400, preset.id)
        assertEquals(center, preset.center)
        assertEquals(0.0, preset.normalizedHeadingDeg(), 1e-9)
        val pts = preset.sixPoints()
        // 六点围绕给定中心：均值应回到中心（LTP 仿射 ⇒ fp 精度）
        val mean = LatLon(
            pts.map { it.latitudeDeg }.average(),
            pts.map { it.longitudeDeg }.average(),
        )
        assertEquals(center.latitudeDeg, mean.latitudeDeg, 1e-9)
        assertEquals(center.longitudeDeg, mean.longitudeDeg, 1e-9)
        // 默认 heading=0 ⇒ u 正北、n 正西：p0 应在中心正西 R 处、p3 正东
        val ltp = LocalTangentPlane(center)
        val p0 = ltp.toEnu2(pts[0])
        val p3 = ltp.toEnu2(pts[3])
        assertVecEquals(Vec2(-stdR, 0.0), p0, 1e-6)
        assertVecEquals(Vec2(stdR, 0.0), p3, 1e-6)
        // 可拟合
        TrackFitter.fit(pts)
    }

    @Test
    fun headingNormalizationAndNegativeAngles() {
        val preset = TrackPreset("n", LatLon(30.0000, 110.0000), stdA, stdR, -179.0)
        assertEquals(181.0, preset.normalizedHeadingDeg(), 1e-9)
        // u 只依赖归一化方位角 ⇒ 与 181° 预设同组六点
        val direct = TrackPreset("n2", LatLon(30.0000, 110.0000), stdA, stdR, 181.0)
        preset.sixPoints().zip(direct.sixPoints()).forEach { (a, b) ->
            assertEquals(a.latitudeDeg, b.latitudeDeg, 1e-12)
            assertEquals(a.longitudeDeg, b.longitudeDeg, 1e-12)
        }
        // 超出 360 同模
        val big = TrackPreset("n3", LatLon(30.0000, 110.0000), stdA, stdR, 541.0)
        assertEquals(181.0, big.normalizedHeadingDeg(), 1e-9)
    }

    // ---------------------------------------------------------------- 整体变换（适配不同跑道）

    @Test
    fun pannedMovesCenterKeepsGeometry() {
        val preset = TrackPreset("t", LatLon(30.0500, 110.0500), stdA, stdR, 181.0)
        val moved = preset.panned(10.0, -5.0) // 东 10m、南 5m

        // 中心按 ENU 平移；a/R/朝向不变
        val ltp = LocalTangentPlane(preset.center)
        val d = ltp.toEnu2(moved.center)
        assertEquals(10.0, d.x, 1e-6)
        assertEquals(-5.0, d.y, 1e-6)
        assertEquals(stdA, moved.straightHalfM, 1e-9)
        assertEquals(stdR, moved.bendRadiusM, 1e-9)
        assertEquals(181.0, moved.normalizedHeadingDeg(), 1e-9)
        // 平移后六点仍与原型同心同构（各点位移量相同；LTP 原点南移带来 cosφ0 二阶误差 ~1e-5m）
        moved.sixPoints().zip(preset.sixPoints()).forEach { (a, b) ->
            val e = ltp.toEnu2(a) - ltp.toEnu2(b)
            assertVecEquals(Vec2(10.0, -5.0), e, 1e-4)
        }
    }

    @Test
    fun scaledKeepsCenterAndHeading() {
        val preset = TrackPreset("t", LatLon(30.0500, 110.0500), stdA, stdR, 90.0)
        val bigger = preset.scaled(1.5)
        assertEquals(stdA * 1.5, bigger.straightHalfM, 1e-9)
        assertEquals(stdR * 1.5, bigger.bendRadiusM, 1e-9)
        assertEquals(preset.center, bigger.center)
        assertEquals(90.0, bigger.normalizedHeadingDeg(), 1e-9)
        // 缩放后周长按比例增长（L=4a+2πR）
        assertEquals(preset.perimeterM() * 1.5, bigger.perimeterM(), 1e-6)
    }

    @Test
    fun rotatedHeadingAdvances() {
        val preset = TrackPreset("t", LatLon(30.0000, 110.0000), stdA, stdR, 10.0)
        assertEquals(25.0, preset.rotated(15.0).normalizedHeadingDeg(), 1e-9)
        assertEquals(350.0, preset.rotated(-20.0).normalizedHeadingDeg(), 1e-9) // 10-20 → 归一化
        // 旋转只改朝向：a/R/中心不变，六点集合同构（距离不变）
        val rotated = preset.rotated(90.0)
        assertEquals(stdA, rotated.straightHalfM, 1e-9)
        assertEquals(preset.center, rotated.center)
    }

    @Test
    fun fromModelRoundTripsFittedTrack() {
        // 拟合模型 → 反推预制参数 → 重生成六点 → 再拟合：a/R/中心/朝向逐位一致
        val preset = TrackPreset("t", LatLon(30.0500, 110.0500), stdA, stdR, 181.0)
        val model = TrackFitter.fit(preset.sixPoints())
        val rebuilt = TrackPreset.fromModel("t", model)

        assertEquals(model.origin, rebuilt.center)
        assertEquals(model.a, rebuilt.straightHalfM, 1e-9)
        assertEquals(model.r, rebuilt.bendRadiusM, 1e-9)
        // 方位角：u 与 −u 同轨 ⇒ 模 180 一致
        val h1 = ((preset.normalizedHeadingDeg() % 180.0) + 180.0) % 180.0
        val h2 = ((rebuilt.normalizedHeadingDeg() % 180.0) + 180.0) % 180.0
        assertEquals(h1, h2, 1e-9)
        // 重生成六点再拟合，几何不变
        val model2 = TrackFitter.fit(rebuilt.sixPoints())
        assertEquals(model.a, model2.a, 1e-9)
        assertEquals(model.r, model2.r, 1e-9)
        // L = 4a + 2πR ⇒ 容差随 a/r 的 1e-9 界传播（(4+2π)·1e-9 ≈ 1.03e-8），取 2e-8。
        assertEquals(model.lengthM, model2.lengthM, 2e-8)
    }

    @Test
    fun transformChainKeepsFitting() {
        // 变换链（移动→缩放→旋转）后六点仍零残差通过拟合
        var preset = TrackPreset("t", LatLon(30.0500, 110.0500), stdA, stdR, 181.0)
        preset = preset.panned(-12.5, 8.0).scaled(0.8).rotated(37.0)
        val model = TrackFitter.fit(preset.sixPoints())
        assertEquals(stdA * 0.8, model.a, 1e-6)
        assertEquals(stdR * 0.8, model.r, 1e-6)
        assertEquals(181.0 + 37.0, preset.normalizedHeadingDeg(), 1e-9)
    }

    @Test
    fun scaledBelowModelBoundRejected() {
        val preset = TrackPreset("t", LatLon(30.0000, 110.0000), stdA, stdR, 0.0)
        // 0.2 ⇒ a=8.5m、R=7.3m 仍合法；再缩一次 a=1.7m≤2.0m ⇒ init 校验抛可读错误
        val small = preset.scaled(0.2)
        assertEquals(stdA * 0.2, small.straightHalfM, 1e-9)
        assertEquals(stdR * 0.2, small.bendRadiusM, 1e-9)
        expectIae("a 退化") { small.scaled(0.2) }
    }

    // ---------------------------------------------------------------- 退化输入

    @Test
    fun presetRejectsDegenerateGeometry() {
        // a ≤ 2.0m / R ≤ 5.0m 与 TrackFitter 同界
        expectIae("a 退化") {
            TrackPreset("bad", LatLon(30.0000, 110.0000), 1.5, 20.0, 0.0)
        }
        expectIae("R 退化") {
            TrackPreset("bad", LatLon(30.0000, 110.0000), 20.0, 3.0, 0.0)
        }
        expectIae("方位角 NaN") {
            TrackPreset("bad", LatLon(30.0000, 110.0000), stdA, stdR, Double.NaN)
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun expectIae(tag: String, block: () -> Unit) {
        try {
            block()
        } catch (e: IllegalArgumentException) {
            assertTrue("错误消息应可读（中文）：${e.message}", !e.message.isNullOrBlank())
            return
        }
        fail("期望 IllegalArgumentException（$tag）")
    }

    private fun assertVecEquals(expected: Vec2, actual: Vec2, tol: Double) {
        assertTrue("期望 $expected 实际 $actual（容差 $tol）", (expected - actual).norm() <= tol)
    }
}
