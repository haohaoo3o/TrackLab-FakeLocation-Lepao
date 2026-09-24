package io.github.haohaoo3o.tracklab.core.geo

import io.github.haohaoo3o.tracklab.core.motion.MotionContracts
import kotlin.math.abs

/**
 * 按米等弧长重采样（docs/CONTRACTS.md）。**仅用于拟合预览/边界走廊显示，不进生成管线。**
 *
 * 构造：Δ=1.0m（[MotionContracts.RESAMPLE_DELTA_M]），N=round(L/Δ)，采样点 s_i = i·L/N
 * （i=0..N，N+1 点 N 段，首末重合 ⇒ 闭合）。
 *
 * 间距判据（全域成立）：|L/N − Δ| ≤ 0.01·Δ + 0.5·Δ/N。
 * 旧判据『间距 ∈[0.99,1.01]m』对合法域内小几何（a→2m/R→5m）为假，禁止作为全域断言；
 * 仅标准试样可附加宽松断言（MetricResamplerTest）。
 */
object MetricResampler {

    /** 单个重采样点：[arcS] 圈内弧长参数（米）、[position] ENU 坐标（米）。 */
    data class ResampledPoint(val arcS: Double, val position: Vec2)

    /**
     * 对 [TrackModel] 中心线等弧长重采样，返回 N+1 点（首末点分别由 C(0) 与 C(L) 两条公式路径求值）。
     *
     * @throws IllegalArgumentException deltaM ≤ 0（退化输入可读错误）
     */
    fun resample(model: TrackModel, deltaM: Double = MotionContracts.RESAMPLE_DELTA_M): List<ResampledPoint> {
        require(deltaM > 0.0) { "重采样步长必须为正（deltaM=$deltaM）" }
        val n = Math.round(model.lengthM / deltaM).toInt().coerceAtLeast(1)
        return (0..n).map { i ->
            val s = i * model.lengthM / n
            ResampledPoint(s, model.point(s))
        }
    }

    /** 间距判据：|L/N − Δ| ≤ 0.01·Δ + 0.5·Δ/N。N=[sampleCount]−1 段、L=[lengthM]。*/
    fun spacingSatisfiesContract(lengthM: Double, sampleCount: Int, deltaM: Double = MotionContracts.RESAMPLE_DELTA_M): Boolean {
        val n = sampleCount - 1
        if (n <= 0) return false
        val spacing = lengthM / n
        return abs(spacing - deltaM) <=
            MotionContracts.RESAMPLE_SPACING_REL_TOL * deltaM + 0.5 * deltaM / n
    }
}
