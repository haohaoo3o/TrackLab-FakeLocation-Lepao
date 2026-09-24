package io.github.haohaoo3o.tracklab.ui.playback

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
 * **输出一：应用内地图回放**（PlaybackRenderer + PlaybackBus）。
 *
 * 在高德地图上渲染测试轨迹回放：全程路线 Polyline（浅色）+ 已行轨迹（深色）+ 当前位置标记。
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
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
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

    /** WGS-84 → 高德 GCJ-02。*/
    private fun toGcj(p: LatLon): LatLng {
        val g = CoordTransform.wgs84ToGcj02(p)
        return LatLng(g.latitudeDeg, g.longitudeDeg)
    }

    companion object {

        /** 全程路线颜色/宽度/z。 */
        private const val COLOR_ROUTE = 0x661565C0.toInt()
        private const val WIDTH_ROUTE = 10f
        private const val Z_ROUTE = 1f

        /** 已行轨迹颜色/宽度/z。 */
        private const val COLOR_TRAVELED = 0xFF2E7D32.toInt()
        private const val WIDTH_TRAVELED = 12f
        private const val Z_TRAVELED = 2f

        /** 跟随缩放。 */
        private const val ZOOM_STREET = 17f
    }
}
