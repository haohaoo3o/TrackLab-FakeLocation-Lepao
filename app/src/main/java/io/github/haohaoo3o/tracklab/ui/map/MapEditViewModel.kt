package io.github.haohaoo3o.tracklab.ui.map

import androidx.lifecycle.ViewModel
import io.github.haohaoo3o.tracklab.core.geo.LatLon
import io.github.haohaoo3o.tracklab.core.geo.MetricResampler
import io.github.haohaoo3o.tracklab.core.geo.TrackFitter
import io.github.haohaoo3o.tracklab.core.geo.TrackModel
import io.github.haohaoo3o.tracklab.core.model.TrackSample
import io.github.haohaoo3o.tracklab.core.motion.BoundaryGuard
import io.github.haohaoo3o.tracklab.core.motion.MotionContracts
import io.github.haohaoo3o.tracklab.core.motion.TrajectoryGenerator
import io.github.haohaoo3o.tracklab.data.export.GeoJsonExporter
import io.github.haohaoo3o.tracklab.data.export.GpxExporter

/**
 * 地图编辑 ViewModel（docs/CONTRACTS.md；纯 Kotlin 逻辑，无 Android 依赖，
 * `app/src/test` 直接可执行，无 Mockito/Robolectric）。
 *
 * 职责：
 * - 六点按固定顺序采集（p0 顶部、p1 左上切点、p2 左下切点、p3 底部、p4 右下切点、p5 右上切点）
 *   与撤销/重置；
 * - 输入校验（**无 interval 项**——Δt 恒 0.5s 不可配置）：
 *   p_base（整数 180–660 s/km）/ laps（整数 1–100）/ seed（long 文本）；
 * - 配速与步频范围（模型固定界，[paceRangeSecPerKm] / [cadenceRangeSpm]，与 MotionContracts 同源）；
 * - 拟合预览（[MetricResampler] 中心线）与边界走廊（[BoundaryGuard.halfWidth]，±W 两侧边线）几何；
 * - [TrajectoryGenerator] 生成 + GPX/GeoJSON 导出文本（文件落盘归 Activity）；
 * - 状态保存（[snapshot]/[restoreSnapshot]，与服务快照同风格 `v1|…` 编码）——
 *   配置变更由 ViewModel 存活承载，进程重建由 `onSaveInstanceState` 字符串承载。
 *
 * 坐标口径：状态内一律 WGS-84（地图显示侧另转 GCJ-02）。
 */
class MapEditViewModel(
    private val generator: TrajectoryGenerator = TrajectoryGenerator(),
) : ViewModel() {

    /** 输入校验错误位（文案键 error_invalid_p_base / error_invalid_laps / error_invalid_seed）。*/
    enum class InputError { NONE, P_BASE, LAPS, SEED }

    /** 编辑状态（不可变快照；[points] 严格按固定顺序 p0..p5）。*/
    data class State(
        val points: List<LatLon>,
        val pBaseSecPerKm: Int,
        val laps: Int,
        val seed: Long,
    )

    /** 拟合预览结果。[Ready] 携带中心线与边界走廊（±W 两侧）的 WGS-84 折线。 */
    sealed class FitPreview {

        data class Ready(
            val model: TrackModel,
            val centerlineWgs84: List<LatLon>,
            val edgePlusWgs84: List<LatLon>,
            val edgeMinusWgs84: List<LatLon>,
        ) : FitPreview()

        /** 拟合被拒：[message] 为 TrackFitter 的中文可读原因（直接可展示）。 */
        data class Rejected(val message: String) : FitPreview()

        /** 点位不足 6 个。 */
        object Incomplete : FitPreview()
    }

    private var current = State(
        points = emptyList(),
        pBaseSecPerKm = DEFAULT_P_BASE_SEC_PER_KM,
        laps = DEFAULT_LAPS,
        seed = DEFAULT_SEED,
    )

    /** 当前状态只读视图。 */
    val state: State get() = current

    /** 配速范围（s/km，唯一夹紧区间 [180, 660]）＝『配速范围设置』的模型固定界。*/
    val paceRangeSecPerKm: ClosedFloatingPointRange<Double>
        get() = MotionContracts.PACE_MIN_S_PER_KM..MotionContracts.PACE_MAX_S_PER_KM

    /** 步频范围（spm，夹紧区间 [150, 200]）——模型固定界，展示用。*/
    val cadenceRangeSpm: ClosedFloatingPointRange<Double>
        get() = MotionContracts.CADENCE_MIN_SPM..MotionContracts.CADENCE_MAX_SPM

    /** 圈数合法域（整数 1–100）。*/
    val lapsRange: IntRange get() = MIN_LAPS..TrajectoryGenerator.MAX_LAPS

    // ---------------------------------------------------------------- 六点采集 / 撤销 / 重置

    /**
     * 按固定顺序追加下一个点位（p0→p5）。已满 6 个返回 false（状态不变）。
     * 入参须为 WGS-84（地图 GCJ-02 由调用方经 CoordTransform 转换）。
     */
    fun addPoint(point: LatLon): Boolean {
        if (current.points.size >= POINT_COUNT) return false
        current = current.copy(points = current.points + point)
        return true
    }

    /** 撤销最后一个点位；无可撤销返回 false。 */
    fun undoPoint(): Boolean {
        val pts = current.points
        if (pts.isEmpty()) return false
        current = current.copy(points = pts.dropLast(1))
        return true
    }

    /** 重置：清空全部点位（参数 p_base/laps/seed 保留）。 */
    fun resetPoints() {
        current = current.copy(points = emptyList())
    }

    // ---------------------------------------------------------------- 输入校验（可测）

    /**
     * 三项输入校验：p_base 整数 180–660 → laps 整数 1–100 → seed long 文本。
     * 返回**首个**错误（优先级 P_BASE > LAPS > SEED）；全部合法返回 [InputError.NONE]。
     */
    fun validateInputs(pBaseText: String, lapsText: String, seedText: String): InputError = when {
        parsePBase(pBaseText) == null -> InputError.P_BASE
        parseLaps(lapsText) == null -> InputError.LAPS
        parseSeed(seedText) == null -> InputError.SEED
        else -> InputError.NONE
    }

    /**
     * 校验并提交三项输入。全部合法才写入状态并返回 [InputError.NONE]；
     * 否则状态不变并返回首个错误。
     */
    fun applyInputs(pBaseText: String, lapsText: String, seedText: String): InputError {
        val error = validateInputs(pBaseText, lapsText, seedText)
        if (error != InputError.NONE) return error
        current = current.copy(
            pBaseSecPerKm = parsePBase(pBaseText)!!,
            laps = parseLaps(lapsText)!!,
            seed = parseSeed(seedText)!!,
        )
        return InputError.NONE
    }

    // ---------------------------------------------------------------- 拟合预览 / 边界

    /**
     * 对当前 6 点拟合并计算预览几何。点位不足 → [FitPreview.Incomplete]；
     * 退化输入 → [FitPreview.Rejected]（携带 TrackFitter 中文原因）。
     * 中心线 = [MetricResampler]（Δ=1.0m，仅预览/走廊显示，不进生成管线）；
     * 边界走廊 = 中心线 ± W·N(s)，W = [BoundaryGuard.halfWidth]。
     */
    fun tryFit(): FitPreview {
        val pts = current.points
        if (pts.size < POINT_COUNT) return FitPreview.Incomplete
        val model = try {
            TrackFitter.fit(pts)
        } catch (e: IllegalArgumentException) {
            return FitPreview.Rejected(e.message ?: "点位拟合失败（输入退化）")
        }
        val w = BoundaryGuard.halfWidth(model.r)
        val sampled = MetricResampler.resample(model)
        return FitPreview.Ready(
            model = model,
            centerlineWgs84 = sampled.map { model.toWgs84(it.position) },
            edgePlusWgs84 = sampled.map { model.toWgs84(it.position + model.normal(it.arcS) * w) },
            edgeMinusWgs84 = sampled.map { model.toWgs84(it.position + model.normal(it.arcS) * -w) },
        )
    }

    // ---------------------------------------------------------------- 生成 / 导出

    /**
     * 用当前 6 点 + 参数生成轨迹样本（WGS-84；回放与导出共用同一序列）。
     * 拟合未完成/被拒返回 null。
     */
    fun generateSamples(): List<TrackSample>? {
        val model = (tryFit() as? FitPreview.Ready)?.model ?: return null
        return generator.generate(model, current.laps, current.pBaseSecPerKm.toDouble(), current.seed)
    }

    /** GPX 导出文本（[GpxExporter]，导出保序口径）；无法生成返回 null。*/
    fun buildGpx(startEpochMs: Long = 0L): String? =
        generateSamples()?.let { GpxExporter.export(it, startEpochMs) }

    /** GeoJSON 导出文本（[GeoJsonExporter]，导出保序口径）；无法生成返回 null。*/
    fun buildGeoJson(): String? =
        generateSamples()?.let { GeoJsonExporter.export(it) }

    // ---------------------------------------------------------------- 状态保存（与服务快照同风格）

    /**
     * 状态快照编码：`"v1|<pBase>|<laps>|<seed>|<lat,lon;…>"`（`|`/`;`/`,` 分隔，
     * 坐标 Double.toString——与服务快照 `v1|…` 同风格）。
     */
    fun snapshot(): String {
        val pts = current.points.joinToString(";") { "${it.latitudeDeg},${it.longitudeDeg}" }
        return "$SNAPSHOT_VERSION|${current.pBaseSecPerKm}|${current.laps}|${current.seed}|$pts"
    }

    /**
     * 从 [snapshot] 编码恢复状态（进程重建后用）。
     *
     * @throws IllegalArgumentException 编码损坏或参数越界（中文可读错误；调用方兜底不崩溃）
     */
    fun restoreSnapshot(encoded: String) {
        val parts = encoded.split("|", limit = 5)
        require(parts.size == 5 && parts[0] == SNAPSHOT_VERSION) {
            "地图编辑状态快照格式错误（期望 $SNAPSHOT_VERSION|… 共 5 段）：$encoded"
        }
        val pBase = parsePBase(parts[1])
        requireNotNull(pBase) { "状态快照解析失败：p_base=${parts[1]}" }
        val laps = parseLaps(parts[2])
        requireNotNull(laps) { "状态快照解析失败：laps=${parts[2]}" }
        val seed = parseSeed(parts[3])
        requireNotNull(seed) { "状态快照解析失败：seed=${parts[3]}" }
        val points = if (parts[4].isEmpty()) {
            emptyList()
        } else {
            parts[4].split(";").map { token ->
                val xy = token.split(",")
                require(xy.size == 2) { "状态快照解析失败：点位 $token" }
                val lat = xy[0].toDoubleOrNull()
                requireNotNull(lat) { "状态快照解析失败：纬度 ${xy[0]}" }
                val lon = xy[1].toDoubleOrNull()
                requireNotNull(lon) { "状态快照解析失败：经度 ${xy[1]}" }
                LatLon(lat, lon)
            }
        }
        require(points.size <= POINT_COUNT) { "状态快照解析失败：点位数 ${points.size} > $POINT_COUNT" }
        current = State(points, pBase!!, laps!!, seed!!)
    }

    companion object {

        /** 六点数（固定顺序）。*/
        const val POINT_COUNT = 6

        /** 默认 p_base = [MotionContracts.PACE_DEFAULT_S_PER_KM]（330 s/km）。 */
        val DEFAULT_P_BASE_SEC_PER_KM: Int = MotionContracts.PACE_DEFAULT_S_PER_KM.toInt()

        /** 默认圈数。 */
        const val DEFAULT_LAPS = 3

        /** 默认随机 seed（钉死 42：LapPerturber 逐圈差异下界实测满足）。*/
        const val DEFAULT_SEED = 42L

        /** 圈数下限（laps 整数 1–100）。*/
        const val MIN_LAPS = 1

        /** 状态快照版本前缀（与服务快照同风格）。*/
        const val SNAPSHOT_VERSION = "v1"

        /** p_base 解析（整数 180–660 s/km）；非法返回 null。*/
        fun parsePBase(text: String): Int? {
            val v = text.trim().toIntOrNull() ?: return null
            val min = MotionContracts.PACE_MIN_S_PER_KM.toInt()
            val max = MotionContracts.PACE_MAX_S_PER_KM.toInt()
            return v.takeIf { it in min..max }
        }

        /** laps 解析（整数 1–100）；非法返回 null。*/
        fun parseLaps(text: String): Int? {
            val v = text.trim().toIntOrNull() ?: return null
            return v.takeIf { it in MIN_LAPS..TrajectoryGenerator.MAX_LAPS }
        }

        /** seed 解析（long 文本）；非法返回 null。*/
        fun parseSeed(text: String): Long? = text.trim().toLongOrNull()
    }
}
