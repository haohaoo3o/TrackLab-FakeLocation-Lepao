package io.github.haohaoo3o.tracklab.core.geo

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * WGS-84 ↔ GCJ-02 坐标口径转换（docs/CONTRACTS.md）。
 *
 * - 内部/导出/Mock = WGS-84；地图（高德）= GCJ-02。
 * - 正向 [wgs84ToGcj02] 为国家测绘局公开加偏公式（长半轴 6378245、第一偏心率平方 0.00669342162296594323）；
 * - 逆向 [gcj02ToWgs84] 用不动点迭代精确反解，保证往返误差 < 1e-7°（CoordTransformTest 验收行）。
 * - 境外（粗判矩形外）恒等返回（高德在境外不做纠偏）。
 */
object CoordTransform {

    /** GCJ 加偏公式所用克拉索夫斯基椭球常数（与 WGS-84 口径转换的公开实现一致）。 */
    private const val GCJ_A = 6378245.0
    private const val GCJ_EE = 0.00669342162296594323

    /** 逆向不动点迭代次数上限（每次迭代残差收缩 ~1e-2 倍，5 次已远优于 1e-7°）。 */
    private const val INVERSE_ITERATIONS = 8

    /** 逆向收敛阈值（度）。 */
    private const val INVERSE_EPS_DEG = 1e-12

    /** 境内判别（公开实现的粗矩形；境外恒等）。 */
    fun isOutOfChina(p: LatLon): Boolean =
        p.longitudeDeg < 72.004 || p.longitudeDeg > 137.8347 ||
            p.latitudeDeg < 0.8293 || p.latitudeDeg > 55.8271

    /** WGS-84 → GCJ-02（高德地图口径）。境外原样返回。 */
    fun wgs84ToGcj02(p: LatLon): LatLon {
        if (isOutOfChina(p)) return p
        val (dLat, dLon) = offsetDeg(p.latitudeDeg, p.longitudeDeg)
        return LatLon(p.latitudeDeg + dLat, p.longitudeDeg + dLon, p.altitudeM)
    }

    /**
     * GCJ-02 → WGS-84（内部口径）。不动点迭代：w_{k+1} = w_k + (p − gcj(w_k))，
     * 使 wgs84ToGcj02(结果) ≈ p（往返 < 1e-7°）。境外原样返回。
     */
    fun gcj02ToWgs84(p: LatLon): LatLon {
        if (isOutOfChina(p)) return p
        var wLat = p.latitudeDeg
        var wLon = p.longitudeDeg
        repeat(INVERSE_ITERATIONS) {
            val g = wgs84ToGcj02(LatLon(wLat, wLon, p.altitudeM))
            val dLat = p.latitudeDeg - g.latitudeDeg
            val dLon = p.longitudeDeg - g.longitudeDeg
            wLat += dLat
            wLon += dLon
            if (abs(dLat) < INVERSE_EPS_DEG && abs(dLon) < INVERSE_EPS_DEG) return LatLon(wLat, wLon, p.altitudeM)
        }
        return LatLon(wLat, wLon, p.altitudeM)
    }

    private fun offsetDeg(lat: Double, lon: Double): Pair<Double, Double> {
        var dLat = transformLat(lon - 105.0, lat - 35.0)
        var dLon = transformLon(lon - 105.0, lat - 35.0)
        val radLat = Math.toRadians(lat)
        var magic = sin(radLat)
        magic = 1 - GCJ_EE * magic * magic
        val sqrtMagic = sqrt(magic)
        dLat = dLat * 180.0 / ((GCJ_A * (1 - GCJ_EE)) / (magic * sqrtMagic) * Math.PI)
        dLon = dLon * 180.0 / (GCJ_A / sqrtMagic * cos(radLat) * Math.PI)
        return dLat to dLon
    }

    private fun transformLat(x: Double, y: Double): Double {
        var r = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        r += (20.0 * sin(6.0 * x * Math.PI) + 20.0 * sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        r += (20.0 * sin(y * Math.PI) + 40.0 * sin(y / 3.0 * Math.PI)) * 2.0 / 3.0
        r += (160.0 * sin(y / 12.0 * Math.PI) + 320.0 * sin(y * Math.PI / 30.0)) * 2.0 / 3.0
        return r
    }

    private fun transformLon(x: Double, y: Double): Double {
        var r = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        r += (20.0 * sin(6.0 * x * Math.PI) + 20.0 * sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        r += (20.0 * sin(x * Math.PI) + 40.0 * sin(x / 3.0 * Math.PI)) * 2.0 / 3.0
        r += (150.0 * sin(x / 12.0 * Math.PI) + 300.0 * sin(x / 30.0 * Math.PI)) * 2.0 / 3.0
        return r
    }
}
