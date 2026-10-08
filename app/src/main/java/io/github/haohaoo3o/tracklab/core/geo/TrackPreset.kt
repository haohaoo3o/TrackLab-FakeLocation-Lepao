package io.github.haohaoo3o.tracklab.core.geo

import kotlin.math.cos
import kotlin.math.sin

/**
 * 预制跑道线路：一次生成六点（WGS-84，§2.1 固定顺序），**绕开手点六点的残差校验失败**
 * —— 六点恰落在 [TrackModel] 残差槽位上，[TrackFitter] 零残差必过；用户再在地图上
 * 拖动六个 Marker 微调，使其贴合实际跑道（适配后仍走同一 [TrackFitter] 校验，不放宽任何阈值）。
 *
 * 参数口径（与 §2.2–§2.3 同源，全部 WGS-84 + ENU 平面米）：
 * - [center] = 跑道中心 = 六点均值 = [TrackModel.origin]（O）；
 * - [straightHalfM] = a（直道半长）、[bendRadiusM] = R（弯道半径）；
 * - [headingDeg] = 长轴（u）方位角，自正北顺时针（度）。u = (sinθ, cosθ)（东、北分量），
 *   n = rot90(u)（§2.2）。槽位：p0(0,+R)、p1(−a,+R)、p2(−a,−R)、p3(0,−R)、
 *   p4(+a,−R)、p5(+a,+R)。
 *
 * headingDeg 的 180° 二义性：u 与 −u 生成的六点集合相同（n 同步翻转，槽位不变），
 * 故方位角只需模 180° 有意义。
 */
data class TrackPreset(
    val id: String,
    val center: LatLon,
    val straightHalfM: Double,
    val bendRadiusM: Double,
    val headingDeg: Double,
) {

    init {
        require(straightHalfM > TrackFitter.MIN_STRAIGHT_HALF_M) {
            "预制跑道直道半长 a=${straightHalfM}m（≤ ${TrackFitter.MIN_STRAIGHT_HALF_M}m），退化"
        }
        require(bendRadiusM > TrackFitter.MIN_BEND_RADIUS_M) {
            "预制跑道弯道半径 R=${bendRadiusM}m（≤ ${TrackFitter.MIN_BEND_RADIUS_M}m），退化"
        }
        require(headingDeg.isFinite()) { "预制跑道方位角必须有限：$headingDeg" }
    }

    /** u 轴方位角归一化到 [0, 360)。 */
    fun normalizedHeadingDeg(): Double {
        val m = headingDeg % 360.0
        return if (m < 0.0) m + 360.0 else m
    }

    /** u（东,北）单位向量（ENU 平面）。 */
    fun uVec(): Vec2 {
        val h = Math.toRadians(normalizedHeadingDeg())
        return Vec2(sin(h), cos(h))
    }

    /** n = rot90(u)（§2.2）。 */
    fun nVec(): Vec2 = uVec().rot90()

    /**
     * 六点（WGS-84，§2.1 固定顺序 p0 顶部 → p5 右上切点）。
     * ENU：p_i = s_i·u + t_i·n，槽位 (s,t) = p0(0,+R) p1(−a,+R) p2(−a,−R) p3(0,−R) p4(+a,−R) p5(+a,+R)。
     */
    fun sixPoints(): List<LatLon> {
        val ltp = LocalTangentPlane(center)
        val u = uVec()
        val n = nVec()
        val s = doubleArrayOf(0.0, -straightHalfM, -straightHalfM, 0.0, straightHalfM, straightHalfM)
        val t = doubleArrayOf(bendRadiusM, bendRadiusM, -bendRadiusM, -bendRadiusM, -bendRadiusM, bendRadiusM)
        return s.indices.map { i -> ltp.toLla2(u * s[i] + n * t[i]) }
    }

    /** 周长 L = 4a + 2πR（与 [TrackModel] 同公式；仅展示/预估用）。 */
    fun perimeterM(): Double = 4.0 * straightHalfM + 2.0 * Math.PI * bendRadiusM

    // ---------------------------------------------------------------- 整体变换（适配不同跑道）

    /**
     * 平移 [dEastM]（东正）/ [dNorthM]（北正）米：中心沿 ENU 平面移动（LTP 仿射，
     * a/R/朝向不变）。用于把预制跑道整体挪到实际跑道上。
     */
    fun panned(dEastM: Double, dNorthM: Double): TrackPreset {
        val ltp = LocalTangentPlane(center)
        return copy(center = ltp.toLla2(Vec2(dEastM, dNorthM)))
    }

    /** 整体缩放 [factor]（a/R 同比例；>1 放大）。退化结果由 init 校验拒绝（a>2m/R>5m）。 */
    fun scaled(factor: Double): TrackPreset = copy(
        straightHalfM = straightHalfM * factor,
        bendRadiusM = bendRadiusM * factor,
    )

    /** 旋转 [deltaDeg]（方位角增量，度；正=顺时针）。a/R/中心不变。 */
    fun rotated(deltaDeg: Double): TrackPreset =
        copy(headingDeg = normalizedHeadingDeg() + deltaDeg)

    companion object {

        /**
         * 从已拟合的 [TrackModel] 反推预制参数：中心 = 六点均值（[TrackModel.origin]）、
         * a/R 原值、u 轴方位角 = atan2(u.x, u.y)（自正北顺时针）。整体变换的基准
         * ——对任意合法六点（预制或手点）都成立，变换后再按槽位重生成六点。
         */
        fun fromModel(id: String, model: TrackModel): TrackPreset = TrackPreset(
            id = id,
            center = model.origin,
            straightHalfM = model.a,
            bendRadiusM = model.r,
            headingDeg = Math.toDegrees(kotlin.math.atan2(model.u.x, model.u.y)),
        )
    }
}

/**
 * 预制跑道库：提供 [standard400] 工厂——在调用方给定的地图中心放置一条标准 400m
 * 跑道（a=42.5m、R=36.5m，默认长轴正南北）。载入后可用「整体变换」（移动/缩放/旋转）
 * 粗调，再拖动六个编号 Marker 微调，使六点贴合实际跑道（拟合校验不放宽）。
 *
 * UI 展示名由调用方按 [id] 映射字符串资源（core 不依赖 Android res，保持纯 JVM 可测）。
 */
object TrackPresetLibrary {

    /** 标准 400m 跑道（放置到调用方给定的地图中心）。 */
    const val ID_STANDARD_400 = "standard_400"

    /** 标准 400m 跑道（放置到 [center]，默认长轴正南北；用户随后拖动六点/变换适配实际跑道）。 */
    fun standard400(center: LatLon, headingDeg: Double = 0.0): TrackPreset = TrackPreset(
        id = ID_STANDARD_400,
        center = center,
        straightHalfM = 42.5,
        bendRadiusM = 36.5,
        headingDeg = headingDeg,
    )
}
