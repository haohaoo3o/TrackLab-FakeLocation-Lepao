package io.github.haohaoo3o.tracklab.ui.matrix

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.annotation.ColorInt
import io.github.haohaoo3o.tracklab.R

/**
 * LED 状态点（16×16dp，DESIGN.md §3.2 / §6.5）。
 *
 * 单一职责：只负责「点的颜色 + 光环 + 呼吸」三件事，不读任何业务状态。
 * 形态用规范给定的两件 drawable（`v2_led` 8dp 圆点 / `v2_led_halo` 16dp 环 + 8dp 点），
 * 颜色用 `backgroundTintList` 运行时染色（§3.2 口径）。
 *
 * 动画生命周期自持：`setState(breathing = false)` / `stop()` / `onDetachedFromWindow()`
 * 一律 `cancel()` 并置空——**绝不让动画持有已 detach 的 View**。
 * 装饰性：宿主在布局里必须 `clickable=false` / `focusable=false` /
 * `importantForAccessibility="no"`（本类不改变这三项，避免与布局声明打架）。
 */
class LedDotView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var breathAnimator: ValueAnimator? = null
    private var lastColor: Int = 0
    private var lastHalo: Boolean = false

    /**
     * 设置状态外观。[halo] = PLAYING 的光环（唯一带光环的一态）；
     * [breathing] 为 true 时播放 1200ms 呼吸（alpha 0.55↔1.0，INFINITE/REVERSE），
     * 否则取消并回到 alpha 1.0。
     * 同色同态不重设背景/染色（回放帧每 500ms 调一次，静止时不产生多余重绘）。
     */
    fun setState(@ColorInt color: Int, halo: Boolean, breathing: Boolean) {
        if (color != lastColor || halo != lastHalo) {
            lastColor = color
            lastHalo = halo
            setBackgroundResource(if (halo) R.drawable.v2_led_halo else R.drawable.v2_led)
            backgroundTintList = ColorStateList.valueOf(color)
        }
        if (breathing) startBreathing() else stop()
    }

    /** 取消呼吸并把 alpha 复位（idle/停止态与生命周期退出点调用）。 */
    fun stop() {
        breathAnimator?.cancel()
        breathAnimator = null
        alpha = 1f
    }

    private fun startBreathing() {
        if (breathAnimator != null) return
        alpha = 1f
        breathAnimator = ValueAnimator.ofFloat(BREATH_MIN_ALPHA, 1f).apply {
            duration = BREATH_MS
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = LinearInterpolator()
            addUpdateListener { alpha = it.animatedValue as Float }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val BREATH_MS = 1200L
        const val BREATH_MIN_ALPHA = 0.55f
    }
}
