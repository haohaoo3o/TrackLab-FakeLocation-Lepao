package io.github.haohaoo3o.tracklab.data.export

import io.github.haohaoo3o.tracklab.core.model.TrackSample

/**
 * GeoJSON 导出器（docs/CONTRACTS.md §4，导出保序口径）。
 *
 * - 输入 `List<TrackSample>`（WGS-84），**保序**输出为 `FeatureCollection → Feature → LineString`；
 * - GeoJSON 规范坐标序为 **[lon, lat]**（与传入字段序相反，导出时交换——测试断言逐点对位）；
 * - 必填字段：`type=FeatureCollection`、`features[0].type=Feature`、`geometry.type=LineString`、
 *   `geometry.coordinates`（长度 = 样本数、逐点 [lon,lat] 保序）、`properties.timesMs`（逐点 elapsedMs 保序）；
 * - 手写序列化（不依赖 org.json——该依赖仅 testImplementation，§1）。
 */
object GeoJsonExporter {

    /** FeatureCollection 类型字面量。 */
    const val TYPE_FEATURE_COLLECTION = "FeatureCollection"

    /** Feature geometry 类型字面量。 */
    const val TYPE_LINE_STRING = "LineString"

    /** 轨迹名（含“测试”字样口径同 GPX）。 */
    const val TRACK_NAME = "TrackLab-测试/模拟定位"

    /**
     * 导出 GeoJSON 文本。
     *
     * @throws IllegalArgumentException 传入空列表（退化输入可读错误）
     */
    fun export(samples: List<TrackSample>): String {
        require(samples.isNotEmpty()) { "导出样本为空：至少需要 1 个 TrackSample 才能导出 GeoJSON" }
        val sb = StringBuilder(samples.size * 64 + 256)
        sb.append("{\n")
        sb.append("  \"type\": \"").append(TYPE_FEATURE_COLLECTION).append("\",\n")
        sb.append("  \"features\": [\n")
        sb.append("    {\n")
        sb.append("      \"type\": \"Feature\",\n")
        sb.append("      \"properties\": {\n")
        sb.append("        \"name\": \"").append(TRACK_NAME).append("\",\n")
        sb.append("        \"generator\": \"TrackLab\",\n")
        sb.append("        \"sampleCount\": ").append(samples.size).append(",\n")
        sb.append("        \"timesMs\": [")
        samples.forEachIndexed { i, s ->
            if (i > 0) sb.append(", ")
            sb.append(s.elapsedMs)
        }
        sb.append("]\n")
        sb.append("      },\n")
        sb.append("      \"geometry\": {\n")
        sb.append("        \"type\": \"").append(TYPE_LINE_STRING).append("\",\n")
        sb.append("        \"coordinates\": [")
        samples.forEachIndexed { i, s ->
            if (i > 0) sb.append(", ")
            // GeoJSON 规范：[lon, lat]
            sb.append("[").append(s.longitudeDeg).append(", ").append(s.latitudeDeg).append("]")
        }
        sb.append("]\n")
        sb.append("      }\n")
        sb.append("    }\n")
        sb.append("  ]\n")
        sb.append("}\n")
        return sb.toString()
    }
}
