package io.github.haohaoo3o.tracklab.device

import android.annotation.SuppressLint
import android.content.Context
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import io.github.haohaoo3o.tracklab.core.model.TrackSample

/**
 * [TestLocationSink] 的 Android 侧实现（docs/CONTRACTS.md §6）：**官方 LocationManager 测试提供者
 * API**（addTestProvider / setTestProviderLocation / setTestProviderEnabled / removeTestProvider）。
 *
 * - 仅当用户在『开发者选项 → 选择模拟位置信息应用』中选中本应用时这些 API 才放行；
 *   否则抛 SecurityException——原样上抛给 [TestLocationOutput] 转显式引导态（`mock_location_guide`）。
 * - 坐标口径 WGS-84（内部/导出/Mock = WGS-84）。
 * - **不隐藏 mock 标志**、不改写任何系统状态、不影响其他应用。
 */
class LocationManagerTestLocationSink(context: Context) : TestLocationSink {

    private val locationManager =
        context.applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    // WrongConstant 抑制记账：addTestProvider 的 power/accuracy 参数在
    // API 31+ 的 SDK stub 上改挂 ProviderProperties int-def（lint 按新 def 校验），而本历史重载
    // 的原始契约为 Criteria int-def；两套 def 的枚举集合为 {低/中/高} 与 {粗/细}。该参数仅是
    // 测试提供者元数据（getProviderProperties 展示），不影响 setTestProviderLocation 的模拟位置
    // 输出。ProviderProperties 类为 API 31+，为 minSdk 26 兼容沿用 Criteria 常量并行级抑制。
    @SuppressLint("WrongConstant")
    override fun add(name: String) {
        locationManager.addTestProvider(
            name,
            /* requiresNetwork = */ false,
            /* requiresSatellite = */ false,
            /* requiresCell = */ false,
            /* hasMonetaryCost = */ false,
            /* supportsAltitude = */ true,
            /* supportsSpeed = */ true,
            /* supportsBearing = */ true,
            Criteria.POWER_LOW,
            Criteria.ACCURACY_FINE,
        )
    }

    override fun set(name: String, sample: TrackSample) {
        val location = Location(name).apply {
            latitude = sample.latitudeDeg
            longitude = sample.longitudeDeg
            speed = sample.speedMps.toFloat()
            accuracy = MOCK_ACCURACY_M
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }
        locationManager.setTestProviderLocation(name, location)
    }

    override fun setEnabled(name: String, enabled: Boolean) {
        locationManager.setTestProviderEnabled(name, enabled)
    }

    override fun remove(name: String) {
        locationManager.removeTestProvider(name)
    }

    companion object {

        /** 模拟位置精度（米；测试提供者输出恒带精度字段，展示口径）。 */
        private const val MOCK_ACCURACY_M = 3f

        /** 官方测试提供者名（= LocationManager.GPS_PROVIDER"gps"：走系统定位管线的标准出口）。 */
        fun defaultProviderName(): String = LocationManager.GPS_PROVIDER
    }
}
