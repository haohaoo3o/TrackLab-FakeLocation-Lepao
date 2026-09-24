package io.github.haohaoo3o.tracklab.map

import io.github.haohaoo3o.tracklab.ui.map.KeyProvider
import io.github.haohaoo3o.tracklab.ui.map.MetaDataKeyProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 断言契约见 docs/CONTRACTS.md（KeyProvider/MetaDataKeyProvider；无 AMAP_API_KEY 构建成功
 * + NEEDS_KEY 提示态；注入链路 local.properties|环境变量→manifestPlaceholders→meta-data）。
 * **Key 只能从构建配置注入**：以静态检查钉死 Manifest meta-data 占位符与 Gradle 注入点，
 * 防止任何硬编码 Key 回流。纯 JVM 直测。
 */
class KeyProviderTest {

    // ---------------------------------------------------------------- KeyProvider / MetaDataKeyProvider

    @Test
    fun metaDataProviderReadsPinnedMetaDataName() {
        var requested: String? = null
        val provider = MetaDataKeyProvider { key ->
            requested = key
            "  injected-key  "
        }
        assertEquals("injected-key", provider.apiKey())
        assertEquals(KeyProvider.META_DATA_API_KEY, requested)
        assertEquals("com.amap.api.v2.apikey", KeyProvider.META_DATA_API_KEY)
    }

    @Test
    fun metaDataProviderTrimsAndTreatsBlankAsMissing() {
        assertNull("null ⇒ 无 Key", MetaDataKeyProvider { null }.apiKey())
        assertNull("空串 ⇒ 无 Key", MetaDataKeyProvider { "" }.apiKey())
        assertNull("纯空白 ⇒ 无 Key", MetaDataKeyProvider { "   " }.apiKey())
        assertFalse(MetaDataKeyProvider { null }.hasKey())
        assertFalse(MetaDataKeyProvider { "  " }.hasKey())
    }

    @Test
    fun hasKeyTrueOnlyForNonBlankKey() {
        assertTrue(MetaDataKeyProvider { "k" }.hasKey())
        assertEquals("k", MetaDataKeyProvider { "k" }.apiKey())
    }

    // ---------------------------------------------------------------- 注入链路静态检查

    @Test
    fun manifestMetaDataIsBuildConfigPlaceholderOnly() {
        val manifest = workspaceFile("app/src/main/AndroidManifest.xml")
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(manifest)
        val nodes = doc.getElementsByTagName("meta-data")
        var value: String? = null
        for (i in 0 until nodes.length) {
            val element = nodes.item(i)
            val name = androidAttr(element, "name")
            if (name == KeyProvider.META_DATA_API_KEY) {
                value = androidAttr(element, "value")
            }
        }
        assertEquals("meta-data 名必须与 KeyProvider 常量一致", "com.amap.api.v2.apikey", KeyProvider.META_DATA_API_KEY)
        assertEquals(
            "manifestPlaceholders 占位符（不得硬编码真实 Key）",
            "\${AMAP_API_KEY}",
            value,
        )
    }

    @Test
    fun gradleInjectsKeyFromBuildConfigurationOnly() {
        val gradle = workspaceFile("app/build.gradle.kts").readText()
        assertTrue(
            "manifestPlaceholders 注入点",
            gradle.contains("manifestPlaceholders[\"AMAP_API_KEY\"]"),
        )
        assertTrue("local.properties 读取点", gradle.contains("getProperty(\"AMAP_API_KEY\")"))
        assertTrue("环境变量回退点", gradle.contains("System.getenv(\"AMAP_API_KEY\")"))
    }

    @Test
    fun localPropertiesTemplateLeavesKeyEmpty() {
        // 模板必须留空（真实 Key 只进本机 local.properties，不入库）
        val example = workspaceFile("local.properties.example").readText()
        assertTrue(example.contains("AMAP_API_KEY="))
        for (line in example.lines()) {
            if (line.startsWith("AMAP_API_KEY=")) {
                assertEquals("模板中 AMAP_API_KEY 必须留空", "", line.removePrefix("AMAP_API_KEY=").trim())
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    /** 读取 manifest 属性（含 `android:` 前缀与无前缀两种形态；DOM 默认命名空间不感知）。 */
    private fun androidAttr(element: org.w3c.dom.Node, local: String): String? {
        val attrs = element.attributes ?: return null
        for (i in 0 until attrs.length) {
            val a = attrs.item(i)
            if (a.nodeName == "android:$local" || a.nodeName == local || a.localName == local) {
                return a.nodeValue
            }
        }
        return null
    }

    /** 从单测工作目录向上查找工作区文件（工作目录不固定，稳妥解析）。 */
    private fun workspaceFile(relative: String): File {
        var dir: File = File(System.getProperty("user.dir") ?: ".").absoluteFile
        for (step in 0 until 6) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            dir = dir.parentFile ?: break
        }
        error("找不到 $relative（user.dir=${System.getProperty("user.dir")}）")
    }
}
