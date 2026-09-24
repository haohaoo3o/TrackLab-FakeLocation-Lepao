package io.github.haohaoo3o.tracklab.data.export

import io.github.haohaoo3o.tracklab.core.model.TrackSample
import java.time.Instant

/**
 * GPX 1.1 导出器（docs/CONTRACTS.md §4，导出保序口径）。
 *
 * - 输入 `List<TrackSample>`（WGS-84），**保序**输出为单一 trk/trkseg 的 trkpt 序列；
 * - 必填字段：`gpx@version=1.1`、`gpx@creator`、`trk`、`trkseg`、`trkpt@lat`、`trkpt@lon`、`trkpt/time`；
 * - `<time>` = ISO-8601 UTC（Instant(startEpochMs + elapsedMs)）；
 * - 单元测试只断言『合法解析 + 必填字段 + 输出点序 = 传入顺序』（禁止引用 TrajectoryGenerator）；
 *   『导出点序与 TrajectoryGenerator.generate 输出一致』的集成断言见 TrajectoryGeneratorTest。
 */
object GpxExporter {

    /** GPX 版本（GPX 1.1 schema）。 */
    const val GPX_VERSION = "1.1"

    /** creator 标识（含“测试”字样口径与合规常驻文案一致，产物可辨识为测试/模拟数据）。 */
    const val GPX_CREATOR = "TrackLab-测试/模拟定位"

    /**
     * 导出 GPX 文本。[startEpochMs] 为轨迹起点绝对时刻（缺省 0 = 1970-01-01T00:00:00Z）。
     *
     * @throws IllegalArgumentException 传入空列表（退化输入可读错误）
     */
    fun export(samples: List<TrackSample>, startEpochMs: Long = 0L): String {
        require(samples.isNotEmpty()) { "导出样本为空：至少需要 1 个 TrackSample 才能导出 GPX" }
        val sb = StringBuilder(samples.size * 96 + 256)
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"").append(GPX_VERSION)
            .append("\" creator=\"").append(GPX_CREATOR)
            .append("\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        sb.append("  <trk>\n")
        sb.append("    <name>").append(GPX_CREATOR).append("</name>\n")
        sb.append("    <trkseg>\n")
        for (s in samples) {
            val time = Instant.ofEpochMilli(startEpochMs + s.elapsedMs).toString()
            sb.append("      <trkpt lat=\"").append(s.latitudeDeg)
                .append("\" lon=\"").append(s.longitudeDeg).append("\">")
            sb.append("<time>").append(time).append("</time>")
            sb.append("</trkpt>\n")
        }
        sb.append("    </trkseg>\n")
        sb.append("  </trk>\n")
        sb.append("</gpx>\n")
        return sb.toString()
    }
}
