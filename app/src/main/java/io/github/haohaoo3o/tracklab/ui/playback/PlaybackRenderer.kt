package io.github.haohaoo3o.tracklab.ui.playback

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.Polyline
import com.amap.api.maps.model.PolylineOptions
import io.github.haohaoo3o.tracklab.core.geo.CoordTransform
import io.github.haohaoo3o.tracklab.core.geo.LatLon

/**
 * **输出一：应用内地图回放**（工程契约 F10，PlaybackRenderer + PlaybackBus）。
 *
 * 在高德地图上渲染测试轨迹回放：计划路线 Polyline（同色相 40% 虚影）+ 已行轨迹（深雾蓝实线）+
 * 当前位置标记（自绘"深盘 + 浅芯"圆标）。底图 = 高德默认白天样式 + 40% 黑压暗层
 * （2026-10-10 复审收口；叠线一律用压暗后仍 ≥3:1 的深色档，理由见下）。
 * 坐标口径：入参 WGS-84 → 显示前经 [CoordTransform.wgs84ToGcj02] 转 GCJ-02。
 * UI 侧经 `repeatOnLifecycle(STARTED)` 收集 PlaybackBus 后调用本类；实例生命周期与 MapView 一致。
 */
class PlaybackRenderer(private val amap: AMap) {

    private var routeLine: Polyline? = null
    private var traveledLine: Polyline? = null
    private val traveledPoints: MutableList<LatLng> = mutableListOf()
    private var positionMarker: Marker? = null

    /** 展示全程测试轨迹（开始回放时一次绘制；空表 = 清除）。 */
    fun showRoute(pointsWgs84: List<LatLon>) {
        clear()
        if (pointsWgs84.isEmpty()) return
        val options = PolylineOptions().color(COLOR_ROUTE).width(WIDTH_ROUTE).zIndex(Z_ROUTE)
        pointsWgs84.forEach { options.add(toGcj(it)) }
        routeLine = amap.addPolyline(options)
    }

    /** 更新当前位置（每帧）：移动标记并延伸已行轨迹（AMap Polyline 经 setPoints 保序更新）。 */
    fun updatePosition(pointWgs84: LatLon) {
        val gcj = toGcj(pointWgs84)
        val marker = positionMarker
        if (marker == null) {
            positionMarker = amap.addMarker(
                MarkerOptions()
                    .position(gcj)
                    // AMap 默认标记（HUE_AZURE，高饱和亮蓝）在压暗后的白天底上是唯一高饱和色块；
                    // 改用自绘低饱和标记（深雾蓝盘 + 浅芯），见 positionIcon()。
                    .icon(BitmapDescriptorFactory.fromBitmap(positionIcon()))
                    .anchor(0.5f, 0.5f)
                    .zIndex(5f),
            )
            traveledPoints.clear()
            traveledPoints.add(gcj)
            val options = PolylineOptions().color(COLOR_TRAVELED).width(WIDTH_TRAVELED).zIndex(Z_TRAVELED)
            options.add(gcj)
            traveledLine = amap.addPolyline(options)
        } else {
            marker.position = gcj
            traveledPoints.add(gcj)
            traveledLine?.setPoints(traveledPoints.toList())
        }
    }

    /**
     * 当前位置标记（自绘，低饱和）：外盘 = [COLOR_TRAVELED] `#4E6E88`，内芯 = `matrix_text_primary` `#E3E9E6`。
     * 实算（实见值 = 原值 ×0.6，40% 压暗层）：盘 `#2F4252` 对压暗后底图亮部 3.30:1 ✔、
     * 芯 `#888C8A` 对盘 **5.9:1** ✔（非文本 3:1 门槛）。
     */
    private fun positionIcon(): Bitmap {
        val bmp = Bitmap.createBitmap(ICON_SIZE_PX, ICON_SIZE_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cx = ICON_SIZE_PX / 2f
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_TRAVELED
            style = Paint.Style.FILL
        }
        val core = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_POSITION_CORE
            style = Paint.Style.FILL
        }
        canvas.drawCircle(cx, cx, ICON_SIZE_PX / 2f - ICON_STROKE_PX, ring)
        canvas.drawCircle(cx, cx, ICON_SIZE_PX / 4f, core)
        return bmp
    }

    /** 清除回放渲染（停止/完成/重开）。 */
    fun clear() {
        routeLine?.remove()
        routeLine = null
        traveledLine?.remove()
        traveledLine = null
        traveledPoints.clear()
        positionMarker?.remove()
        positionMarker = null
    }

    /** 相机跟随当前位置（街区级）。 */
    fun followPosition(pointWgs84: LatLon) {
        amap.animateCamera(CameraUpdateFactory.newLatLngZoom(toGcj(pointWgs84), ZOOM_STREET))
    }

    /** WGS-84 → 高德 GCJ-02。 */
    private fun toGcj(p: LatLon): LatLng {
        val g = CoordTransform.wgs84ToGcj02(p)
        return LatLng(g.latitudeDeg, g.longitudeDeg)
    }

    companion object {

        /**
         * 全程路线（计划路径）颜色/宽度/z：`matrix_playback_route` `#4E6E88` 的 40% alpha 虚影
         * （= DESIGN  的 `matrix_playback_route_ghost` `#664E6E88`，与实迹同色相、靠 alpha 区分）。
         * 2026-10-10 复审收口（第三轮）：底图回白天样式 + 40% 黑压暗层，虚影实见 ≈`#474F55`
         * 对压暗后底图亮部 **2.65~2.77:1**——**刻意低于 3:1**：它是"计划路径"底层提示，
         * 必须可辨但不与实迹抢眼（DESIGN 语义即"虚影"）；实迹实线 3.30:1 压在其上。
         */
        private const val COLOR_ROUTE = 0x664E6E88.toInt()
        private const val WIDTH_ROUTE = 10f
        private const val Z_ROUTE = 1f

        /**
         * 已行轨迹颜色/宽度/z：`matrix_playback_route` `#4E6E88`（实线、最粗、z 最高）。
         * 2026-10-10 复审收口：实见 = ×0.6 = `#2F4252`，对压暗后路线邻域背景中位 `#989898`
         * **3.60:1** ✔（25% 分位 2.75:1）；原始值对纯白 5.37:1 / 对 `#0A0D0C` 3.63:1（DESIGN  白天档）。
         * 对照夜间档浅青 `#86AE9A` 压暗后对同一背景仅 2.09:1（不可辨），故本轮换成深色档。
         */
        private const val COLOR_TRAVELED = 0xFF4E6E88.toInt()
        private const val WIDTH_TRAVELED = 12f
        private const val Z_TRAVELED = 2f

        /**
         * 当前位置标记：尺寸（px）/ 外环描边内缩（px）/ 内芯色。
         * 2026-10-10 复审收口：外环实心圆 = [COLOR_TRAVELED]（实见 `#2F4252`，对压暗后底图亮部
         * 3.30:1 ✔）；内芯由"近黑"改为 `matrix_text_primary` `#E3E9E6`（实见 `#888C8A`）——
         * 压暗会把内部对比度一起压扁，深环+近黑芯实见只剩 1.93:1（环与芯不可分）；
         * 浅芯对深环实见 **5.9:1** ✔，整体成为"深盘 + 浅芯"靶心，在明暗底上都成立。
         */
        private const val ICON_SIZE_PX = 72
        private const val ICON_STROKE_PX = 4f
        private const val COLOR_POSITION_CORE = 0xFFE3E9E6.toInt()

        /** 跟随缩放。 */
        private const val ZOOM_STREET = 17f
    }
}
