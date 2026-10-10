package io.github.haohaoo3o.tracklab.ui.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.LatLngBounds
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.Polyline
import com.amap.api.maps.model.PolylineOptions
import io.github.haohaoo3o.tracklab.core.geo.CoordTransform
import io.github.haohaoo3o.tracklab.core.geo.LatLon

/**
 * 高德地图叠加层控制器（map 阶段）：编号 Marker（p0..p5，**可拖动微调**）、拟合预览 Polyline
 * （中心线）与边界走廊两侧折线（±W）。**纯 Android/AMap 渲染**，几何一律由 [MapEditViewModel] 算好后传入。
 *
 * 坐标口径（工程契约）：入参 WGS-84 → 显示前经 [CoordTransform.wgs84ToGcj02] 转 GCJ-02。
 * 实例生命周期与 MapView 一致：MapView 创建（且已过合规闸门）后构造，随其销毁丢弃。
 */
class MapEditController(private val amap: AMap) {

    private val markers = mutableListOf<Marker>()
    private val polylines = mutableListOf<Polyline>()

    /** Marker → 点位索引（p0..p5；拖动结束回调经 [markerIndex] 定位被拖点）。 */
    private val markerIndices = mutableListOf<Pair<Marker, Int>>()

    /**
     * 渲染点位编号 Marker（[titles] 与 [points] 等长，形如『p0 顶部』）。
     * 图标为白字编号圆点（p0..p5 按  固定顺序语义）；**Marker 可拖动**——预制跑道载入后
     * 用户拖动六点贴合实际跑道，拖动结束由调用方回写 ViewModel（[markerIndex] 取索引）。
     */
    fun renderPoints(points: List<LatLon>, titles: List<String>) {
        markers.forEach { it.remove() }
        markers.clear()
        markerIndices.clear()
        points.forEachIndexed { index, p ->
            val marker = amap.addMarker(
                MarkerOptions()
                    .position(toGcj(p))
                    .title(titles.getOrNull(index) ?: "p$index")
                    .icon(BitmapDescriptorFactory.fromBitmap(numberIcon(index)))
                    .anchor(0.5f, 0.5f)
                    .draggable(true)
                    .zIndex(3f)
            )
            if (marker != null) {
                markers.add(marker)
                markerIndices.add(marker to index)
            }
        }
    }

    /** [marker] 对应的点位索引（p0..p5）；非本控制器创建的 Marker 返回 null。 */
    fun markerIndex(marker: Marker): Int? = markerIndices.firstOrNull { it.first === marker }?.second

    /**
     * 渲染拟合预览：中心线 Polyline（[centerlineWgs84]）+ 边界走廊两侧折线
     * （[edgePlusWgs84]/[edgeMinusWgs84]， 走廊半宽 W）。传空表 = 清除预览。
     */
    fun renderPreview(
        centerlineWgs84: List<LatLon>,
        edgePlusWgs84: List<LatLon>,
        edgeMinusWgs84: List<LatLon>,
    ) {
        clearPolylines()
        if (centerlineWgs84.isEmpty()) return
        addPolyline(centerlineWgs84, COLOR_PREVIEW, WIDTH_PREVIEW, Z_PREVIEW)
        if (edgePlusWgs84.isNotEmpty()) {
            addPolyline(edgePlusWgs84, COLOR_BOUNDARY, WIDTH_BOUNDARY, Z_BOUNDARY)
        }
        if (edgeMinusWgs84.isNotEmpty()) {
            addPolyline(edgeMinusWgs84, COLOR_BOUNDARY, WIDTH_BOUNDARY, Z_BOUNDARY)
        }
    }

    /** 清除全部点位 Marker 与预览/边界折线（重置用）。 */
    fun clearOverlays() {
        markers.forEach { it.remove() }
        markers.clear()
        markerIndices.clear()
        clearPolylines()
    }

    /** 相机移到点位（首个点定位用）。 */
    fun focusOn(point: LatLon) {
        amap.animateCamera(CameraUpdateFactory.newLatLngZoom(toGcj(point), ZOOM_STREET))
    }

    /** 相机框住折线（拟合完成后展示全图）；点数 < 2 退化为 [focusOn]。 */
    fun fitCameraToBounds(points: List<LatLon>) {
        val first = points.firstOrNull() ?: return
        if (points.size < 2) {
            focusOn(first)
            return
        }
        val builder = LatLngBounds.builder()
        points.forEach { builder.include(toGcj(it)) }
        amap.animateCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), BOUNDS_PADDING_PX))
    }

    // ---------------------------------------------------------------- helpers

    private fun addPolyline(coords: List<LatLon>, color: Int, widthDp: Float, z: Float) {
        val options = PolylineOptions().color(color).width(widthDp).zIndex(z)
        coords.forEach { options.add(toGcj(it)) }
        val polyline = amap.addPolyline(options)
        if (polyline != null) polylines.add(polyline)
    }

    private fun clearPolylines() {
        polylines.forEach { it.remove() }
        polylines.clear()
    }

    /** WGS-84 → 高德 GCJ-02。 */
    private fun toGcj(p: LatLon): LatLng {
        val g = CoordTransform.wgs84ToGcj02(p)
        return LatLng(g.latitudeDeg, g.longitudeDeg)
    }

    /**
     * 编号圆点图标（**深字浅底** 0–5）。
     *
     * 2026-10-10 复审收口（第三轮）：底图回默认白天样式 + 布局 40% 黑压暗层后，本图标也一起被
     * 压暗（×0.6，落在 sRGB 编码空间）⇒ **图标内部对比度同样被压扁**。实测选型（实见值 = 原值 ×0.6，
     * 括号内为实见后的对比度）：
     *   · 填充 `#E3E9E6`（= `matrix_text_primary`，HSV S=0.026，近中性）→ 实见 `#888C8A`；
     *     数字/描边 `#0A0D0C`（近黑）→ 实见 `#060807`：数字 on 填充 **5.9:1** ✔
     *   · 对照（已否决）：`matrix_track_center` `#4A7A66` 填充 + 近黑数字，压暗后只剩 **2.03:1**
     *     （编号会糊）；夜间档的 `#8FA3B8` 填充压暗后对浅底仅 1.98:1，圆点会融进底图。
     * 圆点对底图的可辨性由 **4px 近黑描边**承担：实见 `#060807` 对压暗后浅底带（≈`#919191`）
     * **6.37:1** ✔（非文本 3:1 门槛）。
     */
    private fun numberIcon(index: Int): Bitmap {
        val size = ICON_SIZE_PX
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cx = size / 2f
        val radius = size / 2f - ICON_STROKE_PX
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_MARKER_FILL
            style = Paint.Style.FILL
        }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_MARKER_INK
            style = Paint.Style.STROKE
            strokeWidth = ICON_STROKE_PX
        }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_MARKER_INK
            textSize = size * 0.5f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        canvas.drawCircle(cx, cx, radius, fill)
        canvas.drawCircle(cx, cx, radius, stroke)
        val textY = cx - (text.descent() + text.ascent()) / 2f
        canvas.drawText(index.toString(), cx, textY, text)
        return bmp
    }

    companion object {

        /**
         * 拟合预览中心线颜色（DESIGN 「白天底图叠加线」的 `matrix_track_center` `#4A7A66`）/ 宽度（dp）/ z。
         * 2026-10-10 复审收口（第三轮）：底图回白天样式 + 40% 黑压暗层，叠加线**同样被压暗**
         * （实见 = 原值 ×0.6 = `#2C493D`）。真机复算（返工前白天底图按 alpha 0.4 合成后沿路线邻域
         * 采样，排除线本身，n=17236）：当地背景中位 `#989898`，实见线色对该背景 **3.42:1** ✔
         * （非文本 3:1 门槛；较暗 25% 分位 2.61:1）。对照夜间档浅青 `#86AE9A` 压暗后对同一背景
         * 只剩 **2.09:1**（不可辨）——这是本轮把叠线整体换成深色档的原因。
         * 原始值本身的判据（DESIGN ）：对纯白 4.92:1 / 对 `#0A0D0C` 3.97:1。
         */
        private const val COLOR_PREVIEW = 0xFF4A7A66.toInt()
        private const val WIDTH_PREVIEW = 10f
        private const val Z_PREVIEW = 2f

        /**
         * 边界走廊折线颜色（DESIGN  的 `matrix_track_corridor` `#7C6A45`）/ 宽度（dp）/ z。
         * 实见 = ×0.6 = `#4A4029`，对压暗后路线邻域背景中位 `#989898` **3.54:1** ✔（25% 分位 2.70:1）；
         * 原始值对纯白 5.24:1 / 对 `#0A0D0C` 3.72:1。
         */
        private const val COLOR_BOUNDARY = 0xFF7C6A45.toInt()
        private const val WIDTH_BOUNDARY = 6f
        private const val Z_BOUNDARY = 1f

        /** 编号 Marker 圆点：填充（`matrix_text_primary` `#E3E9E6` 近中性）/ 墨色（描边+数字）/ 尺寸（px）/ 描边（px）。 */
        private const val COLOR_MARKER_FILL = 0xFFE3E9E6.toInt()
        private const val COLOR_MARKER_INK = 0xFF0A0D0C.toInt()
        private const val ICON_SIZE_PX = 96
        private const val ICON_STROKE_PX = 4f

        /** 街区级缩放与取景内边距（px）。 */
        private const val ZOOM_STREET = 17f
        private const val BOUNDS_PADDING_PX = 120
    }
}
