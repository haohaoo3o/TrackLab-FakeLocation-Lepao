package io.github.haohaoo3o.tracklab.core.model

/**
 * 轨迹样本数据模型（docs/CONTRACTS.md；motion 管线的输出类型）。
 *
 * - 坐标口径 **WGS-84**（内部/导出/Mock；地图显示另经 CoordTransform 转 GCJ-02）。
 * - [elapsedMs] 为相对起点的累计时间（Δt=0.5s 的整数倍，MotionContracts.DELTA_T_SEC）。
 * - 恒等式契约：|paceSecPerKm − 1000/speedMps| < 1e-9（pace 单位 s/km、v 单位 m/s）。
 * - **不含步幅字段**（步幅为派生量 S = 60·v/c）。
 * - 导出器（GpxExporter/GeoJsonExporter）按传入顺序保序输出。
 */
data class TrackSample(
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    val elapsedMs: Long,
    val speedMps: Double,
    val paceSecPerKm: Double,
    val cadenceSpm: Double,
)
