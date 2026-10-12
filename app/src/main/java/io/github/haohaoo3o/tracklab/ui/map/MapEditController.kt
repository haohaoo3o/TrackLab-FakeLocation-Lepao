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
 * 高德地图叠加层控制器：编号 Marker（p0..p5，**可拖动微调**）、拟合预览 Polyline
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
     * 编号圆点图标（**深字浅底** 0–5；单色化 2026-10-12：填充 `#F2F2F2` / 数字描边 `#111111`，
     * 数字 on 填充 16.87:1；4px 描边承担对底图可辨性）。
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
         * 拟合预览中心线颜色（单色化 2026-10-12：`#2E2E2E` 深灰）/ 宽度（dp）/ z。
         * 宽度 10dp / z 2 不动。对压暗后邻域中位 `#989898` 4.71:1（非文本 3:1 门槛）。
         */
        private const val COLOR_PREVIEW = 0xFF2E2E2E.toInt()
        private const val WIDTH_PREVIEW = 10f
        private const val Z_PREVIEW = 2f

        /**
         * 边界走廊折线颜色（单色化 2026-10-12：`#8A8A8A` 中灰）/ 宽度（dp）/ z（ 走廊显示）。
         * 宽度 6dp / z 1 不动。对纯白 3.45:1（非文本 3:1 门槛）；另靠"与中心线同现 + 线宽差
         * （6dp vs 10dp）+ 双侧成对出现"冗余识别。
         */
        private const val COLOR_BOUNDARY = 0xFF8A8A8A.toInt()
        private const val WIDTH_BOUNDARY = 6f
        private const val Z_BOUNDARY = 1f

        /** 编号 Marker 圆点：填充 `#F2F2F2` / 墨色 `#111111`（描边+数字）/ 尺寸（px）/ 描边（px）。单色化 2026-10-12。 */
        private const val COLOR_MARKER_FILL = 0xFFF2F2F2.toInt()
        private const val COLOR_MARKER_INK = 0xFF111111.toInt()
        private const val ICON_SIZE_PX = 96
        private const val ICON_STROKE_PX = 4f

        /** 街区级缩放与取景内边距（px）。 */
        private const val ZOOM_STREET = 17f
        private const val BOUNDS_PADDING_PX = 120
    }
}
