package io.github.haohaoo3o.tracklab.ui

import io.github.haohaoo3o.tracklab.ui.overlay.OverlayGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 断言契约见 docs/CONTRACTS.md §8：悬浮窗控件几何不变量——
 * 计算所得控件矩形全部落在给定视口内（含 margin）、互不重叠、命中区 ≥48dp、同输入同输出（纯函数）。
 */
class OverlayGeometryTest {

    /** 一组可行视口（含贴线可行域）+ 自定义 margin/gap 组合。 */
    private val cases = listOf(
        Triple(360, 640, 8),
        Triple(1080, 1920, 8),
        Triple(411, 891, 12),
        Triple(320, 480, 0),
        Triple(240, 320, 4), // 贴近最小可行域
    )

    @Test
    fun allControlRectsStayInsideViewportWithMargin() {
        for ((w, h, margin) in cases) {
            val layout = OverlayGeometry.computeLayout(w, h, marginDp = margin)
            val allowed = OverlayGeometry.Rect(margin, margin, w - margin, h - margin)
            for (rect in layout.all) {
                assertTrue(
                    "控件矩形须落在视口内（含 margin）：$rect ∉ $allowed（viewport=${w}x$h margin=$margin）",
                    rect.inside(allowed),
                )
            }
        }
    }

    @Test
    fun controlsNeverOverlap() {
        for ((w, h, margin) in cases) {
            val layout = OverlayGeometry.computeLayout(w, h, marginDp = margin)
            val rects = layout.all
            for (i in rects.indices) {
                for (j in i + 1 until rects.size) {
                    assertFalse(
                        "控件矩形互不重叠：${rects[i]} × ${rects[j]}（viewport=${w}x$h）",
                        rects[i].overlaps(rects[j]),
                    )
                }
            }
        }
    }

    @Test
    fun hitTargetsAreAtLeast48dp() {
        for ((w, h, margin) in cases) {
            val layout = OverlayGeometry.computeLayout(w, h, marginDp = margin)
            for ((name, rect) in listOf("pause" to layout.pause, "stop" to layout.stop)) {
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
}
