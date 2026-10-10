package io.github.haohaoo3o.tracklab.ui

import io.github.haohaoo3o.tracklab.ui.overlay.OverlayGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.roundToInt

/**
 * 断言契约见 docs/CONTRACTS.md §8（补充验收行——终审 feedback）：悬浮窗控件几何不变量——
 * 计算所得控件矩形全部落在给定视口内（含 margin）、互不重叠、命中区 ≥48dp、同输入同输出（纯函数）。
 * 填充人：playback 阶段。
 */
class OverlayGeometryTest {

    /** 一组可行视口（含贴线可行域）+ 自定义 margin/gap 组合。 */
    private val cases = listOf(
        Triple(360, 640, 8),
        Triple(1080, 1920, 8),
        Triple(411, 891, 12),
        Triple(320, 480, 0),
        Triple(280, 320, 4), // 贴近最小可行域（四键 minWidth=264 + margin；展开高 104 + margin）
    )

    @Test
    fun allControlRectsStayInsideViewportWithMargin() {
        for ((w, h, margin) in cases) {
            // 展开/折叠两档：非零高矩形全部落在视口内（含 margin）。
            for (expanded in listOf(true, false)) {
                val layout = OverlayGeometry.computeLayout(w, h, marginDp = margin, expanded = expanded)
                val allowed = OverlayGeometry.Rect(margin, margin, w - margin, h - margin)
                for (rect in layout.solid) {
                    assertTrue(
                        "控件矩形须落在视口内（含 margin）：$rect ∉ $allowed" +
                            "（viewport=${w}x$h margin=$margin expanded=$expanded）",
                        rect.inside(allowed),
                    )
                }
            }
        }
    }

    @Test
    fun controlsNeverOverlap() {
        for ((w, h, margin) in cases) {
            // 两档：非零高矩形两两不重叠（折叠态 gridMetrics 为零高投影，天然排除）。
            for (expanded in listOf(true, false)) {
                val layout = OverlayGeometry.computeLayout(w, h, marginDp = margin, expanded = expanded)
                val rects = layout.solid
                for (i in rects.indices) {
                    for (j in i + 1 until rects.size) {
                        assertFalse(
                            "控件矩形互不重叠：${rects[i]} × ${rects[j]}（viewport=${w}x$h expanded=$expanded）",
                            rects[i].overlaps(rects[j]),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun hitTargetsAreAtLeast48dp() {
        for ((w, h, margin) in cases) {
            val layout = OverlayGeometry.computeLayout(w, h, marginDp = margin)
            for ((name, rect) in listOf(
                "pause" to layout.pause,
                "stop" to layout.stop,
                "fold" to layout.fold,
            )) {
                assertTrue(
                    "$name 命中区宽 ≥ 48dp：$rect（viewport=${w}x$h）",
                    rect.width >= OverlayGeometry.MIN_HIT_TARGET_DP,
                )
                assertTrue(
                    "$name 命中区高 ≥ 48dp：$rect（viewport=${w}x$h）",
                    rect.height >= OverlayGeometry.MIN_HIT_TARGET_DP,
                )
            }
        }
    }

    @Test
    fun layoutIsPureDeterministic() {
        for ((w, h, margin) in cases) {
            val a = OverlayGeometry.computeLayout(w, h, marginDp = margin)
            val b = OverlayGeometry.computeLayout(w, h, marginDp = margin)
            assertEquals("同输入同输出（纯函数）：${w}x$h", a, b)
        }
    }

    // ---------------------------------------------------------------- Matrix 扩展 5 用例（overlay-geometry-spec.md）

    /** ① foldKeyHitTarget：新老签名下 pause/stop/fold 宽高均 ≥48dp。 */
    @Test
    fun foldKeyHitTarget() {
        for ((w, h, margin) in cases) {
            for (layout in listOf(
                OverlayGeometry.computeLayout(w, h, marginDp = margin),
                OverlayGeometry.computeLayout(w, h, marginDp = margin, expanded = true),
                OverlayGeometry.computeLayout(w, h, marginDp = margin, expanded = false),
            )) {
                for ((name, rect) in listOf(
                    "pause" to layout.pause,
                    "stop" to layout.stop,
                    "fold" to layout.fold,
                )) {
                    assertTrue(
                        "$name 命中区 ≥ 48dp：$rect（viewport=${w}x$h expanded=${layout.expanded}）",
                        rect.width >= OverlayGeometry.MIN_HIT_TARGET_DP &&
                            rect.height >= OverlayGeometry.MIN_HIT_TARGET_DP,
                    )
                }
            }
        }
    }

    /** ② collapsedAndExpandedInsideAndDisjoint：两档①全部非零高矩形在视口内、②两两不重叠。 */
    @Test
    fun collapsedAndExpandedInsideAndDisjoint() {
        for ((w, h, margin) in cases) {
            for (expanded in listOf(true, false)) {
                val layout = OverlayGeometry.computeLayout(w, h, marginDp = margin, expanded = expanded)
                val allowed = OverlayGeometry.Rect(margin, margin, w - margin, h - margin)
                for (rect in layout.solid) {
                    assertTrue(
                        "expanded=$expanded 控件矩形须在视口内：$rect ∉ $allowed（viewport=${w}x$h）",
                        rect.inside(allowed),
                    )
                }
                val rects = layout.solid
                for (i in rects.indices) {
                    for (j in i + 1 until rects.size) {
                        assertFalse(
                            "expanded=$expanded 控件矩形互不重叠：${rects[i]} × ${rects[j]}（viewport=${w}x$h）",
                            rects[i].overlaps(rects[j]),
                        )
                    }
                }
                if (expanded) {
                    assertTrue(
                        "展开态 gridMetrics 须为可见行（高 48dp）：${layout.gridMetrics}",
                        layout.gridMetrics.height >= OverlayGeometry.MIN_HIT_TARGET_DP,
                    )
                } else {
                    assertEquals(
                        "折叠态 gridMetrics 取零高投影（top == bottom，不参与②重叠断言）",
                        layout.gridMetrics.top,
                        layout.gridMetrics.bottom,
                    )
                }
            }
        }
    }

    /** ③ defaultArgIsExpandedAndPure：不传 expanded 等价 expanded=true，且同输入同输出。 */
    @Test
    fun defaultArgIsExpandedAndPure() {
        for ((w, h, margin) in cases) {
            val implicit = OverlayGeometry.computeLayout(w, h, marginDp = margin)
            val explicit = OverlayGeometry.computeLayout(w, h, marginDp = margin, expanded = true)
            assertTrue("默认即展开", implicit.expanded)
            assertEquals("不传 expanded 等价 expanded=true：${w}x$h", explicit, implicit)
            assertEquals(
                "同输入同输出（纯函数④）：${w}x$h",
                OverlayGeometry.computeLayout(w, h, marginDp = margin, expanded = false),
                OverlayGeometry.computeLayout(w, h, marginDp = margin, expanded = false),
            )
        }
    }

    /** ④ tooSmallStillThrowsChinese：视口不足仍抛中文错（四键版 minWidth=96+gap+48+gap+48+gap+48）。 */
    @Test
    fun tooSmallStillThrowsChinese() {
        for ((w, h) in listOf(100 to 200, 200 to 30, 40 to 40, 240 to 320)) {
            for (expanded in listOf(true, false)) {
                try {
                    OverlayGeometry.computeLayout(w, h, expanded = expanded)
                    fail("视口过小应抛 IllegalArgumentException：${w}x$h expanded=$expanded")
                } catch (e: IllegalArgumentException) {
                    assertTrue(
                        "视口不足须抛中文错（expanded=$expanded）：${e.message}",
                        e.message.orEmpty().contains("视口"),
                    )
                }
            }
        }
    }

    /** ⑤ clampUnchanged：clampWindowPosition 拖动语义不动（旧用例全量通过即证）。 */
    @Test
    fun clampUnchangedAfterMatrixExtension() {
        val clamped = OverlayGeometry.clampWindowPosition(10, 10, 500, 700, 360, 640, 8)
        assertEquals(OverlayGeometry.Position(8, 8), clamped)
        val inside = OverlayGeometry.clampWindowPosition(123, 456, 200, 100, 360, 640, 8)
        val window = OverlayGeometry.Rect(inside.x, inside.y, inside.x + 200, inside.y + 100)
        val allowed = OverlayGeometry.Rect(8, 8, 352, 632)
        assertTrue("拖动落点后窗口矩形须在视口内：$window ∉ $allowed", window.inside(allowed))
    }

    @Test
    fun tooSmallViewportIsExplicitlyRejected() {
        for ((w, h) in listOf(100 to 200, 200 to 30, 40 to 40)) {
            try {
                OverlayGeometry.computeLayout(w, h)
                fail("视口过小应抛 IllegalArgumentException：${w}x$h")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message.orEmpty().contains("视口"))
            }
        }
    }

    // ---------------------------------------------------------------- 可拖动悬浮窗落点钳制

    @Test
    fun clampWindowPositionKeepsWindowInsideViewport() {
        val vw = 360
        val vh = 640
        val ww = 200
        val wh = 100
        val margin = OverlayGeometry.DEFAULT_MARGIN_DP
        val dragPoints = listOf(
            OverlayGeometry.Position(-500, -500),
            OverlayGeometry.Position(5000, 5000),
            OverlayGeometry.Position(-1, 300),
            OverlayGeometry.Position(200, -2),
            OverlayGeometry.Position(123, 456),
        )
        for (p in dragPoints) {
            val clamped = OverlayGeometry.clampWindowPosition(p.x, p.y, ww, wh, vw, vh, margin)
            val window = OverlayGeometry.Rect(clamped.x, clamped.y, clamped.x + ww, clamped.y + wh)
            val allowed = OverlayGeometry.Rect(margin, margin, vw - margin, vh - margin)
            assertTrue("拖动落点后窗口矩形须在视口内（含 margin）：$window ∉ $allowed", window.inside(allowed))
            // 幂等（纯函数 + 稳定不动点）
            val again = OverlayGeometry.clampWindowPosition(clamped.x, clamped.y, ww, wh, vw, vh, margin)
            assertEquals(clamped, again)
        }
    }

    @Test
    fun clampOfWindowLargerThanViewportPinsToMarginOrigin() {
        // 窗口在两轴上均大于『视口 − 2·margin』：贴 margin 原点（已知退化，不静默越界）
        val clamped = OverlayGeometry.clampWindowPosition(10, 10, 500, 700, 360, 640, 8)
        assertEquals(OverlayGeometry.Position(8, 8), clamped)
    }

    // ---------------------------------------------------------------- v2 悬浮窗宽度算式（DESIGN.md §4.3/§4.4）

    /**
     * 主行固定宽算式自洽：抓手 2 + 间隙 8 + 标记格 30 + 间隙 8 + 三键 3×48 + 键间 2×8 = 216dp。
     * 与 DESIGN §4.4 的行宽实算 `2 + 8 + 指标格 + 8 + 30 + 8 + 48 + 8 + 48 + 8 + 48 = 216 + 指标格` 逐项对齐。
     */
    @Test
    fun mainRowFixedWidthMatchesDesignArithmetic() {
        val byConstants = OverlayGeometry.GRIP_WIDTH_DP +
            OverlayGeometry.DEFAULT_GAP_DP +                       // 抓手 → 指标格
            OverlayGeometry.DEFAULT_GAP_DP + OverlayGeometry.MARKER_WIDTH_DP +
            OverlayGeometry.DEFAULT_GAP_DP +                       // 标记格 marginStart + 本体 + marginEnd
            OverlayGeometry.MAIN_KEY_COUNT * OverlayGeometry.MIN_HIT_TARGET_DP +
            (OverlayGeometry.MAIN_KEY_COUNT - 1) * OverlayGeometry.DEFAULT_GAP_DP
        assertEquals("主行固定宽须由常量推出 216dp（改任一常量即失配）", 216, byConstants)
        assertEquals("MAIN_ROW_FIXED_WIDTH_DP 须等于同一算式", byConstants, OverlayGeometry.MAIN_ROW_FIXED_WIDTH_DP)
        assertEquals("三键命中区 48dp（§4.4）", 48, OverlayGeometry.MIN_HIT_TARGET_DP)
    }

    /**
     * 窗宽 / 内容宽 / 指标格实得宽与 DESIGN §4.4 表逐值一致（411dp→380/364/148、360dp→344/328/112、
     * 320dp→304/288/72）——把规范里的手算表钉进纯函数，避免"改布局忘了改公式"。
     */
    @Test
    fun windowContentAndMetricsWidthsMatchDesignTable() {
        val rows = listOf(
            Triple(411, 380, 148),
            Triple(360, 344, 112),
            Triple(320, 304, 72),
        )
        for ((viewport, window, metricsWidth) in rows) {
            val windowWidth = OverlayGeometry.computeWindowWidthDp(viewport)
            assertEquals("窗宽 = min(380, 视口−16)：视口 ${viewport}dp", window, windowWidth)
            val contentWidth = OverlayGeometry.computeContentWidthDp(windowWidth)
            assertEquals("内容宽 = 窗宽 − 2×8：视口 ${viewport}dp", window - 16, contentWidth)
            assertEquals(
                "指标格实得宽：视口 ${viewport}dp",
                metricsWidth,
                OverlayGeometry.computeMetricsWidthDp(contentWidth),
            )
        }
    }

    /** 指标格 96dp 模型下限（METRICS_MIN_WIDTH_DP）的精确边界：视口 343dp 差 1dp、344dp 恰好达标。 */
    @Test
    fun metricsModelFloorBoundaryIsExact() {
        fun metricsWidthAt(viewportWidthDp: Int): Int = OverlayGeometry.computeMetricsWidthDp(
            OverlayGeometry.computeContentWidthDp(OverlayGeometry.computeWindowWidthDp(viewportWidthDp)),
        )
        assertEquals(95, metricsWidthAt(343))
        assertEquals("视口 344dp 起指标格才达到 96dp 模型下限", 96, metricsWidthAt(344))
        assertTrue(
            "视口 ≥ 344dp 时指标格不得低于模型下限",
            (344..1440 step 8).all { metricsWidthAt(it) >= OverlayGeometry.METRICS_MIN_WIDTH_DP },
        )
    }

    /**
     * 窗宽公式与 [OverlayGeometry.computeLayout] 的可行域自洽：视口 ≥ 296dp 时
     * 内容宽 ≥ 模型下限（96 + 3×48 + 3×8 = 264dp），且展开态 4 格每格 ≥ 48dp、指标格 ≥ 48dp。
     * 296dp 是两者的合流边界（内容宽恰好 264dp）。
     */
    @Test
    fun windowFormulaAndGridCellsStayFeasible() {
        val modelMinContentWidth =
            OverlayGeometry.METRICS_MIN_WIDTH_DP +
                OverlayGeometry.MAIN_KEY_COUNT * OverlayGeometry.MIN_HIT_TARGET_DP +
                OverlayGeometry.MAIN_KEY_COUNT * OverlayGeometry.DEFAULT_GAP_DP
        assertEquals("模型下限内容宽 = 96 + 3×48 + 3×8 = 264dp", 264, modelMinContentWidth)
        for (viewportWidthDp in 296..1440 step 8) {
            val content = OverlayGeometry.computeContentWidthDp(
                OverlayGeometry.computeWindowWidthDp(viewportWidthDp),
            )
            assertTrue(
                "视口 ${viewportWidthDp}dp：内容宽 $content 不得低于模型下限 $modelMinContentWidth",
                content >= modelMinContentWidth,
            )
            // 与 computeLayout 的可行域一致（不抛即代表模型接受该视口）。
            OverlayGeometry.computeLayout(viewportWidthDp, 891, expanded = true)
            val cell = OverlayGeometry.computeGridCellWidthDp(content)
            assertTrue("视口 ${viewportWidthDp}dp：网格单格 $cell 不得低于 48dp", cell >= OverlayGeometry.MIN_HIT_TARGET_DP)
            val metricsWidth = OverlayGeometry.computeMetricsWidthDp(content)
            assertTrue(
                "视口 ${viewportWidthDp}dp：指标格 $metricsWidth 不得低于 48dp（可截断，但不能挤没）",
                metricsWidth >= OverlayGeometry.MIN_HIT_TARGET_DP,
            )
        }
        assertEquals("296dp 视口：内容宽恰好落在模型下限", 264, OverlayGeometry.computeContentWidthDp(OverlayGeometry.computeWindowWidthDp(296)))
    }

    /** 展开态 4 格网格（LED 16dp + 4 格 + 4×8dp 间隔）在常见视口下的实得宽（≥48dp）。 */
    @Test
    fun gridCellWidthsMatchWindowSelfLayout() {
        val rows = listOf(411 to 79, 360 to 70, 320 to 60, 296 to 54)
        for ((viewport, expectedCell) in rows) {
            val content = OverlayGeometry.computeContentWidthDp(OverlayGeometry.computeWindowWidthDp(viewport))
            assertEquals("视口 ${viewport}dp 单格宽", expectedCell, OverlayGeometry.computeGridCellWidthDp(content))
            assertEquals(
                "四格 + LED + 四个间隔不得超出内容宽：视口 ${viewport}dp",
                4 * expectedCell + OverlayGeometry.LED_WIDTH_DP + 4 * OverlayGeometry.DEFAULT_GAP_DP <= content,
                true,
            )
        }
        assertEquals("网格列数 = 圈/距离/配速/步频", 4, OverlayGeometry.GRID_COLUMNS)
        assertEquals("LED 16dp 宿主", 16, OverlayGeometry.LED_WIDTH_DP)
    }

    /**
     * 展开态模型网格行与 XML 实排一致：紧贴主行下方 8dp（GRID_ROW_GAP_DP）、高 48dp
     * （GRID_ROW_HEIGHT_DP，= 三键命中区高）；折叠动效的插值目标 = 二者之和 56dp。
     */
    @Test
    fun expandedGridRowMatchesWindowSelfLayout() {
        val layout = OverlayGeometry.computeLayout(411, 891, marginDp = 8, expanded = true)
        assertEquals(OverlayGeometry.GRID_ROW_HEIGHT_DP, layout.gridMetrics.height)
        assertEquals(layout.metrics.bottom + OverlayGeometry.GRID_ROW_GAP_DP, layout.gridMetrics.top)
        // 指标格是主行最左块（其右边界止于暂停键），网格行则左右贴 margin —— 用内容边界比：
        assertEquals(layout.metrics.left, layout.gridMetrics.left)
        assertEquals(layout.fold.right, layout.gridMetrics.right)
        assertEquals(
            "折叠动效插值目标 = gap 8 + 行高 48 = 56dp",
            56,
            OverlayGeometry.GRID_ROW_GAP_DP + OverlayGeometry.GRID_ROW_HEIGHT_DP,
        )
        // 折叠态：网格行取零高投影（不占高、不参与重叠断言），窗口高度差恰为 56dp。
        val collapsed = OverlayGeometry.computeLayout(411, 891, marginDp = 8, expanded = false)
        assertEquals(collapsed.gridMetrics.top, collapsed.gridMetrics.bottom)
        assertEquals(56, layout.gridMetrics.bottom - collapsed.gridMetrics.bottom)
    }

    /** 新增宽度算式同输入同输出（纯函数④），且不改动既有 5 矩形模型的输出。 */
    @Test
    fun widthHelpersArePureAndDoNotDisturbLayoutModel() {
        for (viewportWidthDp in listOf(296, 320, 360, 411, 1080)) {
            assertEquals(
                "computeWindowWidthDp 同输入同输出：${viewportWidthDp}dp",
                OverlayGeometry.computeWindowWidthDp(viewportWidthDp),
                OverlayGeometry.computeWindowWidthDp(viewportWidthDp),
            )
            val window = OverlayGeometry.computeWindowWidthDp(viewportWidthDp)
            assertEquals(
                "computeContentWidthDp 同输入同输出：${viewportWidthDp}dp",
                OverlayGeometry.computeContentWidthDp(window),
                OverlayGeometry.computeContentWidthDp(window),
            )
            val content = OverlayGeometry.computeContentWidthDp(window)
            assertEquals(
                "computeMetricsWidthDp 同输入同输出：${viewportWidthDp}dp",
                OverlayGeometry.computeMetricsWidthDp(content),
                OverlayGeometry.computeMetricsWidthDp(content),
            )
            assertEquals(
                "computeGridCellWidthDp 同输入同输出：${viewportWidthDp}dp",
                OverlayGeometry.computeGridCellWidthDp(content),
                OverlayGeometry.computeGridCellWidthDp(content),
            )
            // 5 矩形模型与旧口径一字未动（新旧调用等价）。
            assertEquals(
                "既有模型输出不得因 v2 扩展改变：${viewportWidthDp}dp",
                OverlayGeometry.computeLayout(viewportWidthDp, 891, marginDp = 8, gapDp = 8, expanded = true),
                OverlayGeometry.computeLayout(viewportWidthDp, 891, marginDp = 8, gapDp = 8, expanded = true),
            )
        }
    }

    /** 新增算式对非法参数的显式中文拒绝（不静默返回错值）；show() 路径上的窗宽函数永不抛（退化返回 0）。 */
    @Test
    fun widthHelpersRejectIllegalArgsInChinese() {
        val cases = listOf<Pair<String, () -> Unit>>(
            "负 margin" to { OverlayGeometry.computeWindowWidthDp(360, marginDp = -1) },
            "maxWidth 0" to { OverlayGeometry.computeWindowWidthDp(360, maxWidthDp = 0) },
            "负内边距" to { OverlayGeometry.computeContentWidthDp(320, paddingDp = -8) },
            "列数 0" to { OverlayGeometry.computeGridCellWidthDp(300, columns = 0) },
            "负 gap" to { OverlayGeometry.computeGridCellWidthDp(300, gapDp = -1) },
            "负 LED 宽" to { OverlayGeometry.computeGridCellWidthDp(300, ledWidthDp = -1) },
        )
        for ((name, block) in cases) {
            try {
                block()
                fail("非法参数应抛 IllegalArgumentException：$name")
            } catch (e: IllegalArgumentException) {
                assertTrue(
                    "非法参数须抛中文错（$name）：${e.message}",
                    e.message.orEmpty().contains("悬浮窗"),
                )
            }
        }
        assertEquals("视口比 2×margin 还窄时窗宽退化 0（show() 路径不抛）", 0, OverlayGeometry.computeWindowWidthDp(4))
        assertEquals("栅格宽不足时单格退化 0（不抛）", 0, OverlayGeometry.computeGridCellWidthDp(40))
        assertEquals("内容宽不足时指标格退化 0（不抛）", 0, OverlayGeometry.computeMetricsWidthDp(100))
    }

    // ------------------------------------------- 初始落点（2026-10-10 真机复审问题 1）

    /**
     * 首测真机口径（1080×2400 @440dpi = 393×873dp；窗宽 min(380, 393−16) = 377dp、窗高 120dp）：
     * 初始 x = (393 − 377)/2 = **8dp = margin**，左右边距相等。
     * 对照缺陷：旧实现 `x = 16dp` ⇒ `16 + 377 + 16 = 409 > 393`，右边距被挤成 0
     * （真机实测左边距 43px / 右边距 0，右圆角被屏幕切平）。
     */
    @Test
    fun initialWindowPositionCentersOnDeviceViewport() {
        val margin = OverlayGeometry.DEFAULT_MARGIN_DP
        val windowWidth = 377
        val windowHeight = 120
        val p = OverlayGeometry.initialWindowPosition(
            windowWidthDp = windowWidth,
            windowHeightDp = windowHeight,
            viewportWidthDp = 393,
            viewportHeightDp = 873,
            desiredYDp = 96,
        )
        assertEquals("x = (393 − 377)/2 = 8dp = margin（旧实现此处是 16dp）", 8, p.x)
        assertEquals("y 取 desiredY（96dp 在界内，不动）", 96, p.y)
        assertEquals("左右边距相等（dp）：$p", p.x, 393 - p.x - windowWidth)
        assertTrue("左边距 ≥ margin", p.x >= margin)
        assertTrue("右边距 ≥ margin", 393 - p.x - windowWidth >= margin)
        assertTrue(
            "窗口矩形须落在视口内（含 margin）：$p " +
                "（旧落点 16dp 时 16 + 377 + 16 = 409 > 393 —— 缺陷复现条件）",
            OverlayGeometry.Rect(p.x, p.y, p.x + windowWidth, p.y + windowHeight)
                .inside(OverlayGeometry.Rect(margin, margin, 393 - margin, 873 - margin)),
        )
    }

    /** 宽视口（平板 800dp / 窗宽封顶 380dp）：仍居中（210/210），而不是贴左 16dp（那会右距 404dp）。 */
    @Test
    fun initialWindowPositionCentersOnWideViewport() {
        val p = OverlayGeometry.initialWindowPosition(
            windowWidthDp = 380,
            windowHeightDp = 120,
            viewportWidthDp = 800,
            viewportHeightDp = 1280,
            desiredYDp = 96,
        )
        assertEquals("x = (800 − 380)/2 = 210dp（居中，非贴左）", 210, p.x)
        assertEquals("左右边距相等：$p", p.x, 800 - p.x - 380)
    }

    /** 边距差为奇数 dp（视口 393 / 窗宽 376）：整数 dp 下左右相差 1dp，两边都仍 ≥ margin。 */
    @Test
    fun initialWindowPositionIsWithinOneDpOfSymmetricOnOddDifference() {
        val p = OverlayGeometry.initialWindowPosition(376, 120, 393, 873, desiredYDp = 96)
        assertEquals("x = ⌊(393 − 376)/2⌋ = 8dp", 8, p.x)
        val rightMargin = 393 - p.x - 376
        assertEquals("右距 = 9dp（与左距 8dp 相差 1dp：整数 dp 不可再分）", 9, rightMargin)
        assertTrue("两侧都 ≥ margin", p.x >= 8 && rightMargin >= 8)
    }

    /**
     * 退化口径（如实，写清楚）：窗口比『视口 − 2·margin』宽时 x/y 都贴 margin 原点——
     * 与 [OverlayGeometry.clampWindowPosition] **同一条代码路径**（同一函数），不静默越界。
     */
    @Test
    fun initialWindowPositionPinsToMarginWhenWindowTooLarge() {
        val p = OverlayGeometry.initialWindowPosition(
            windowWidthDp = 500,
            windowHeightDp = 700,
            viewportWidthDp = 360,
            viewportHeightDp = 640,
            desiredYDp = 96,
        )
        assertEquals("窗宽 500 > 360 − 16：x 贴 margin 原点", 8, p.x)
        assertEquals("窗高 700 > 640 − 16：y 也被钳到 margin 原点（desiredY 96 不再生效）", 8, p.y)
        assertEquals(
            "与 clampWindowPosition 完全同解（同一套语义，控制器未另写算法）",
            OverlayGeometry.clampWindowPosition(0, 96, 500, 700, 360, 640),
            p,
        )
    }

    /** 纵向越界（矮视口）：desiredY 按同一语义钳回，横向仍居中。 */
    @Test
    fun initialWindowPositionClampsDesiredYDp() {
        val p = OverlayGeometry.initialWindowPosition(377, 120, 393, 200, desiredYDp = 96)
        assertEquals("y ≤ 200 − 8 − 120 = 72dp", 72, p.y)
        assertEquals("横向不受视口高影响，仍居中 8dp", 8, p.x)
    }

    /**
     * 纯函数④（同输入同输出）+ 落点是 [OverlayGeometry.clampWindowPosition] 的不动点
     * （初始落点本身就在钳制后的可行域内 ⇒ 拖动前的"未钳位"缺陷不会回潮）。
     */
    @Test
    fun initialWindowPositionIsPureAndFixedPointOfClamp() {
        val cases = listOf(
            Triple(377, 393, 873), // 真机
            Triple(200, 360, 640), // 窄窗
            Triple(380, 800, 1280), // 平板
            Triple(500, 360, 640), // 退化（窗比视口宽）
            Triple(376, 393, 873), // 奇数差
        )
        for ((windowWidth, viewportWidth, viewportHeight) in cases) {
            val a = OverlayGeometry.initialWindowPosition(windowWidth, 120, viewportWidth, viewportHeight, desiredYDp = 96)
            val b = OverlayGeometry.initialWindowPosition(windowWidth, 120, viewportWidth, viewportHeight, desiredYDp = 96)
            assertEquals("同输入同输出（纯函数④）：窗 $windowWidth / 视口 $viewportWidth", a, b)
            assertEquals(
                "初始落点是钳制的不动点（clampWindowPosition 同一语义）：$a",
                a,
                OverlayGeometry.clampWindowPosition(a.x, a.y, windowWidth, 120, viewportWidth, viewportHeight),
            )
        }
        // 真机换算到 px（density 2.75）后左右边距仍相等：窗宽 1036px（向下取整）、x = 22px。
        assertEquals("377dp @2.75 向下取整 = 1036px（不是 1037）", 1036, (377 * 2.75).toInt())
        assertEquals("x = 8dp @2.75 = 22px", 22, (8 * 2.75).roundToInt())
        assertEquals("1080 − 22 − 1036 = 22px = 8dp", 22, 1080 - 22 - 1036)
    }
}
