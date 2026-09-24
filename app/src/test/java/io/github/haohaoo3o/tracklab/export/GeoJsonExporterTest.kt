package io.github.haohaoo3o.tracklab.export

import io.github.haohaoo3o.tracklab.core.model.TrackSample
import io.github.haohaoo3o.tracklab.data.export.GeoJsonExporter
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 断言契约（导出保序口径）：合法解析 + 必填字段 +『输出点序 = 传入
 * List<TrackSample> 顺序』（org.json:json:20240303 仅 testImplementation）。禁止引用
 * TrajectoryGenerator；一致性集成断言见 TrajectoryGeneratorTest。
 * GeoJSON 规范坐标序 [lon, lat]——逐点对位断言覆盖字段交换与保序。输入样本手造（刻意非单调）。
 */
class GeoJsonExporterTest {

    @Test
    fun exportParsesAndContainsRequiredFieldsInInputOrder() {
        val samples = handMadeSamples() // 坐标刻意非单调
        val json = JSONObject(GeoJsonExporter.export(samples))

        // 必填字段：FeatureCollection / Feature / LineString
        assertEquals(GeoJsonExporter.TYPE_FEATURE_COLLECTION, json.getString("type"))
        val features = json.getJSONArray("features")
        assertTrue("features 非空", features.length() >= 1)
        val feature = features.getJSONObject(0)
        assertEquals("Feature", feature.getString("type"))
        val geometry = feature.getJSONObject("geometry")
        assertEquals(GeoJsonExporter.TYPE_LINE_STRING, geometry.getString("type"))

        // 必填字段 + 输出点序 = 传入顺序（GeoJSON 坐标序 [lon, lat]）
        val coordinates: JSONArray = geometry.getJSONArray("coordinates")
        assertEquals(samples.size, coordinates.length())
        for (i in 0 until samples.size) {
            val c = coordinates.getJSONArray(i)
            assertEquals("coordinates[$i][0]=lon 保序", samples[i].longitudeDeg, c.getDouble(0), 0.0)
            assertEquals("coordinates[$i][1]=lat 保序", samples[i].latitudeDeg, c.getDouble(1), 0.0)
        }

        // 必填字段：properties.timesMs 逐点保序
        val properties = feature.getJSONObject("properties")
        assertEquals(samples.size, properties.getInt("sampleCount"))
        val times = properties.getJSONArray("timesMs")
        assertEquals(samples.size, times.length())
        for (i in 0 until samples.size) {
            assertEquals("timesMs[$i] 保序", samples[i].elapsedMs, times.getLong(i))
        }
    }

    @Test
    fun emptyInputRejected() {
        val e = try {
            GeoJsonExporter.export(emptyList())
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
