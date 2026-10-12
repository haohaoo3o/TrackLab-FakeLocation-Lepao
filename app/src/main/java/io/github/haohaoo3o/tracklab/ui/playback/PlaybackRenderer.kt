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
     * 当前位置标记（单色化 2026-10-12）：外盘 = [COLOR_TRAVELED] `#1A1A1A` 近黑，
     * 内芯 = `#FFFFFF` 纯白。"深盘 + 浅芯"靶心，在明暗底上都成立。
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
         * 全程路线（计划路径）颜色/宽度/z：`#1A1A1A` 的 40% alpha 虚影
         * （与实迹同色相、靠 alpha 区分；单色化 2026-10-12）。
         * 虚影刻意低于实线：它是"计划路径"底层提示，必须可辨但不与实迹抢眼。
         */
        private const val COLOR_ROUTE = 0x661A1A1A.toInt()
        private const val WIDTH_ROUTE = 10f
        private const val Z_ROUTE = 1f

        /**
         * 已行轨迹颜色/宽度/z：`#1A1A1A` 近黑（实线、最粗、z 最高；单色化 2026-10-12）。
         */
        private const val COLOR_TRAVELED = 0xFF1A1A1A.toInt()
        private const val WIDTH_TRAVELED = 12f
        private const val Z_TRAVELED = 2f

        /**
         * 当前位置标记：尺寸（px）/ 外环描边内缩（px）/ 内芯色（单色化 2026-10-12：`#FFFFFF` 纯白）。
         */
        private const val ICON_SIZE_PX = 72
        private const val ICON_STROKE_PX = 4f
        private const val COLOR_POSITION_CORE = 0xFFFFFFFF.toInt()

        /** 跟随缩放。 */
        private const val ZOOM_STREET = 17f
    }
}
