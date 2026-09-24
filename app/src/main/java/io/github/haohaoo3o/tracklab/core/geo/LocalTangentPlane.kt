package io.github.haohaoo3o.tracklab.core.geo

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 大地坐标（WGS-84）。内部/导出/Mock 一律此口径；地图显示经 [CoordTransform] 转 GCJ-02
 *（docs/CONTRACTS.md）。[altitudeM] 为椭球高（米），跑道场景恒为 0，仅用于 ENU 第三分量往返。
 */
data class LatLon(val latitudeDeg: Double, val longitudeDeg: Double, val altitudeM: Double = 0.0)

/** 局部 ENU 平面坐标（米；x=东、y=北）。跑道几何、C(s)、残差 (s,t) 均在此平面运算。 */
data class Vec2(val x: Double, val y: Double) {

    operator fun plus(o: Vec2): Vec2 = Vec2(x + o.x, y + o.y)

    operator fun minus(o: Vec2): Vec2 = Vec2(x - o.x, y - o.y)

    operator fun times(k: Double): Vec2 = Vec2(x * k, y * k)

    operator fun unaryMinus(): Vec2 = Vec2(-x, -y)

    fun dot(o: Vec2): Double = x * o.x + y * o.y

    /** z 分量叉积（>0 表示 o 在本向量逆时针侧）。 */
    fun cross(o: Vec2): Double = x * o.y - y * o.x

    fun norm(): Double = sqrt(x * x + y * y)

    fun normalized(): Vec2 {
        val n = norm()
        require(n > 0.0) { "零向量不可归一化（点位退化）" }
        return Vec2(x / n, y / n)
    }

    /** 逆时针旋转 90°：(−y, x)（CONTRACTS 的 rot90）。*/
    fun rot90(): Vec2 = Vec2(-y, x)
}

/** 局部 ENU 三维坐标（米；x=东、y=北、z=天）。z 仅做高程差往返，不参与跑道平面几何。 */
data class Vec3(val x: Double, val y: Double, val z: Double) {

    fun norm(): Double = sqrt(x * x + y * y + z * z)
}

/**
 * 局部切平面（LTP）→ENU 等距近似投影（docs/CONTRACTS.md）。
 *
 * 公式（球面等距近似，R=[EARTH_RADIUS_M]）：
 * ```
 * x = R·cos(φ0)·(λ − λ0)   y = R·(φ − φ0)   z = h − h0
 * φ = φ0 + y/R             λ = λ0 + x/(R·cosφ0)   h = h0 + z
 * ```
 * - 显式可逆 ⇒ 原点映射 (0,0,0) 精确、ENU↔LLA 往返 < 1e-6m（验收行 1/2）；
 * - 与球面大圆距离（同 R 的 haversine）在 ≤200m 基线上相对误差 ≤ 1e-3（验收行 3）。
 *
 * 有效性：纬度 |φ| < 80°（cosφ0 不塌缩）、经度差取 (−π, π] 主值（跨 180° 经线安全）。
 * 这是『等距近似』而非椭球 ECEF 旋转——跑道几百米尺度下与大圆口径自洽（KDoc 同步 CONTRACTS）。
 */
class LocalTangentPlane(val origin: LatLon) {

    private val lat0 = Math.toRadians(origin.latitudeDeg)
    private val lon0 = Math.toRadians(origin.longitudeDeg)
    private val cosLat0 = cos(lat0)

    init {
        require(abs(cosLat0) > 1e-6) {
            "局部切平面在极点退化（origin 纬度 ${origin.latitudeDeg}°，|cos φ0| 过小）"
        }
    }

    /** LLA → ENU（米）。 */
    fun toEnu(p: LatLon): Vec3 {
        val dLon = wrapPi(Math.toRadians(p.longitudeDeg) - lon0)
        val dLat = Math.toRadians(p.latitudeDeg) - lat0
        return Vec3(
            EARTH_RADIUS_M * cosLat0 * dLon,
            EARTH_RADIUS_M * dLat,
            p.altitudeM - origin.altitudeM
        )
    }

    /** ENU（米）→ LLA。与 [toEnu] 互逆（往返 < 1e-6m）。*/
    fun toLla(e: Vec3): LatLon {
        val lat = lat0 + e.y / EARTH_RADIUS_M
        val lon = lon0 + e.x / (EARTH_RADIUS_M * cosLat0)
        return LatLon(Math.toDegrees(lat), wrapPiDeg(Math.toDegrees(lon)), origin.altitudeM + e.z)
    }

    /** 水平 ENU（跑道平面用）。 */
    fun toEnu2(p: LatLon): Vec2 {
        val e = toEnu(p)
        return Vec2(e.x, e.y)
    }

    /** 水平平面坐标 → LLA（高程取原点高程）。 */
    fun toLla2(v: Vec2): LatLon = toLla(Vec3(v.x, v.y, 0.0))

    companion object {

        /** 等距近似球半径 = WGS-84 平均半径 (2a+b)/3（米）。大圆距离同用此 R，口径自洽。*/
        const val EARTH_RADIUS_M = 6371008.8

        /** 经度差主值 (−π, π]。 */
        internal fun wrapPi(rad: Double): Double {
            var v = rad
            while (v <= -Math.PI) v += 2 * Math.PI
            while (v > Math.PI) v -= 2 * Math.PI
            return v
        }

        private fun wrapPiDeg(deg: Double): Double = Math.toDegrees(wrapPi(Math.toRadians(deg)))
    }
}
