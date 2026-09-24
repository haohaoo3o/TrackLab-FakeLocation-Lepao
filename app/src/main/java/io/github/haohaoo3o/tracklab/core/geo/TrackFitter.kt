package io.github.haohaoo3o.tracklab.core.geo

import kotlin.math.abs
import kotlin.math.max

/**
 * 六点拟合器（docs/CONTRACTS.md）。
 *
 * 六点顺序固定：p0 顶部、p1 左上切点、p2 左下切点、p3 底部、p4 右下切点、p5 右上切点。
 * 流程：LLA→ENU（O=六点均值）→ 局部基 u=normalize(normalize(p5−p1)+normalize(p4−p2))（左→右）、
 * n=rot90(u) → 手性校验（A>1.0m² 且 (p0−O)·n>0）→ a=(s5+s4−s1−s2)/4、R=(t0+t1+t5−t2−t3−t4)/6
 * → 残差槽位 (ŝ,t̂) 校验 → 中点约束。
 *
 * 全部退化输入抛带中文消息的 [IllegalArgumentException]（校验次序钉死，逐项独立可测）：
 * 点距 < 0.5m → 平行角 > 15° → A ≤ 1.0m²（镜像/乱序 ⇒『点序绕向错误或点位退化』）→ (p0−O)·n ≤ 0
 * → a ≤ 2.0m / R ≤ 5.0m → 残差超限 → 『顶部/底部应标记在直道中点附近』。
 */
object TrackFitter {

    /** 点距下限（任意两点对，米）。 */
    const val MIN_POINT_DISTANCE_M = 0.5

    /** p1p5 与 p2p4 连线平行角上限（度）。 */
    const val MAX_PARALLEL_ANGLE_DEG = 15.0

    /** ENU 有向面积下限（m²）：A ≤ 该值 ⇒ 点序绕向错误或点位退化。 */
    const val MIN_SIGNED_AREA_M2 = 1.0

    /** 直道半长 a 下限（米）：a ≤ 2.0 ⇒ 退化。 */
    const val MIN_STRAIGHT_HALF_M = 2.0

    /** 弯道半径 R 下限（米）：R ≤ 5.0 ⇒ 退化。 */
    const val MIN_BEND_RADIUS_M = 5.0

    /** 残差容差 tol = max(RESIDUAL_TOL_BASE_M, RESIDUAL_TOL_REL_R·R)。*/
    const val RESIDUAL_TOL_BASE_M = 2.0
    const val RESIDUAL_TOL_REL_R = 0.02

    /** 中点约束：|s0| > 0.5a 或 |s3| > 0.5a ⇒ 『顶部/底部应标记在直道中点附近』。 */
    const val MIDPOINT_S_REL_A = 0.5

    /** 残差容差 tol（米）。 */
    fun residualTol(r: Double): Double = max(RESIDUAL_TOL_BASE_M, RESIDUAL_TOL_REL_R * r)

    /**
     * 六点（固定顺序，WGS-84）→ [TrackModel]。输入必须恰为 6 点；退化输入抛中文 [IllegalArgumentException]。
     */
    fun fit(points: List<LatLon>): TrackModel {
        require(points.size == 6) {
            "必须提供 6 个点位（顶部/左上切点/左下切点/底部/右下切点/右上切点），实际 ${points.size} 个"
        }

        // LTP 原点 O = 六点均值
        val origin = LatLon(
            points.map { it.latitudeDeg }.average(),
            points.map { it.longitudeDeg }.average(),
            points.map { it.altitudeM }.average()
        )
        val ltp = LocalTangentPlane(origin)
        val p = points.map { ltp.toEnu2(it) }

        // ① 点距 < 0.5m ⇒ 退化
        for (i in 0 until 6) {
            for (j in i + 1 until 6) {
                val d = (p[i] - p[j]).norm()
                require(d >= MIN_POINT_DISTANCE_M) {
                    "点距过小：p$i 与 p$j 相距 %.3fm（< %.1fm），点位退化".format(d, MIN_POINT_DISTANCE_M)
                }
            }
        }

        // ② 平行角：normalize(p5−p1) 与 normalize(p4−p2) 夹角 ≤ 15°（局部基前提）
        val v1 = (p[5] - p[1]).normalized()
        val v2 = (p[4] - p[2]).normalized()
        val angleDeg = Math.toDegrees(
            kotlin.math.acos(v1.dot(v2).coerceIn(-1.0, 1.0))
        )
        require(angleDeg <= MAX_PARALLEL_ANGLE_DEG) {
            "两侧切点连线不平行（夹角 %.2f° > %.0f°），点位退化".format(angleDeg, MAX_PARALLEL_ANGLE_DEG)
        }

        // 局部基：u=normalize(normalize(p5−p1)+normalize(p4−p2))（左→右），n=rot90(u)
        val u = (v1 + v2).normalized()
        val n = u.rot90()

        // ③ 手性：ENU 有向面积 A > 1.0m²（i 模 6）；镜像/乱序 ⇒ A<0 ⇒ 可读错误
        var area2 = 0.0
        for (i in 0 until 6) {
            area2 += p[i].cross(p[(i + 1) % 6])
        }
        val area = 0.5 * area2
        require(area > MIN_SIGNED_AREA_M2) {
            "点序绕向错误或点位退化（ENU 有向面积 A=%.4fm² ≤ %.1fm²）".format(area, MIN_SIGNED_AREA_M2)
        }

        // ④ (p0−O)·n > 0（顶部须在 +n 侧；O 为 ENU 原点）
        val topSide = p[0].dot(n)
        require(topSide > 0.0) {
            "点序绕向错误或点位退化（顶部点不在 +n 侧：(p0−O)·n=%.4fm）".format(topSide)
        }

        // (s,t) 坐标与 a/R
        val s = DoubleArray(6) { p[it].dot(u) }
        val t = DoubleArray(6) { p[it].dot(n) }
        val a = (s[5] + s[4] - s[1] - s[2]) / 4.0
        val r = (t[0] + t[1] + t[5] - t[2] - t[3] - t[4]) / 6.0

        // ⑤ a/R 阈值
        require(a > MIN_STRAIGHT_HALF_M) {
            "直道半长 a=%.3fm（≤ %.1fm），点位退化".format(a, MIN_STRAIGHT_HALF_M)
        }
        require(r > MIN_BEND_RADIUS_M) {
            "弯道半径 R=%.3fm（≤ %.1fm），点位退化".format(r, MIN_BEND_RADIUS_M)
        }

        // ⑥ 残差槽位 (ŝ,t̂)：p0(0,+R) p1(−a,+R) p2(−a,−R) p3(0,−R) p4(+a,−R) p5(+a,+R)
        //    全部点校验 |t−t̂| ≤ tol；仅 p1/p2/p4/p5 另校验 |s−ŝ| ≤ tol。
        val tol = residualTol(r)
        val sHat = doubleArrayOf(0.0, -a, -a, 0.0, a, a)
        val tHat = doubleArrayOf(r, r, -r, -r, -r, r)
        val checkS = booleanArrayOf(false, true, true, false, true, true)
        for (i in 0 until 6) {
            val dt = abs(t[i] - tHat[i])
            require(dt <= tol) {
                "点位残差超限：p$i 的 |t−t̂|=%.3fm > tol=%.3fm（槽位 t̂=%.3fm，实测 t=%.3fm）"
                    .format(dt, tol, tHat[i], t[i])
            }
            if (checkS[i]) {
                val ds = abs(s[i] - sHat[i])
                require(ds <= tol) {
                    "点位残差超限：p$i 的 |s−ŝ|=%.3fm > tol=%.3fm（槽位 ŝ=%.3fm，实测 s=%.3fm）"
                        .format(ds, tol, sHat[i], s[i])
                }
            }
        }

        // ⑦ 中点约束：p0/p3 仅做 t 残差 + 中点约束
        val midTol = MIDPOINT_S_REL_A * a
        require(abs(s[0]) <= midTol && abs(s[3]) <= midTol) {
            "顶部/底部应标记在直道中点附近（|s0|=%.3fm、|s3|=%.3fm，允许 ±%.3fm）"
                .format(abs(s[0]), abs(s[3]), midTol)
        }

        return TrackModel(origin = origin, u = u, n = n, a = a, r = r)
    }
}
