package io.github.haohaoo3o.tracklab.geo

import io.github.haohaoo3o.tracklab.core.geo.LatLon
import io.github.haohaoo3o.tracklab.core.geo.LocalTangentPlane
import io.github.haohaoo3o.tracklab.core.geo.TrackFitter
import io.github.haohaoo3o.tracklab.core.geo.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.tan

/**
 * 断言契约（六点顺序/手性/残差槽位/中点约束/退化条件）。
 * 覆盖：正常输入、带少量误差输入、退化输入（六类可读错误）、闭合误差 |C(0)−C(L)|≤1e-6m、
 * 平滑过渡（直线与两端圆弧接缝处值与一阶导连续）。
 *
 * 数值口径：标准试样 a=42.5m、R=36.5m；L 一律由 4a+2πR 现算（禁止硬编码 398.8）。
 */
class TrackFitterTest {

    // ---------------------------------------------------------------- 正常输入

    @Test
    fun fitIdealSixPointsRecoversContractGeometry() {
        val a = STD_A
        val r = STD_R
        val model = TrackFitter.fit(idealPoints(a, r))

        // a/R/L（L=4a+2πR ⇒ 标准试样 399.336m，实算禁止硬编码）
        assertEquals(a, model.a, 1e-6)
        assertEquals(r, model.r, 1e-6)
        assertEquals(4.0 * a + 2.0 * PI * r, model.lengthM, 1e-6)

        // 残差槽位 (ŝ,t̂) 的 C(s) 落点（见表）：0→p5、a→p0、2a→p1、2a+πR→p2、3a+πR→p3、4a+πR→p4
        val piR = PI * r
        val slot = slots(a, r)
        assertVecEquals(slot[5], model.point(0.0), 1e-6)                  // p5 (+a,+R)
        assertVecEquals(slot[0], model.point(a), 1e-6)                    // p0 (0,+R)
        assertVecEquals(slot[1], model.point(2.0 * a), 1e-6)              // p1 (−a,+R)
        assertVecEquals(slot[2], model.point(2.0 * a + piR), 1e-6)        // p2 (−a,−R)
        assertVecEquals(slot[3], model.point(3.0 * a + piR), 1e-6)        // p3 (0,−R)
        assertVecEquals(slot[4], model.point(4.0 * a + piR), 1e-6)        // p4 (+a,−R)
        assertVecEquals(slot[5], model.point(model.lengthM), 1e-6)        // p5 = C(L)

        // 外法向（见表）：直道 ±n、弯道 α/β 端部与最值处
        assertVecEquals(Vec2(0.0, 1.0), model.normal(0.0), 1e-9)                        // ① +n
        assertVecEquals(Vec2(0.0, 1.0), model.normal(2.0 * a), 1e-9)                    // ② α=0
        assertVecEquals(Vec2(-1.0, 0.0), model.normal(2.0 * a + piR / 2.0), 1e-9)       // ② α=π/2 最左
        assertVecEquals(Vec2(0.0, -1.0), model.normal(2.0 * a + piR), 1e-9)             // ②→③ α=π
        assertVecEquals(Vec2(0.0, -1.0), model.normal(3.0 * a + piR), 1e-9)             // ③ −n
        assertVecEquals(Vec2(1.0, 0.0), model.normal(4.0 * a + piR + piR / 2.0), 1e-9)  // ④ β=π/2 最右
        for (k in 0..100) {
            assertEquals(1.0, model.normal(k * model.lengthM / 100.0).norm(), 1e-9)
        }
    }

    @Test
    fun fitSlightNoiseStaysWithinTolerance() {
        // 带少量误差输入：槽位 + ≤0.35m 确定性扰动 ⇒ 拟合通过、a/R 偏移 < 0.5m、闭合仍成立
        val a = STD_A
        val r = STD_R
        val noise = listOf(
            Vec2(0.20, -0.30), Vec2(-0.10, 0.25), Vec2(0.30, 0.15),
            Vec2(-0.25, -0.20), Vec2(0.15, -0.30), Vec2(-0.20, 0.20)
        )
        val ltp = LocalTangentPlane(ORIGIN)
        val noisy = idealPoints(a, r).mapIndexed { i, p -> ltp.toLla2(ltp.toEnu2(p) + noise[i]) }
        val model = TrackFitter.fit(noisy)
        assertEquals(a, model.a, 0.5)
        assertEquals(r, model.r, 0.5)
        assertTrue("闭合误差 |C(0)−C(L)| ≤ 1e-6m", (model.point(0.0) - model.point(model.lengthM)).norm() <= 1e-6)
    }

    @Test
    fun closureAndFirstDerivativeContinuityAtSeams() {
        // 闭合误差 + 平滑过渡（直线与两端圆弧接缝处值/一阶导连续，|C′|=1）
        val model = TrackFitter.fit(idealPoints(STD_A, STD_R))
        val h = 1e-4
        val seams = listOf(model.topStraightEndM, model.leftBendEndM, model.bottomStraightEndM)

        for (s in seams) {
            // 值连续 + 单位速率（Lipschitz 1）
            assertTrue(
                "s=$s 接缝值连续",
                (model.point(s + h) - model.point(s - h)).norm() <= 2.0 * h * 1.001 + 1e-9
            )
            // 一阶导（切向）连续
            val tMinus = (model.point(s) - model.point(s - h)).normalized()
            val tPlus = (model.point(s + h) - model.point(s)).normalized()
            assertTrue("s=$s 接缝切向连续", (tPlus - tMinus).norm() <= 1e-3)
            // 法向连续
            assertTrue("s=$s 接缝法向连续", (model.normal(s + h) - model.normal(s - h)).norm() <= 1e-3)
            // |C′| = 1（中心差分）
            val speed = (model.point(s + h) - model.point(s - h)).norm() / (2.0 * h)
            assertTrue("s=$s 处 |C′|=1", abs(speed - 1.0) <= 1e-3)
        }

        // 闭合接缝 0/L（两条公式路径求值）
        assertTrue(
            "|C(0)−C(L)| ≤ 1e-6m",
            (model.point(0.0) - model.point(model.lengthM)).norm() <= 1e-6
        )
        val t0Plus = (model.point(h) - model.point(0.0)).normalized()
        val lMinus = (model.point(model.lengthM) - model.point(model.lengthM - h)).normalized()
        assertTrue("闭合接缝切向连续", (t0Plus - lMinus).norm() <= 1e-3)
        assertTrue(
            "闭合接缝法向连续",
            (model.normal(model.lengthM) - model.normal(0.0)).norm() <= 1e-3
        )
    }

    // ---------------------------------------------------------------- 退化输入（六类可读错误）

    @Test
    fun fitRejectsWrongPointCount() {
        val e = expectIllegalArgument(idealPoints(STD_A, STD_R).take(5))
        assertTrue("应提示 6 点要求：" + e.message, e.message!!.contains("6 个点位"))
    }

    @Test
    fun fitRejectsCoincidentPoints() {
        // 点距 < 0.5m：把 p3 挪到距 p2 仅 0.1m
        val p = idealPoints(STD_A, STD_R).toMutableList()
        val ltp = LocalTangentPlane(ORIGIN)
        p[3] = ltp.toLla2(Vec2(-STD_A + 0.1, -STD_R))
        val e = expectIllegalArgument(p)
        assertTrue("应提示点距过小：" + e.message, e.message!!.contains("点距过小"))
    }

    @Test
    fun fitRejectsNonParallelChords() {
        // 平行角 > 15°：p2p4 连线旋转 20°（p1p5 不动）
        val a = STD_A
        val r = STD_R
        val ltp = LocalTangentPlane(ORIGIN)
        val p = idealPoints(a, r).toMutableList()
        p[4] = ltp.toLla2(Vec2(a, -r + 2.0 * a * tan(Math.toRadians(20.0))))
        val e = expectIllegalArgument(p)
        assertTrue("应提示连线不平行：" + e.message, e.message!!.contains("不平行"))
    }

    @Test
    fun fitRejectsMirroredOrder() {
        // 镜像乱序（整体对 u 轴翻转）⇒ A<0 ⇒『点序绕向错误或点位退化』
        val ltp = LocalTangentPlane(ORIGIN)
        val mirrored = idealPoints(STD_A, STD_R).map {
            val v = ltp.toEnu2(it)
            ltp.toLla2(Vec2(v.x, -v.y))
        }
        val e = expectIllegalArgument(mirrored)
        assertTrue("镜像应被手性检出：" + e.message, e.message!!.contains("点序绕向错误"))
    }

    @Test
    fun fitRejectsScrambledOrder() {
        // 乱序：p0 与 p3 互换（点集不变、绕向塌缩 A=0）⇒ 同样被手性检出
        val p = idealPoints(STD_A, STD_R).toMutableList()
        val tmp = p[0]
        p[0] = p[3]
        p[3] = tmp
        val e = expectIllegalArgument(p)
        assertTrue("乱序应被手性检出：" + e.message, e.message!!.contains("点序绕向错误"))
    }

    @Test
    fun fitRejectsTinyStraight() {
        // a ≤ 2.0m
        val e = expectIllegalArgument(idealPoints(1.5, 20.0))
        assertTrue("应提示直道半长退化：" + e.message, e.message!!.contains("直道半长"))
    }

    @Test
    fun fitRejectsTinyRadius() {
        // R ≤ 5.0m
        val e = expectIllegalArgument(idealPoints(10.0, 3.0))
        assertTrue("应提示弯道半径退化：" + e.message, e.message!!.contains("弯道半径"))
    }

    @Test
    fun fitRejectsResidualOverflow() {
        // 残差超限：p0 下移 4m ⇒ |t0−t̂0| ≈ 2.67m > tol=2.0m（其余点残差仍在 tol 内）
        val a = STD_A
        val r = STD_R
        val ltp = LocalTangentPlane(ORIGIN)
        val p = idealPoints(a, r).toMutableList()
        p[0] = ltp.toLla2(Vec2(0.0, r - 4.0))
        val e = expectIllegalArgument(p)
        assertTrue("应提示残差超限：" + e.message, e.message!!.contains("点位残差超限"))
    }

    @Test
    fun fitRejectsOffMidpointTopBottom() {
        // 中点约束：p0/p3 沿 u 对向平移 0.55a（O 不动、残差全零）⇒『顶部/底部应标记在直道中点附近』
        val a = STD_A
        val r = STD_R
        val ltp = LocalTangentPlane(ORIGIN)
        val p = idealPoints(a, r).toMutableList()
        p[0] = ltp.toLla2(Vec2(0.55 * a, r))
        p[3] = ltp.toLla2(Vec2(-0.55 * a, -r))
        val e = expectIllegalArgument(p)
        assertTrue("应提示中点约束：" + e.message, e.message!!.contains("直道中点"))
    }

    // ---------------------------------------------------------------- helpers

    private fun expectIllegalArgument(points: List<LatLon>): IllegalArgumentException {
        try {
            TrackFitter.fit(points)
        } catch (e: IllegalArgumentException) {
            return e
        }
        fail("期望 IllegalArgumentException（可读中文错误），实际拟合成功")
        throw AssertionError("unreachable")
    }

    private fun assertVecEquals(expected: Vec2, actual: Vec2, tol: Double) {
        assertTrue("期望 $expected 实际 $actual（容差 $tol）", (expected - actual).norm() <= tol)
    }

    companion object {

        /** 拟合原点（任意；投影为仿射映射 ⇒ 六点均值与之重合到 fp 精度）。 */
        private val ORIGIN = LatLon(30.0000, 110.0000) // 合成境内测试坐标，不对应个人位置

        private const val STD_A = 42.5
        private const val STD_R = 36.5

        /** 残差槽位 ENU 坐标 (ŝ,t̂)：p0(0,+R) p1(−a,+R) p2(−a,−R) p3(0,−R) p4(+a,−R) p5(+a,+R)。*/
        private fun slots(a: Double, r: Double): List<Vec2> = listOf(
            Vec2(0.0, r), Vec2(-a, r), Vec2(-a, -r),
            Vec2(0.0, -r), Vec2(a, -r), Vec2(a, r)
        )

        /** 槽位理想六点（固定顺序 p0..p5，WGS-84）。 */
        private fun idealPoints(a: Double, r: Double): List<LatLon> {
            val ltp = LocalTangentPlane(ORIGIN)
            return slots(a, r).map { ltp.toLla2(it) }
        }
    }
}
