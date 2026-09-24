package io.github.haohaoo3o.tracklab.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 回归测试·静态护栏（实机行为由 androidTest 的 FloatingOverlayThreadingTest
 * 在真机核对，此处钉死结构；扫描前剥掉注释，行结构保留）：
 *
 * 1. [io.github.haohaoo3o.tracklab.ui.overlay.FloatingOverlayController] 的全部 View/WindowManager 突变
 *    只允许出现在主线程执行体（*OnMainThread）与触摸回调（attachDragHandler，View 体系恒主线程派发）内；
 * 2. 五个公开入口（show/update/updateMetrics/setPaused/hide）必须经
 *    [io.github.haohaoo3o.tracklab.ui.overlay.MainThreadMarshal] 封送（sync=show/hide、async=更新类）——
 *    后台线程直触 addView/setText 即 RuntimeException / CalledFromWrongThreadException 崩溃（回归根因）；
 * 3. 服务侧（PlaybackForegroundService）不得直触 WindowManager/View 突变——HUD 一律经
 *    FloatingOverlayController 封送，绕过即重开崩溃路径。
 *
 * 纯 JVM 可执行（源码扫描）。
 */
class OverlayThreadingGuardTest {

    private val allowedMutationHosts = setOf(
        "showOnMainThread",
        "hideOnMainThread",
        "setMetricsOnMainThread",
        "setPausedOnMainThread",
        "attachDragHandler",
    )

    @Test
    fun viewAndWindowMutationsOnlyInsideMainThreadingBodies() {
        val code = codeOnly(controllerSource().readText())
        val funHeader = Regex("""\bfun\s+(\w+)\s*\(""")
        val mutation = Regex(
            """windowManager\.(addView|removeView|updateViewLayout)|metricsView(\s*=|\?\.text)|""" +
                """pauseButton(\s*=|\?\.setText|\?\.contentDescription)|root\s*=|layoutParams\s*=""",
        )
        val violations = mutableListOf<String>()
        var currentFun = "<top-level>"
        for (line in code.lineSequence()) {
            funHeader.find(line)?.let { currentFun = it.groupValues[1] }
            if (mutation.containsMatchIn(line) && currentFun !in allowedMutationHosts) {
                violations.add("[$currentFun] ${line.trim()}")
            }
        }
        assertTrue(
            "View/WindowManager 突变只允许出现在主线程执行体/触摸回调内：$violations",
            violations.isEmpty(),
        )
    }

    @Test
    fun publicEntryPointsAllDelegateThroughMainThreadMarshal() {
        val code = codeOnly(controllerSource().readText())
        val required = mapOf(
            Regex("""fun\s+show\(\)\s*:\s*Boolean\s*=\s*marshal\.sync""") to "show() 须同步封送主线程",
            Regex("""fun\s+hide\(\)\s*=\s*marshal\.sync""") to "hide() 须同步封送主线程",
            Regex("""fun\s+update\(metricsText: String, paused: Boolean\)\s*=\s*marshal\.async""") to
                "update() 须异步封送主线程",
            Regex("""fun\s+updateMetrics\(text: String\)\s*=\s*marshal\.async""") to
                "updateMetrics() 须异步封送主线程",
            Regex("""fun\s+setPaused\(paused: Boolean\)\s*=\s*marshal\.async""") to
                "setPaused() 须异步封送主线程",
        )
        for ((regex, message) in required) {
            assertTrue(message, regex.containsMatchIn(code))
        }
        assertTrue("须持有 MainThreadMarshal 实例", code.contains("MainThreadMarshal("))
    }

    @Test
    fun playbackServiceNeverTouchesWindowManagerDirectly() {
        // 服务侧只经 FloatingControlHost → FloatingOverlayController 触达 HUD；服务代码不得直触
        // WindowManager/View 突变（否则绕开封送，回归崩溃路径重开）。
        val code = codeOnly(serviceSource().readText())
        for (forbidden in listOf("windowManager", "WindowManager", "addView", "updateViewLayout")) {
            assertTrue(
                "PlaybackForegroundService 不得直触 $forbidden（HUD 一律经 FloatingOverlayController 封送）：$forbidden",
                !code.contains(forbidden),
            )
        }
        assertTrue("HUD 更新须经 FloatingControlHost.update（整帧一次封送）", code.contains("overlay?.update("))
    }

    // ---- helpers（源码定位口径同 PlaybackStateMachineTest 的静态护栏）----

    private fun controllerSource(): File =
        sourceFile("app/src/main/java/io/github/haohaoo3o/tracklab/ui/overlay/FloatingOverlayController.kt")

    private fun serviceSource(): File =
        sourceFile("app/src/main/java/io/github/haohaoo3o/tracklab/service/PlaybackForegroundService.kt")

    private fun sourceFile(relative: String): File {
        var dir = File(System.getProperty("user.dir") ?: ".").absoluteFile
        for (step in 0 until 6) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            dir = dir.parentFile ?: break
        }
        error("找不到 $relative（user.dir=${System.getProperty("user.dir")}）")
    }

    /** 剥掉 // 行注释与 /* … */（含 KDoc）块注释；行数保持（每行注释体置空），结构扫描不误伤。 */
    private fun codeOnly(text: String): String {
        val out = StringBuilder()
        var inBlock = false
        for (line in text.lineSequence()) {
            var s = line
            if (inBlock) {
                val end = s.indexOf("*/")
                if (end < 0) {
                    s = ""
                } else {
                    s = s.substring(end + 2)
                    inBlock = false
                }
            }
            val sb = StringBuilder()
            var i = 0
            while (i < s.length) {
                when {
                    s.startsWith("//", i) -> break
                    s.startsWith("/*", i) -> {
                        val end = s.indexOf("*/", i + 2)
                        if (end < 0) {
                            inBlock = true
                            break
                        }
                        i = end + 2
                    }
                    else -> {
                        sb.append(s[i])
                        i++
                    }
                }
            }
            out.appendLine(sb)
        }
        return out.toString()
    }
}
