package io.github.haohaoo3o.tracklab.ui.overlay

/**
 * 悬浮窗 HUD 几何（工程契约 ）——**纯函数，同输入同输出**，零 Android 依赖。
 *
 * 不变量（OverlayGeometryTest 断言）：
 * ① 计算所得控件矩形全部落在给定视口内（含 margin：rect ⊆ [margin, 尺寸−margin]）；
 * ② 互不重叠（含边界不相交；折叠态 gridMetrics 取零高投影，显式排除在②检查外）；
 * ③ 命中区 ≥ [MIN_HIT_TARGET_DP]（48dp，暂停/停止/折叠三键）；
 * ④ 纯函数同输入同输出。
 *
 * 布局形态（Matrix 扩展，默认展开保证旧调用可编译、旧语义 = 展开）：
 * 折叠态单行 `[指标区 metrics][暂停 pause][停止 stop][折叠 fold]` 横排，总高 48dp；
 * 展开态 = 单行 + 下方 gridMetrics 行（高 48dp + gap），gridMetrics 左右贴 margin、与上行 gap 相隔。
 * 视口过小无法同时满足 ①③ 时抛 IllegalArgumentException（中文可读；宁可显式失败也不静默违反不变量）。
 * [clampWindowPosition]：拖动悬浮窗时把窗口钳回视口（含 margin）——可拖动悬浮窗的落点约束。
 * [initialWindowPosition]：初始落点 = 水平居中（左右边距相等）+ 纵向钳制——**同一套钳制语义**
 * （内部即调 [clampWindowPosition]，控制器不另写算法）；真机复审问题 1 的修复点。
 *
 * **v2 扩展（设计资产 /，同为纯函数，语义只增不改）**：
 * 上面 5 矩形模型（[computeLayout]）**一个字都没动**；新增的四件是「主行 / 网格行的横向宽度算式」，
 * 供控制器据此定窗宽（`params.width`）与自检：
 * - 抓手（`matrix_overlay_grip`，2dp）与**合规标记格**（`matrix_overlay_test_marker`，wrap_content
 *   ≈30dp）都是**主行矩形内部的子节点**——不构成第 6 个控件（DESIGN  末段），故不进 `all/solid`；
 * - [computeWindowWidthDp]：`min(380, 视口 − 2·margin)`dp（DESIGN  窗宽公式）；
 * - [computeContentWidthDp]：窗宽 − 2×8dp 玻璃卡内边距；
 * - [computeMetricsWidthDp]：内容宽 − [MAIN_ROW_FIXED_WIDTH_DP]（= 216dp）= 指标格实得宽；
 * - [computeGridCellWidthDp]：展开态 4 格每格宽（LED 16dp + 4 格 + 4×8dp 间隔）。
 * 已知退化（如实记录，不静默）：视口 < 344dp 时指标格低于模型下限 [METRICS_MIN_WIDTH_DP]（96dp），
 * 视口 < 296dp 时内容宽低于 [computeLayout] 的可行下限（264dp）——DESIGN  表内 320dp 一档同口径。
 *
 * 单位：dp（整数；Android 侧换算 px 由 FloatingOverlayController 承担）。
 */
object OverlayGeometry {

    /** 最小命中区（dp， ③）。 */
    const val MIN_HIT_TARGET_DP = 48

    /** 默认视口内缩 margin（dp）。 */
    const val DEFAULT_MARGIN_DP = 8

    /** 控件间距（dp）。 */
    const val DEFAULT_GAP_DP = 8

    /** 指标区（圈数/距离/配速/步频文本）最小宽度（dp）。 */
    const val METRICS_MIN_WIDTH_DP = 96

    /** 主行传送键枚数（暂停 / 停止 / 折叠），每枚 [MIN_HIT_TARGET_DP] 见方。 */
    const val MAIN_KEY_COUNT = 3

    /** 拖拽抓手宽（dp，装饰性：2×20dp 圆头条）。 */
    const val GRIP_WIDTH_DP = 2

    /** 合规标记格均值宽（dp；「测试」≈28dp / en「TEST」≈32dp，行宽实算取 30dp）。 */
    const val MARKER_WIDTH_DP = 30

    /** 展开态状态 LED 宽（dp，16×16dp 宿主、8dp 圆点在正中）。 */
    const val LED_WIDTH_DP = 16

    /** 展开态指标网格列数（圈 / 距离 / 配速 / 步频）。 */
    const val GRID_COLUMNS = 4

    /** 展开态网格行高（dp）＝ 命中区高：4 格与三键同高，视觉同一节奏。 */
    const val GRID_ROW_HEIGHT_DP = MIN_HIT_TARGET_DP

    /** 网格行与主行之间的 gap（dp）——折叠动效插值的 56dp = 本值 + [GRID_ROW_HEIGHT_DP]。 */
    const val GRID_ROW_GAP_DP = DEFAULT_GAP_DP

    /** 玻璃卡内边距（dp，`overlay_root` 的 padding）：内容宽 = 窗宽 − 2×本值。 */
    const val WINDOW_PADDING_DP = 8

    /** 悬浮窗最大窗宽（dp，DESIGN  窗宽公式的上限）。 */
    const val MAX_WINDOW_WIDTH_DP = 380

    /**
     * 主行固定宽（dp）= 抓手 2 + 间隙 8 + 标记格 30 + 间隙 8 + 三键 3×48 + 键间 2×8 = **216**。
     * 用 `get()` 由各常量推导（而非写死 216），使「改任一常量 → 测试算式同步失配」。
     * 依据：DESIGN  行宽实算 `2 + 8 + 指标格 + 8 + 30 + 8 + 48 + 8 + 48 + 8 + 48 = 216 + 指标格`。
     */
    val MAIN_ROW_FIXED_WIDTH_DP: Int
        get() = GRIP_WIDTH_DP + DEFAULT_GAP_DP +       // 抓手 + 抓手→指标间隙
            DEFAULT_GAP_DP + MARKER_WIDTH_DP +         // 标记格 marginStart + 标记格
            DEFAULT_GAP_DP +                           // 标记格 marginEnd
            MAIN_KEY_COUNT * MIN_HIT_TARGET_DP +       // 三键
            (MAIN_KEY_COUNT - 1) * DEFAULT_GAP_DP      // 键间 2×8

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

    /** 悬浮窗四控件布局（metrics / pause / stop / fold + 展开态网格行 gridMetrics）。 */
    data class ControlLayout(
        val metrics: Rect,
        val pause: Rect,
        val stop: Rect,
        val fold: Rect,
        val gridMetrics: Rect,
        val expanded: Boolean,
    ) {

        /** 全部控件矩形（不变量①② 断言遍历面；折叠态 gridMetrics 为零高投影，调用方排除在②外）。 */
        val all: List<Rect> get() = listOf(metrics, pause, stop, fold, gridMetrics)

        /** 非零高矩形（不变量②重叠断言面：折叠态 gridMetrics 零高，天然排除）。 */
        val solid: List<Rect> get() = all.filter { it.height > 0 && it.width > 0 }
    }

    /** 窗口左上角（dp）。 */
    data class Position(val x: Int, val y: Int)

    /**
     * 计算 HUD 四控件布局（纯函数，不变量①②③④）。
     *
     * @param viewportWidthDp 视口宽（dp）
     * @param viewportHeightDp 视口高（dp）
     * @param marginDp 视口内缩 margin（dp，默认 [DEFAULT_MARGIN_DP]）
     * @param gapDp 控件间距（dp，默认 [DEFAULT_GAP_DP]）
     * @param expanded 展开态（默认 true：保持旧调用语义，SmokeTest 不因 GONE 失败）
     * @throws IllegalArgumentException 视口过小（无法同时满足 ①③）
     */
    fun computeLayout(
        viewportWidthDp: Int,
        viewportHeightDp: Int,
        marginDp: Int = DEFAULT_MARGIN_DP,
        gapDp: Int = DEFAULT_GAP_DP,
        expanded: Boolean = true,
    ): ControlLayout {
        require(marginDp >= 0 && gapDp >= 0) { "margin/间距不得为负：margin=$marginDp gap=$gapDp" }
        val contentLeft = marginDp
        val contentTop = marginDp
        val contentRight = viewportWidthDp - marginDp
        val contentWidth = contentRight - contentLeft
        val minWidth = METRICS_MIN_WIDTH_DP + gapDp + MIN_HIT_TARGET_DP + gapDp +
            MIN_HIT_TARGET_DP + gapDp + MIN_HIT_TARGET_DP
        val singleHeight = MIN_HIT_TARGET_DP
        val expandedHeight = MIN_HIT_TARGET_DP + gapDp + MIN_HIT_TARGET_DP
        val needHeight = if (expanded) expandedHeight else singleHeight
        require(contentWidth >= minWidth && viewportHeightDp - 2 * marginDp >= needHeight) {
            "悬浮窗视口过小：${viewportWidthDp}x${viewportHeightDp}dp（margin=$marginDp gap=$gapDp），" +
                "需至少 ${minWidth + 2 * marginDp}x${needHeight + 2 * marginDp}dp"
        }
        val contentBottom = contentTop + singleHeight
        val fold = Rect(contentRight - MIN_HIT_TARGET_DP, contentTop, contentRight, contentBottom)
        val stop = Rect(fold.left - gapDp - MIN_HIT_TARGET_DP, contentTop, fold.left - gapDp, contentBottom)
        val pause = Rect(stop.left - gapDp - MIN_HIT_TARGET_DP, contentTop, stop.left - gapDp, contentBottom)
        val metrics = Rect(contentLeft, contentTop, pause.left - gapDp, contentBottom)
        val gridMetrics = if (expanded) {
            Rect(contentLeft, contentBottom + gapDp, contentRight, contentBottom + gapDp + MIN_HIT_TARGET_DP)
        } else {
            // 折叠态零高投影（top == bottom），显式排除在②重叠检查外。
            Rect(contentLeft, contentBottom, contentRight, contentBottom)
        }
        return ControlLayout(
            metrics = metrics,
            pause = pause,
            stop = stop,
            fold = fold,
            gridMetrics = gridMetrics,
            expanded = expanded,
        )
    }

    /**
     * 悬浮窗**初始落点**（纯函数，同输入同输出）：水平方向**恒取居中**
     * （`x = (视口宽 − 窗宽) / 2`），再经 [clampWindowPosition] 的同一套钳制语义收进
     * `[margin, 视口宽 − margin − 窗宽]`；纵向取 [desiredYDp] 后同样钳制。
     *
     * 为什么不是「固定 x 再钳位」（2026-10-10 真机复审问题 1）：原实现 `x = 16dp` 在
     * 1080×2400 @440dpi（视口 393dp、窗宽 min(380, 393−16) = 377dp）上让
     * `16 + 377 + 16 = 409 > 393`——右边 16dp 放不下、窗口贴死屏边（真机实测左边距 43px /
     * **右边距 0**，右圆角被切平）。只要窗宽 = 视口 − 2·margin（本机即如此），固定 x 必然有
     * 一侧掉到 0；居中后左右边距 = (393 − 377)/2 = **8dp = margin**，两边相等。
     *
     * 退化口径（如实，不静默越界；与 [clampWindowPosition] 同一条代码路径）：
     * ① 窗口比『视口 − 2·margin』宽时 x 贴 [marginDp]（贴 margin 原点=已知退化）；
     * ② 窗宽与视口宽之差为奇数 dp 时左右边距相差 1dp（整数 dp 不可再分）。
     *
     * @param desiredYDp 期望纵向落点（dp，含 margin 钳制）——横向不设"期望值"：横向恒居中
     */
    fun initialWindowPosition(
        windowWidthDp: Int,
        windowHeightDp: Int,
        viewportWidthDp: Int,
        viewportHeightDp: Int,
        desiredYDp: Int,
        marginDp: Int = DEFAULT_MARGIN_DP,
    ): Position =
        // 居中后的 x 直接交给拖动用的同一函数：同一套钳制语义（窗口过大时同样贴 margin 原点），
        // 控制器里不再另写一套算法。整数除法向下取整；差为负（窗口比视口宽）时结果必 < margin → 钳回 margin。
        clampWindowPosition(
            x = (viewportWidthDp - windowWidthDp) / 2,
            y = desiredYDp,
            windowWidthDp = windowWidthDp,
            windowHeightDp = windowHeightDp,
            viewportWidthDp = viewportWidthDp,
            viewportHeightDp = viewportHeightDp,
            marginDp = marginDp,
        )

    /**
     * 拖动悬浮窗的窗口落点钳制（纯函数）：把尺寸 `windowWidthDp × windowHeightDp` 的窗口
     * 左上角从 ([x], [y]) 钳回视口内（含 margin）——不变量① 在任意拖动序列后成立。
     * 窗口比『视口 − 2·margin』大时贴 margin 原点（无法完全内嵌属已知退化，不静默越界）。
     * 初始落点见 [initialWindowPosition]（同一语义的超集：先居中、再走这里钳制）。
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

    // ---------------------------------------------------------------- v2 宽度算式（纯函数，DESIGN /）

    /**
     * 窗宽（dp）：`min(maxWidthDp, 视口宽 − 2·marginDp)`（DESIGN  窗宽公式）。
     *
     * 控制器把它换算成 px 作为 `WindowManager.LayoutParams.width`（根 `overlay_root` 用 match_parent）。
     * **px 换算取向下取整**（`(dp × density).toInt()`，见 FloatingOverlayController.showOnMainThread）：
     * 377dp @2.75 = 1036.75 → 1036px（不是 1037px）——1080px 屏上 1037px 会让左右各只剩 21.5px
     * （一侧 21px = 7.6dp < margin 8dp），1036px 时左右各 22px = 8dp，与 [initialWindowPosition]
     * 的居中落点合成「左右边距相等且都 ≥ 8dp」（2026-10-10 真机复审问题 1 的验收口径）。
     * 退化口径（如实）：视口比 2·margin 还窄时返回 0（不抛异常——本函数在 show() 路径上，
     * 抛异常等于悬浮窗崩；真正的"太小"由 [computeLayout] 显式拒绝）。
     *
     * @throws IllegalArgumentException marginDp < 0 或 maxWidthDp ≤ 0（中文错，同本模块口径）
     */
    fun computeWindowWidthDp(
        viewportWidthDp: Int,
        marginDp: Int = DEFAULT_MARGIN_DP,
        maxWidthDp: Int = MAX_WINDOW_WIDTH_DP,
    ): Int {
        require(marginDp >= 0 && maxWidthDp > 0) {
            "悬浮窗窗宽参数非法：margin=$marginDp maxWidth=$maxWidthDp"
        }
        return (viewportWidthDp - 2 * marginDp).coerceIn(0, maxWidthDp)
    }

    /**
     * 内容宽（dp）= 窗宽 − 2×[WINDOW_PADDING_DP]（DESIGN ：玻璃卡左右各 8dp 内边距）。
     *
     * @throws IllegalArgumentException paddingDp < 0
     */
    fun computeContentWidthDp(windowWidthDp: Int, paddingDp: Int = WINDOW_PADDING_DP): Int {
        require(paddingDp >= 0) { "悬浮窗内容宽参数非法：padding=$paddingDp" }
        return (windowWidthDp - 2 * paddingDp).coerceAtLeast(0)
    }

    /**
     * 指标格（`txt_overlay_metrics`）实得宽（dp）= 内容宽 − [MAIN_ROW_FIXED_WIDTH_DP]（216dp）。
     *
     * 允许为 0～[METRICS_MIN_WIDTH_DP]：指标串 `ellipsize=end` 可截断，合规标记格恒在
     * （DESIGN  载体规则）——故这里不抛异常，只如实返回可用宽。
     */
    fun computeMetricsWidthDp(contentWidthDp: Int): Int =
        (contentWidthDp - MAIN_ROW_FIXED_WIDTH_DP).coerceAtLeast(0)

    /**
     * 展开态网格单格宽（dp）：`(内容宽 − LED 宽 − 列数×gap) / 列数`（整数除法，向下取整）。
     * 实排 = LED 16dp + 4 格（weight=1）+ 4×8dp 间隔（LED→第 1 格 1 个 + 格间 3 个）。
     *
     * @throws IllegalArgumentException columns ≤ 0 / gapDp < 0 / ledWidthDp < 0
     */
    fun computeGridCellWidthDp(
        contentWidthDp: Int,
        columns: Int = GRID_COLUMNS,
        gapDp: Int = DEFAULT_GAP_DP,
        ledWidthDp: Int = LED_WIDTH_DP,
    ): Int {
        require(columns > 0 && gapDp >= 0 && ledWidthDp >= 0) {
            "悬浮窗网格参数非法：columns=$columns gap=$gapDp led=$ledWidthDp"
        }
        val fixedWidth = ledWidthDp + gapDp + (columns - 1) * gapDp
        return ((contentWidthDp - fixedWidth) / columns).coerceAtLeast(0)
    }
}

