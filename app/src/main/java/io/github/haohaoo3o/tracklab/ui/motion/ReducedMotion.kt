package io.github.haohaoo3o.tracklab.ui.motion

/**
 * 装饰动效总开关（进程内，DESIGN.md §5.2.4）。Activity 写、
 * [io.github.haohaoo3o.tracklab.ui.overlay.FloatingOverlayController] 读——悬浮窗由
 * PlaybackForegroundService 构造（同进程），Activity 的实例字段到不了控制器，
 * 故必须走进程内共享状态。
 *
 * 两级、系统优先：系统「动画时长缩放 = 0」时**强制**关闭；应用开关只能在其之内再关，
 * **不能越过系统关闭而打开**。状态只存内存，默认开（= 允许动效）。
 */
object ReducedMotion {

    /** 用户开关（默认 false = 允许动效）。 */
    @Volatile
    var userDisabled: Boolean = false

    /** 系统「动画时长缩放 = 0」时为 false（API 26 = minSdk）。 */
    fun systemAllows(): Boolean = android.animation.ValueAnimator.areAnimatorsEnabled()

    /** 是否允许播放装饰动效。 */
    fun allows(): Boolean = !userDisabled && systemAllows()
}
