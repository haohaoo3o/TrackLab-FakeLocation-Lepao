package io.github.haohaoo3o.tracklab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 双语键集校验（docs/CONTRACTS.md §10）——保持常驻：
 * 1. `values/strings.xml` 与 `values-en/strings.xml` 键集相等（新增文案键必须双语同步）；
 * 2. 合规常驻文案（test_mode_badge / notification_title）在两语言下均含“测试”字样，
 *    使 SmokeTest 断言 locale 无关。
 * lint 侧另有 app/lint.xml 忽略 MissingTranslation/ExtraTranslation（双保险之二）。
 */
class StringsSyncTest {

    @Test
    fun stringKeysAreIdenticalAcrossLocales() {
        val zh = stringEntries(valuesFile("values"))
        val en = stringEntries(valuesFile("values-en"))
        assertTrue("values/strings.xml 不应为空", zh.isNotEmpty())
        assertEquals("values 与 values-en 键集必须相等（CONTRACTS §10）", zh.keys, en.keys)
    }

    @Test
    fun complianceStringsContainTestMarkerInAllLocales() {
        for (dir in listOf("values", "values-en")) {
            val entries = stringEntries(valuesFile(dir))
            for (key in listOf("test_mode_badge", "notification_title")) {
                val value = entries[key] ?: error("缺少合规文案键 $key（$dir）")
                assertTrue(
                    "合规文案 $key（$dir）必须含“测试”字样（locale 无关），实际：$value",
                    value.contains("测试")
                )
            }
        }
    }

    // ---- helpers ----

    /** 从单测工作目录向上查找 app/src/main/res（工作目录不固定，稳妥解析）。 */
    private fun valuesFile(dirName: String): File {
        var dir: File = File(System.getProperty("user.dir") ?: ".").absoluteFile
        for (step in 0 until 6) {
            val candidate = File(dir, "app/src/main/res/$dirName/strings.xml")
            if (candidate.isFile) return candidate
            dir = dir.parentFile ?: break
        }
        error("找不到 app/src/main/res/$dirName/strings.xml（user.dir=${System.getProperty("user.dir")}）")
    }

    /** 解析 <string name="...">…</string> 为 name→text 平表（不处理复数/数组，键表只用 string）。 */
    private fun stringEntries(file: File): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        val result = LinkedHashMap<String, String>()
        for (i in 0 until nodes.length) {
            val element = nodes.item(i)
            val name = element.attributes.getNamedItem("name")?.nodeValue ?: continue
            result[name] = element.textContent
        }
        return result
    }
}
