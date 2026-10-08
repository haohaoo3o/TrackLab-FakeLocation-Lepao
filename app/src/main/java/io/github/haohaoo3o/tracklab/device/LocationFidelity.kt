package io.github.haohaoo3o.tracklab.device

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 位置拟真的**无状态纯几何**助手（[LocationFrameSynthesizer] 与 [LocationManagerTestLocationSink] 共用）。
 *
 * 本对象只保留纯数学换算（方位角/平面距离/方位归一化）；帧合成状态与低频相关过程由
 * 有状态、可复现的 [LocationFrameSynthesizer] 统一持有（wall/elapsed/sample 时间同锚、
 * 位移-speed-bearing-500ms 一致、精度/高程为低频相关过程）。行为由 `LocationFidelityTest` 护栏。
 */
object LocationFidelity {

    /**
     * 由相邻两点求初始方位角（度，0=正北，顺时针）。纯数学，等价于
     * `Location.bearingTo` 的地心球面近似；返回 (−180, 180]。
     */
    fun bearingDeg(lat1Deg: Double, lon1Deg: Double, lat2Deg: Double, lon2Deg: Double): Double {
        val lat1 = Math.toRadians(lat1Deg)
        val lat2 = Math.toRadians(lat2Deg)
        val dLon = Math.toRadians(lon2Deg - lon1Deg)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return Math.toDegrees(atan2(y, x))
    }

    /** 平面近似距离（米；等距圆柱投影，km 尺度内误差 <0.1%——位移判据足够）。 */
    fun approxDistanceM(lat1Deg: Double, lon1Deg: Double, lat2Deg: Double, lon2Deg: Double): Double {
        val dLat = Math.toRadians(lat2Deg - lat1Deg)
        val dLon = Math.toRadians(lon2Deg - lon1Deg)
        val latM = Math.toRadians((lat1Deg + lat2Deg) / 2.0)
        val x = dLon * cos(latM)
        val y = dLat
        val chord = sqrt(x * x + y * y)
        return chord * EARTH_RADIUS_M
    }

    /** 方位角归一化到 [0, 360)。 */
    fun normalizeBearingDeg(bearingDeg: Double): Double {
        val wrapped = bearingDeg % 360.0
        return if (wrapped < 0) wrapped + 360.0 else wrapped
    }

    private const val EARTH_RADIUS_M = 6_371_000.0
}
