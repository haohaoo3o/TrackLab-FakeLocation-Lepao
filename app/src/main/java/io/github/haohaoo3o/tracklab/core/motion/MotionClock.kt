package io.github.haohaoo3o.tracklab.core.motion

/**
 * 等时推进时钟（docs/CONTRACTS.md §3）。
 *
 * - 步长 Δt=[MotionContracts.DELTA_T_SEC]=0.5s **全文钉死**：等于 TrackSample 间隔、
 *   MotionClock 等时步长；全部连续性阈值按 0.5s 标定，**禁止取 Δt=1s**（§3 推导）。
 * - 管线第 1 步：等时推进 ds = v·Δt（[stepDistanceM]）。
 * - 样本时刻 t_k = k·Δt、elapsedMs = k·500（[elapsedSec]/[elapsedMs]），纯函数、无内部状态。
 */
class MotionClock {

    /** 等时步长（秒）。钉死 = [MotionContracts.DELTA_T_SEC]，不开放配置（Δt 恒 0.5s）。*/
    val deltaTSec: Double = MotionContracts.DELTA_T_SEC

    /** 第 [stepIndex] 个样本的时刻 t_k = k·Δt（秒）。 */
    fun elapsedSec(stepIndex: Long): Double {
        require(stepIndex >= 0L) { "步序号必须非负（stepIndex=$stepIndex）" }
        return stepIndex * deltaTSec
    }

    /** 第 [stepIndex] 个样本的累计时间（毫秒；Δt=0.5s ⇒ 恒为 500 的整数倍）。 */
    fun elapsedMs(stepIndex: Long): Long {
        require(stepIndex >= 0L) { "步序号必须非负（stepIndex=$stepIndex）" }
        return (stepIndex * deltaTSec * 1000.0).toLong()
    }

    /** 管线第 1 步位移 ds = v·Δt（米）。速度必须非负（禁负速度）。*/
    fun stepDistanceM(speedMps: Double): Double {
        require(speedMps >= 0.0) { "速度必须非负（speedMps=$speedMps）" }
        return speedMps * deltaTSec
    }
}
