package io.github.haohaoo3o.tracklab.core.motion

import java.util.Random
import kotlin.math.sqrt

/**
 * OU（Ornstein–Uhlenbeck）噪声离散化（递推式钉死）。
 * PaceProfile（ν）与 CadenceProfile（η）共用；随机源一律 [java.util.Random]（非 kotlin.random.Random）。
 *
 * 结点间隔 Δ=[MotionContracts.OU_NODE_INTERVAL_SEC]=1.0s=2·Δt，结点间**线性插值**；结点值递推
 * （g_k = [Random.nextGaussian]，ρ=[MotionContracts.OU_RHO]）：
 *
 * ```
 * x_0     = clamp(σ·g_0, ±x_max)
 * raw_{k+1} = ρ·x_k + σ·√(1−ρ²)·g_{k+1}
 * x_{k+1} = clamp(x_k + clamp(raw_{k+1} − x_k, ±δ_max), ±x_max)
 * ν(t)    = x_k + frac·(x_{k+1} − x_k)，k = ⌊t/Δ⌋，frac = (t−kΔ)/Δ
 * ```
 *
 * 消耗次序（钉死）：g_k 按结点序号 k=0,1,2,… 单调消耗；取值器内部维护结点序列 [x_k, x_{k+1}] 窗口，
 * 跨窗即按序消耗下一个 g——**同 seed 任意查询次序下逐点可复现**。
 *
 * 连续性推论：相邻结点 |x_{k+1}−x_k| ≤ δ_max ⇒ 线性插值下任意 500ms 步 |Δν| ≤ 0.5·δ_max
 * （含跨结点窗；Pace ⇒ 0.01、Cadence ⇒ 0.5spm）。
 */
internal class OuNoise(
    private val random: Random,
    private val sigma: Double,
    private val deltaMax: Double,
    private val xMax: Double,
) {

    /** 已消耗的结点值 x_0..x_m（按下标单调延长，保证 g 消耗次序钉死）。 */
    private val nodes: MutableList<Double> = ArrayList()

    init {
        require(sigma >= 0.0 && deltaMax >= 0.0 && xMax > 0.0) {
            "OU 噪声参数退化（sigma=$sigma, deltaMax=$deltaMax, xMax=$xMax）"
        }
        // x_0 = clamp(σ·g_0, ±x_max)
        nodes.add(clamp(sigma * random.nextGaussian()))
    }

    /** 噪声在时刻 [tSec]（秒）的取值 ν(t)（结点间线性插值）。*/
    fun value(tSec: Double): Double {
        require(tSec >= 0.0) { "OU 噪声时间必须非负（t=$tSec）" }
        val k = Math.floor(tSec / MotionContracts.OU_NODE_INTERVAL_SEC).toLong()
        val frac = (tSec - k * MotionContracts.OU_NODE_INTERVAL_SEC) / MotionContracts.OU_NODE_INTERVAL_SEC
        ensure(k + 1)
        val xk = nodes[k.toInt()]
        val xk1 = nodes[k.toInt() + 1]
        return xk + frac * (xk1 - xk)
    }

    /** 结点值 x_k（测试/复算可直读；越界按需延长并按序消耗 g）。 */
    fun nodeAt(index: Int): Double {
        require(index >= 0) { "结点序号必须非负（index=$index）" }
        ensure(index.toLong())
        return nodes[index]
    }

    /** 把结点序列延长到至少 index+1 个（每次恰好消耗一个 nextGaussian，严格按结点序）。 */
    private fun ensure(index: Long) {
        while (nodes.size <= index) {
            val xk = nodes[nodes.size - 1]
            val g = random.nextGaussian()
            val raw = MotionContracts.OU_RHO * xk +
                sigma * sqrt(1.0 - MotionContracts.OU_RHO * MotionContracts.OU_RHO) * g
            // x_{k+1} = clamp(x_k + clamp(raw_{k+1} − x_k, ±δ_max), ±x_max)
            val step = (raw - xk).coerceIn(-deltaMax, deltaMax)
            nodes.add(clamp(xk + step))
        }
    }

    private fun clamp(x: Double): Double = x.coerceIn(-xMax, xMax)
}
