package io.github.haohaoo3o.tracklab.ui.overlay

/**
 * 悬浮窗 HUD 几何（docs/CONTRACTS.md §8）——**纯函数，同输入同输出**，零 Android 依赖。
 *
 * 不变量（OverlayGeometryTest 断言）：
 * ① 计算所得控件矩形全部落在给定视口内（含 margin：rect ⊆ [margin, 尺寸−margin]）；
 * ② 互不重叠（含边界不相交）；
 * ③ 命中区 ≥ [MIN_HIT_TARGET_DP]（48dp，暂停/停止两键）；
 * ④ 纯函数同输入同输出。
 *
 * 布局形态：`[指标区 metrics][暂停 pause][停止 stop]` 横排，贴视口内缘（margin 内缩）。
 * 视口过小无法同时满足 ①③ 时抛 IllegalArgumentException（中文可读；宁可显式失败也不静默违反不变量）。
 * [clampWindowPosition]：拖动悬浮窗时把窗口钳回视口（含 margin）——可拖动悬浮窗的落点约束。
 *
 * 单位：dp（整数；Android 侧换算 px 由 FloatingOverlayController 承担）。
 */
object OverlayGeometry {

    /** 最小命中区（dp，§8 ③）。*/
    const val MIN_HIT_TARGET_DP = 48

    /** 默认视口内缩 margin（dp）。 */
    const val DEFAULT_MARGIN_DP = 8

    /** 控件间距（dp）。 */
    const val DEFAULT_GAP_DP = 8

    /** 指标区（圈数/距离/配速/步频文本）最小宽度（dp）。 */
    const val METRICS_MIN_WIDTH_DP = 96

    /** 整数矩形（dp，左闭右开：width = right − left）。 */
    data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int) {

        val width: Int get() = right - left
        val height: Int get() = bottom - top

        /** 与 [other] 是否重叠（开区间语义：仅贴边不算重叠）。 */
        fun overlaps(other: Rect): Boolean =
            left < other.right && other.left < right && top < other.bottom && other.top < bottom

        /** 是否完全落在 [outer] 内（含边界）。 */
        fun inside(outer: Rect): Boolean =
            left >= outer.left && top >= outer.top && right <= outer.right && bottom <= outer.bottom
    }

    /** 悬浮窗三控件布局（metrics / pause / stop）。 */
    data class ControlLayout(val metrics: Rect, val pause: Rect, val stop: Rect) {

        /** 全部控件矩形（不变量①② 断言遍历面）。 */
        val all: List<Rect> get() = listOf(metrics, pause, stop)
    }

    /** 窗口左上角（dp）。 */
    data class Position(val x: Int, val y: Int)

    /**
     * 计算 HUD 三控件布局（纯函数，不变量①②③④）。
     *
     * @param viewportWidthDp 视口宽（dp）
     * @param viewportHeightDp 视口高（dp）
     * @param marginDp 视口内缩 margin（dp，默认 [DEFAULT_MARGIN_DP]）
     * @param gapDp 控件间距（dp，默认 [DEFAULT_GAP_DP]）
     * @throws IllegalArgumentException 视口过小（无法同时满足 ①③）
     */
    fun computeLayout(
        viewportWidthDp: Int,
        viewportHeightDp: Int,
        marginDp: Int = DEFAULT_MARGIN_DP,
        gapDp: Int = DEFAULT_GAP_DP,
    ): ControlLayout {
        require(marginDp >= 0 && gapDp >= 0) { "margin/间距不得为负：margin=$marginDp gap=$gapDp" }
        val contentLeft = marginDp
        val contentTop = marginDp
        val contentRight = viewportWidthDp - marginDp
        val contentBottom = viewportHeightDp - marginDp
        val contentWidth = contentRight - contentLeft
        val contentHeight = contentBottom - contentTop
        val minWidth = METRICS_MIN_WIDTH_DP + gapDp + MIN_HIT_TARGET_DP + gapDp + MIN_HIT_TARGET_DP
        require(contentHeight >= MIN_HIT_TARGET_DP && contentWidth >= minWidth) {
            "悬浮窗视口过小：${viewportWidthDp}x${viewportHeightDp}dp（margin=$marginDp gap=$gapDp），" +
                "需至少 ${minWidth + 2 * marginDp}x${MIN_HIT_TARGET_DP + 2 * marginDp}dp"
        }
        val stop = Rect(contentRight - MIN_HIT_TARGET_DP, contentTop, contentRight, contentBottom)
        val pause = Rect(stop.left - gapDp - MIN_HIT_TARGET_DP, contentTop, stop.left - gapDp, contentBottom)
        val metrics = Rect(contentLeft, contentTop, pause.left - gapDp, contentBottom)
        return ControlLayout(metrics = metrics, pause = pause, stop = stop)
    }

    /**
     * 拖动悬浮窗的窗口落点钳制（纯函数）：把尺寸 `windowWidthDp × windowHeightDp` 的窗口
     * 左上角从 ([x], [y]) 钳回视口内（含 margin）——不变量① 在任意拖动序列后成立。
     * 窗口比『视口 − 2·margin』大时贴 margin 原点（无法完全内嵌属已知退化，不静默越界）。
     */
    fun clampWindowPosition(
        x: Int,
        y: Int,
        windowWidthDp: Int,
        windowHeightDp: Int,
        viewportWidthDp: Int,
        viewportHeightDp: Int,
        marginDp: Int = DEFAULT_MARGIN_DP,
    ): Position {
        val maxX = maxOf(marginDp, viewportWidthDp - marginDp - windowWidthDp)
        val maxY = maxOf(marginDp, viewportHeightDp - marginDp - windowHeightDp)
        return Position(x = x.coerceIn(marginDp, maxX), y = y.coerceIn(marginDp, maxY))
    }
}
