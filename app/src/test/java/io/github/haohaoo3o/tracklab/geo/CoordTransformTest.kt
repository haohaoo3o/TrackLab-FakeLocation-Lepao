package io.github.haohaoo3o.tracklab.geo

import io.github.haohaoo3o.tracklab.core.geo.CoordTransform
import io.github.haohaoo3o.tracklab.core.geo.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 断言契约（GCJ-02↔WGS-84 往返 <1e-7°；口径：内部/导出/Mock=WGS-84、
 * 地图=GCJ-02）。
 */
class CoordTransformTest {

    /** 境内采样点（含边远地区，覆盖加偏公式全域）。 */
    private val insideChina = listOf(
        LatLon(30.0000, 110.0000), // 合成境内测试坐标，不对应个人位置
        LatLon(39.9042, 116.4074),  // 北京
        LatLon(43.8256, 87.6168),   // 乌鲁木齐
        LatLon(29.6520, 91.1721),   // 拉萨
        LatLon(45.8038, 126.5349),  // 哈尔滨
        LatLon(23.1291, 113.2644)   // 广州
    )

    /** 境外采样点（粗判矩形外 ⇒ 恒等）。 */
    private val outsideChina = listOf(
        LatLon(35.6895, 139.6917),  // 东京
        LatLon(51.5074, -0.1278),   // 伦敦
        LatLon(-33.8688, 151.2093), // 悉尼
        LatLon(0.0, 0.0)
    )

    @Test
    fun wgs84Gcj02RoundTripWithin1e7Deg() {
        // 验收行：GCJ↔WGS 往返 < 1e-7°
        for (w in insideChina) {
            val w2 = CoordTransform.gcj02ToWgs84(CoordTransform.wgs84ToGcj02(w))
            assertDegClose(w, w2, 1e-7, "WGS→GCJ→WGS")
        }
    }

    @Test
    fun gcj02Wgs84RoundTripWithin1e7Deg() {
        for (g in insideChina) {
            val g2 = CoordTransform.wgs84ToGcj02(CoordTransform.gcj02ToWgs84(g))
            assertDegClose(g, g2, 1e-7, "GCJ→WGS→GCJ")
        }
    }

    @Test
    fun insideChinaTransformIsNotIdentity() {
        // 境内确实加偏（sanity：偏移量 ~1e-3° 量级，远大于 1e-6°）
        for (w in insideChina) {
            val g = CoordTransform.wgs84ToGcj02(w)
            assertTrue(
                "境内应有加偏：$w ⇒ $g",
                abs(g.latitudeDeg - w.latitudeDeg) > 1e-6 || abs(g.longitudeDeg - w.longitudeDeg) > 1e-6
            )
        }
    }

    @Test
    fun outsideChinaIsIdentity() {
        // 境外恒等（含高程字段）
        for (p in outsideChina) {
            assertEquals(p, CoordTransform.wgs84ToGcj02(p))
            assertEquals(p, CoordTransform.gcj02ToWgs84(p))
        }
    }

    @Test
    fun altitudeIsPreserved() {
        val w = LatLon(30.0000, 110.0000, 42.5) // 合成境内测试坐标，不对应个人位置
        assertEquals(42.5, CoordTransform.wgs84ToGcj02(w).altitudeM, 0.0)
        assertEquals(42.5, CoordTransform.gcj02ToWgs84(w).altitudeM, 0.0)
    }

    private fun assertDegClose(a: LatLon, b: LatLon, tolDeg: Double, what: String) {
        assertTrue("$what 纬度往返 < $tolDeg°：${a.latitudeDeg} vs ${b.latitudeDeg}",
            abs(a.latitudeDeg - b.latitudeDeg) < tolDeg)
        assertTrue("$what 经度往返 < $tolDeg°：${a.longitudeDeg} vs ${b.longitudeDeg}",
            abs(a.longitudeDeg - b.longitudeDeg) < tolDeg)
    }
}
