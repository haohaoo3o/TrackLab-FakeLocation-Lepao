package io.github.haohaoo3o.tracklab.device

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * [LocationFidelity] 的纯 JVM 行为护栏（纯数学换算，收敛行为可测）。
 *
 * 升级后的 [LocationFidelity] 只保留**无状态纯几何**（方位角换算/平面距离/归一化）；
 * 逐帧物理合成（精度/高程/时间锚/速度-位移一致性）已收敛到 [LocationFrameSynthesizer]，
 * 由 `LocationFrameSynthesizerTest` 覆盖。
 */
class LocationFidelityTest {

    private fun assertBearingNear(expected: Double, actual: Double) {
        val diff = abs(LocationFidelity.normalizeBearingDeg(actual - expected))
        val wrapped = if (diff > 180.0) 360.0 - diff else diff
        assertTrue("方位角期望≈$expected 实际=$actual", wrapped <= 1.0)
    }

    @Test
    fun bearing_northIsZero() {
        // 同经线向北 100m ≈ 纬度 +0.0009°
        assertBearingNear(
            0.0,
            LocationFidelity.bearingDeg(31.2, 121.4, 31.2009, 121.4),
        )
    }

    @Test
    fun bearing_eastIsNinety() {
        // 同纬线向东 100m（31.2° 处经度步长略大于 0.0009°）
        val dLon = 100.0 / (111_320.0 * Math.cos(Math.toRadians(31.2)))
        assertBearingNear(
            90.0,
            LocationFidelity.bearingDeg(31.2, 121.4, 31.2, 121.4 + dLon),
        )
    }

    @Test
    fun bearing_southIsPositiveViaNormalization() {
        val b = LocationFidelity.normalizeBearingDeg(
            LocationFidelity.bearingDeg(31.2009, 121.4, 31.2, 121.4),
        )
        assertBearingNear(180.0, b)
    }

    @Test
    fun approxDistance_hundredMetersIsHundredMeters() {
        val d = LocationFidelity.approxDistanceM(31.2, 121.4, 31.2009, 121.4)
        assertTrue("距离期望≈100m 实际=$d", abs(d - 100.0) <= 2.0)
    }
}
