package io.github.haohaoo3o.tracklab.export

import io.github.haohaoo3o.tracklab.core.model.TrackSample
import io.github.haohaoo3o.tracklab.data.export.GpxExporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * 断言契约（导出保序口径）：合法解析 + 必填字段 +『输出点序 = 传入
 * List<TrackSample> 顺序』。禁止引用尚不存在的 TrajectoryGenerator；『导出点序与
 * TrajectoryGenerator.generate 输出一致』的集成断言见 TrajectoryGeneratorTest。
 * 输入样本手造（刻意非单调，证明保序而非排序）。
 */
class GpxExporterTest {

    @Test
    fun exportParsesAndContainsRequiredFieldsInInputOrder() {
        val samples = handMadeSamples() // 坐标刻意非单调
        val xml = GpxExporter.export(samples, startEpochMs = 0L)

        // 合法解析（XML well-formed）
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(xml.byteInputStream())
        val root = doc.documentElement
        assertEquals("gpx", root.tagName)
        assertEquals("1.1", root.getAttribute("version"))
        assertTrue("creator 必填", root.getAttribute("creator").isNotEmpty())

        // 必填结构：trk / trkseg
        assertTrue("trk 必填", root.getElementsByTagName("trk").length == 1)
        assertTrue("trkseg 必填", root.getElementsByTagName("trkseg").length == 1)

        // 必填字段 + 输出点序 = 传入顺序
        val trkpts = root.getElementsByTagName("trkpt")
        assertEquals(samples.size, trkpts.length)
        for (i in 0 until samples.size) {
            val pt = trkpts.item(i) as Element
            assertEquals("trkpt[$i] lat 保序", samples[i].latitudeDeg, pt.getAttribute("lat").toDouble(), 0.0)
            assertEquals("trkpt[$i] lon 保序", samples[i].longitudeDeg, pt.getAttribute("lon").toDouble(), 0.0)
            val times = pt.getElementsByTagName("time")
            assertEquals("trkpt[$i] time 必填", 1, times.length)
            assertTrue("trkpt[$i] time 非空", times.item(0).textContent.isNotEmpty())
        }
    }

    @Test
    fun emptyInputRejected() {
        val e = try {
            GpxExporter.export(emptyList())
            fail("期望 IllegalArgumentException（可读中文错误）")
            throw AssertionError("unreachable")
        } catch (ex: IllegalArgumentException) {
            ex
        }
        assertTrue("应提示样本为空：" + e.message, e.message!!.contains("导出样本为空"))
    }

    /** 手造 3 个样本（lat/lon 非单调 ⇒ 顺序断言能区分『保序』与『排序』）。 */
    private fun handMadeSamples(): List<TrackSample> = listOf(
        TrackSample(30.00000, 110.00000, 0L, 3.0, 333.3333333333333, 170.0), // 合成境内测试坐标，不对应个人位置
        TrackSample(30.00060, 109.99930, 500L, 2.5, 400.0, 165.0),
        TrackSample(29.99940, 110.00080, 1000L, 2.7, 370.3703703703704, 168.0)
    )
}
