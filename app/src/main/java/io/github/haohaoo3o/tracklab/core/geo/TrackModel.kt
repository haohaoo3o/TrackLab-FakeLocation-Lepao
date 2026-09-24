package io.github.haohaoo3o.tracklab.core.geo

import kotlin.math.PI

/**
 * 六点拟合后的跑道几何模型：直道半长 a、弯道半径 R、局部基 (u, n) 与弧长参数化曲线 C(s)/N(s)
 *（docs/CONTRACTS.md）。以 LTP 原点 O 为 ENU 原点 ⇒ C(s) 直接给出 ENU 坐标。
 *
 * C(s) 四段式（s=0 起于 p5，CCW；L = 4a + 2πR）：
 * ① s∈[0,2a] 顶直道：O+(a−s)·u+R·n（右→左；0→p5、a→p0、2a→p1），N=+n；
 * ② s∈[2a,2a+πR] 左弯：α=(s−2a)/R，C_L+R·(cosα·n − sinα·u)（C_L=O−a·u），N=cosα·n − sinα·u；
 * ③ s∈[2a+πR,4a+πR] 底直道：O+(s−3a−πR)·u − R·n（左→右），N=−n；
 * ④ s∈[4a+πR,L] 右弯：β=(s−4a−πR)/R，C_R+R·(sinβ·u − cosβ·n)（C_R=O+a·u），N=sinβ·u − cosβ·n。
 *
 * 连续性：接缝处函数值与一阶导连续（|C′|=1 全程、切向连续），|C(0)−C(L)| ≤ 1e-6m。
 * 定义域：s ∈ [0, L]（多圈由调用方自行取模，见 [wrapArc]）；s=L 走 ④ 末端公式，
 * 与 s=0 的 ① 首端公式路径不同——闭合断言因此有实际意义。
 */
class TrackModel(
    val origin: LatLon,
    val u: Vec2,
    val n: Vec2,
    val a: Double,
    val r: Double,
) {

    /** 原点 O 的 LTP（[origin] 即 O；六点均值）。*/
    val ltp: LocalTangentPlane = LocalTangentPlane(origin)

    /** 周长 L = 4a + 2πR（禁止硬编码；标准试样 a=42.5、R=36.5 ⇒ L=399.336m，附录 A）。 */
    val lengthM: Double = 4.0 * a + 2.0 * PI * r

    /** 段界：顶直道末端 s=2a。 */
    val topStraightEndM: Double get() = 2.0 * a

    /** 段界：左弯末端 s=2a+πR。 */
    val leftBendEndM: Double get() = 2.0 * a + PI * r

    /** 段界：底直道末端 s=4a+πR。 */
    val bottomStraightEndM: Double get() = 4.0 * a + PI * r

    /**
     * 中心线 C(s)，ENU（米）。s 须 ∈ [0, lengthM]（含端点）；越界抛可读错误。
     * s = lengthM 处用 ④ 末端公式求值（非折回 0），保证闭合断言非平凡。
     */
    fun point(s: Double): Vec2 {
        checkDomain(s)
        return when {
            s <= topStraightEndM -> {
                // ① 顶直道：O+(a−s)·u+R·n
                u * (a - s) + n * r
            }
            s <= leftBendEndM -> {
                // ② 左弯：C_L+R·(cosα·n − sinα·u)
                val alpha = (s - 2.0 * a) / r
                u * (-a - r * kotlin.math.sin(alpha)) + n * (r * kotlin.math.cos(alpha))
            }
            s <= bottomStraightEndM -> {
                // ③ 底直道：O+(s−3a−πR)·u − R·n
                u * (s - 3.0 * a - PI * r) + n * (-r)
            }
            else -> {
                // ④ 右弯：C_R+R·(sinβ·u − cosβ·n)
                val beta = (s - 4.0 * a - PI * r) / r
                u * (a + r * kotlin.math.sin(beta)) + n * (-r * kotlin.math.cos(beta))
            }
        }
    }

    /** 外法向 N(s)（单位向量；与 C(s) 同段定义，见表）。*/
    fun normal(s: Double): Vec2 {
        checkDomain(s)
        return when {
            s <= topStraightEndM -> n
            s <= leftBendEndM -> {
                val alpha = (s - 2.0 * a) / r
                u * (-kotlin.math.sin(alpha)) + n * (kotlin.math.cos(alpha))
            }
            s <= bottomStraightEndM -> -n
            else -> {
                val beta = (s - 4.0 * a - PI * r) / r
                u * (kotlin.math.sin(beta)) + n * (-kotlin.math.cos(beta))
            }
        }
    }

    /** 弧长参数取模到 [0, L)（多圈推进由 motion 管线使用）。 */
    fun wrapArc(s: Double): Double {
        val m = s % lengthM
        return if (m < 0) m + lengthM else m
    }

    /** ENU 平面坐标 → WGS-84（高程取原点高程）。管线第 5 步。*/
    fun toWgs84(p: Vec2): LatLon = ltp.toLla2(p)

    /** WGS-84 → ENU 平面坐标。 */
    fun toEnu(p: LatLon): Vec2 = ltp.toEnu2(p)

    private fun checkDomain(s: Double) {
        require(s >= 0.0 && s <= lengthM) {
            "弧长参数 s=$s 超出 [0, L=$lengthM]（多圈请先用 wrapArc 取模）"
        }
    }
}
