package io.github.haohaoo3o.tracklab.core.motion

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min

/**
 * 弯道过渡权重 w(s) 与过渡半宽 hw（PaceProfile 与 LapPerturber 共用）。
 *
 * - 弯道段：左弯 s∈[2a, 2a+πR]、右弯 s∈[4a+πR, L]；**弯道上 d_arc=0 ⇒ w=1**。
 * - d_arc(s) = 圈内弧长到最近弯道段的距离（闭合圈最短弧距；弯道上为 0）。
 * - w(s) = 0.5·(1+cos(π·min(1, d_arc(s)/hw)))，hw = min(8.0m, 0.4a, 0.2πR)
 *   （[MotionContracts.BEND_WINDOW_HW_BASE_M]/[MotionContracts.BEND_WINDOW_HW_REL_A]/
 *   [MotionContracts.BEND_WINDOW_HW_REL_ARC]）。
 * - PaceProfile 的 f_bend = 1+A_b·w(s) 与 LapPerturber 的 d_k·w(s) 同源（过渡段差异口径一致）。
 */
object BendWeight {

    /** 过渡半宽 hw = min(8.0m, 0.4a, 0.2πR)（米）。 */
    fun halfWidthM(a: Double, r: Double): Double {
        require(a > 0.0 && r > 0.0) { "跑道几何退化（a=$a, R=$r）" }
        return min(
            MotionContracts.BEND_WINDOW_HW_BASE_M,
            min(
                MotionContracts.BEND_WINDOW_HW_REL_A * a,
                MotionContracts.BEND_WINDOW_HW_REL_ARC * PI * r
            )
        )
    }

    /** 弯道过渡权重 w(s)（[lapArcS] 为圈内弧长，内部取模到 [0, L)）。 */
    fun weight(lapArcS: Double, lengthM: Double, a: Double, r: Double): Double {
        val hw = halfWidthM(a, r)
        val dArc = arcDistanceToBend(lapArcS, lengthM, a, r)
        val x = min(1.0, dArc / hw)
        return 0.5 * (1.0 + cos(PI * x))
    }

    /** 圈内弧长到最近弯道段的距离（弯道上为 0；闭合圈最短弧距）。 */
    fun arcDistanceToBend(lapArcS: Double, lengthM: Double, a: Double, r: Double): Double {
        require(lengthM > 0.0) { "周长必须为正（L=$lengthM）" }
        val s = wrap(lapArcS, lengthM)
        val leftBendLo = 2.0 * a
        val leftBendHi = 2.0 * a + PI * r
        val rightBendLo = 4.0 * a + PI * r
        val rightBendHi = lengthM
        return min(
            distToInterval(s, leftBendLo, leftBendHi, lengthM),
            distToInterval(s, rightBendLo, rightBendHi, lengthM)
        )
    }

    /** 闭合圈上 s 到区间 [lo, hi]（⊂ [0, L]）的最短弧距（区间内为 0）。 */
    private fun distToInterval(s: Double, lo: Double, hi: Double, lengthM: Double): Double {
        if (s >= lo && s <= hi) return 0.0
        return min(circleDist(s, lo, lengthM), circleDist(s, hi, lengthM))
    }

    /** 闭合圈上两点最短弧距 min(|x−y|, L−|x−y|)。 */
    private fun circleDist(x: Double, y: Double, lengthM: Double): Double {
        val d = Math.abs(x - y)
        return min(d, lengthM - d)
    }

    private fun wrap(s: Double, lengthM: Double): Double {
        val m = s % lengthM
        return if (m < 0.0) m + lengthM else m
    }
}
