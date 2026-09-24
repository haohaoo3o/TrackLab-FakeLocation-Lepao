package io.github.haohaoo3o.tracklab

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * 排除项负向验收（静态扫描）：app/src/main/java 下全部源文件（递归）内不得出现
 * Telephony 伪装 / mock 标志抹除 / 第三方应用自动化（含无障碍）等禁词（禁词表见本文件）。
 * 扫描范围与禁词以 CONTRACTS 为准（含注释；docs/ 与 test 源不计）。
 */
class BoundaryNegativeTest {

    /**
     * 禁词表（钉死，命中即红）。注意：本文件自身出现禁词属预期——test 源不在扫描范围。
     */
    private val forbiddenWords = listOf(
        "android.telephony",
        "TelephonyManager",
        "SmsManager",
        "SubscriptionManager",
        "CellIdentity",
        "NeighboringCellInfo",
        "AccessibilityService",
        "AccessibilityNodeInfo",
        "performGlobalAction",
        "isFromMockProvider",
        "setIsFromMockProvider",
        "setHiddenApiExemptions",
        "Xposed",
        "LSPosed",
        "de.robv.android.xposed",
    )

    @Test
    fun mainSourcesContainNoForbiddenWords() {
        val files = sourceFiles()
        assertTrue("扫描范围 app/src/main/java 不应为空", files.isNotEmpty())
        val hits = mutableListOf<String>()
        for (file in files) {
            val text = file.readText()
            for (word in forbiddenWords) {
                if (text.contains(word)) {
                    hits += "${file.name}: 命中禁词 $word"
                }
            }
        }
        if (hits.isNotEmpty()) {
            fail("排除项违例（命中即红）：\n${hits.joinToString("\n")}")
        }
    }

    @Test
    fun scanCoversAllRecursiveMainJavaSources() {
        // 扫描面自检：断言覆盖 app/src/main/java 递归（含注释——逐文件全文 contains）。
        val files = sourceFiles()
        assertTrue("应扫到 service/device/data/ui 全部子包", files.size >= 20)
        val dirs = files.map { it.parentFile?.name ?: "" }.toSet()
        for (expected in listOf("service", "device", "data", "ui", "map", "privacy", "motion", "geo", "export")) {
            assertTrue("扫描面缺包子目录：$expected（实际 $dirs）", expected in dirs)
        }
    }

    // ---------------------------------------------------------------- helpers

    /** 从单测工作目录向上定位 app/src/main/java（工作目录不固定，稳妥解析）。 */
    private fun sourceRoot(): File {
        var dir = File(System.getProperty("user.dir") ?: ".").absoluteFile
        for (step in 0 until 6) {
            val candidate = File(dir, "app/src/main/java")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile ?: break
        }
        error("找不到 app/src/main/java（user.dir=${System.getProperty("user.dir")}）")
    }

    private fun sourceFiles(): List<File> =
        sourceRoot().walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java") }.toList()
}
