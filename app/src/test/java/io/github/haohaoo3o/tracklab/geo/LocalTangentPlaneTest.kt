package io.github.haohaoo3o.tracklab.geo

import io.github.haohaoo3o.tracklab.core.geo.LatLon
import io.github.haohaoo3o.tracklab.core.geo.LocalTangentPlane
import io.github.haohaoo3o.tracklab.core.geo.Vec3
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 断言契约：
 * 1. 原点映射：LLA(O) → ENU (0,0,0)，误差 < 1e-6m；
 * 2. ENU↔LLA 往返 < 1e-6m（水平平移量级 ≤ 1km）；
 * 3. 水平距离保真：≤200m 基线上 ‖ΔENU_horizontal‖ 与 WGS-84 大圆距离相对误差 ≤ 1e-3。
 * 参照大圆距离 = 球面 haversine（R=LocalTangentPlane.EARTH_RADIUS_M，与投影口径自洽）。
 */
class LocalTangentPlaneTest {

    @Test
    fun originMapsToEnuZero() {
        // 断言 1
        val o = LatLon(30.0000, 110.0000, 12.5) // 合成境内测试坐标，不对应个人位置
        val e = LocalTangentPlane(o).toEnu(o)
        assertTrue("LLA(O)→ENU(0,0,0)：$e", e.norm() < 1e-6)
    }

    @Test
    fun enuLlaRoundTripWithin1e6m() {
        // 断言 2：ENU↔LLA 往返 < 1e-6m（水平平移量级 ≤ 1km）
        val o = LatLon(30.0000, 110.0000, 0.0)
        val ltp = LocalTangentPlane(o)
        val enus = listOf(
            Vec3(0.0, 0.0, 0.0),
            Vec3(500.0, -300.0, 25.0),
            Vec3(-1000.0, 1000.0, -40.0),
            Vec3(999.5, 123.456, 0.5),
            Vec3(-123.4, -987.6, 0.0)
        )
        for (e in enus) {
            val back = ltp.toEnu(ltp.toLla(e))
            assertTrue("ENU→LLA→ENU 往返：$e ⇒ $back", dist3(e, back) < 1e-6)
        }
        // 反方向：LLA→ENU→LLA 以米度量
        val llas = listOf(
            LatLon(30.0000, 110.0000, 0.0),
            LatLon(30.0036, 110.0063, 30.0),
            LatLon(29.9906, 109.9863, -10.0)
        )
        for (p in llas) {
            val p2 = ltp.toLla(ltp.toEnu(p))
            assertTrue("LLA→ENU→LLA 往返：$p ⇒ $p2", dist3(ltp.toEnu(p), ltp.toEnu(p2)) < 1e-6)
        }
    }

    @Test
    fun horizontalDistanceFidelityWithin1e3() {
        // 断言 3：≤200m 基线，‖ΔENU_horizontal‖ 与大圆距离相对误差 ≤ 1e-3
        val latitudes = listOf(0.0, 30.0000, 45.0, 60.0, -33.8688)
        val bearings = (0 until 8).map { it * 45.0 }
        val lengths = listOf(50.0, 100.0, 200.0)
        for (lat in latitudes) {
            val lon = 110.0000
            val p = LatLon(lat, lon)
            val ltp = LocalTangentPlane(p)
            for (bearing in bearings) {
                for (d in lengths) {
                    // 目标点独立构造：球面大圆航位推算（不经过本投影）
                    val q = destination(p, d, bearing)
                    val enuDist = ltp.toEnu2(q).norm()
                    val gcDist = haversineM(p, q)
                    assertTrue("gcDist>0（d=$d）", gcDist > 0.0)
                    val rel = kotlin.math.abs(enuDist - gcDist) / gcDist
                    assertTrue(
                        "lat=$lat bearing=$bearing d=$d：相对误差 $rel ≤ 1e-3（ENU=$enuDist gc=$gcDist）",
                        rel <= 1e-3
                    )
                }
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    /** 球面大圆航位推算：从 [p] 沿方位 [bearingDeg]（自北起顺时针）走 [distanceM]。 */
    private fun destination(p: LatLon, distanceM: Double, bearingDeg: Double): LatLon {
        val r = LocalTangentPlane.EARTH_RADIUS_M
        val delta = distanceM / r
        val theta = Math.toRadians(bearingDeg)
        val phi1 = Math.toRadians(p.latitudeDeg)
        val lam1 = Math.toRadians(p.longitudeDeg)
        val phi2 = asin(sin(phi1) * cos(delta) + cos(phi1) * sin(delta) * cos(theta))
        val lam2 = lam1 + atan2(
            sin(theta) * sin(delta) * cos(phi1),
            cos(delta) - sin(phi1) * sin(phi2)
        )
        return LatLon(Math.toDegrees(phi2), Math.toDegrees(lam2), p.altitudeM)
    }

    /** 球面 haversine 大圆距离（R 与投影同口径）。*/
    private fun haversineM(a: LatLon, b: LatLon): Double {
        val r = LocalTangentPlane.EARTH_RADIUS_M
        val phi1 = Math.toRadians(a.latitudeDeg)
        val phi2 = Math.toRadians(b.latitudeDeg)
        val dPhi = phi2 - phi1
        val dLam = Math.toRadians(b.longitudeDeg - a.longitudeDeg)
        val h = sin(dPhi / 2) * sin(dPhi / 2) +
            cos(phi1) * cos(phi2) * sin(dLam / 2) * sin(dLam / 2)
        return 2.0 * r * asin(sqrt(h))
    }

    private fun dist3(a: Vec3, b: Vec3): Double =
        sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y) + (a.z - b.z) * (a.z - b.z))
}
