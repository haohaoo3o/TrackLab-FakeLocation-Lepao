package io.github.haohaoo3o.tracklab.core.motion

import kotlin.math.abs
import kotlin.math.min

/**
 * 走廊边界检查与防御性硬夹（docs/CONTRACTS.md）。
 *
 * 走廊半宽 W = min(1.75m, 0.3R)（[MotionContracts.BOUNDARY_W_MAX_M]/[MotionContracts.BOUNDARY_W_REL_R]）。
 * 因 |Δ̂| < 0.55 < W，**生成路径恒不触发钳制**——『零越界』是对生成序列的防御性不变量断言；
 * 钳制函数本身由 BoundaryGuardTest 构造 |t|>W 的越界残差点直测（§10）。
 *
 * 本对象为『边界检查』的纯函数实现（无状态、无 Android 依赖）：
 * [isWithinBoundary] 为边界检查谓词，[clampLateral] 为硬夹（把横向残差 t 钳进 [−W, +W]）。
 */
object BoundaryGuard {

    /** 走廊半宽 W = min([MotionContracts.BOUNDARY_W_MAX_M], [MotionContracts.BOUNDARY_W_REL_R]·R)。 */
    fun halfWidth(radiusR: Double): Double {
        require(radiusR > 0.0) { "弯道半径 R 必须为正（R=$radiusR）" }
        return min(MotionContracts.BOUNDARY_W_MAX_M, MotionContracts.BOUNDARY_W_REL_R * radiusR)
    }

    /** 边界检查：横向残差 t 是否落在走廊内（|t| ≤ W）。 */
    fun isWithinBoundary(lateralT: Double, radiusR: Double): Boolean =
        abs(lateralT) <= halfWidth(radiusR)

    /** 防御性硬夹：把横向残差 t 钳到 [−W, +W]（越界点钳到边界，直测口径）。*/
    fun clampLateral(lateralT: Double, radiusR: Double): Double {
        val w = halfWidth(radiusR)
        return lateralT.coerceIn(-w, w)
    }
}
