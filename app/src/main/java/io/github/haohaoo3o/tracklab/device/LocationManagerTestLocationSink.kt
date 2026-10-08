package io.github.haohaoo3o.tracklab.device

import android.annotation.SuppressLint
import android.content.Context
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import io.github.haohaoo3o.tracklab.core.model.TrackSample

/**
 * [TestLocationSink] 的 Android 侧实现（docs/CONTRACTS.md §6）：**官方 LocationManager 测试提供者
 * API**（addTestProvider / setTestProviderLocation / setTestProviderEnabled / removeTestProvider）。
 *
 * - 仅当用户在『开发者选项 → 选择模拟位置信息应用』中选中本应用时这些 API 才放行；
 *   否则抛 SecurityException——原样上抛给 [TestLocationOutput] 转显式引导态（`mock_location_guide`）。
 * - 坐标口径 WGS-84（内部/导出/Mock = WGS-84，§1）。
 * - **不隐藏 mock 标志**、不改写任何系统状态、不影响其他应用。
 * - **会话期覆盖面**：出口按 [physicalProviderNamesFor] 同时覆盖**主提供者（gps）与 network**——
 *   部分应用从 network 侧读取位置（last-known 与注册），若只写 gps，这些消费方在会话内仍会读到
 *   真实的 network 值。测试提供者注册即接管同名真实提供者，该提供者的全部消费方在会话内收到
 *   mock 帧（官方机制固有语义），remove 后真实提供者恢复。
 * - **全有或全无**（fail-closed）：任一提供者的注册失败按原异常上抛（出口转显式引导态），
 *   绝不静默降级为部分覆盖；add 部分失败时回收已注册项（不留无出口的测试提供者）。
 *   清理（remove）对单个提供者的非 SecurityException 容错继续（未注册/ROM 差异），
 *   确保**另一个提供者不被残留**；SecurityException 仍原样上抛。
 *
 * **输出字段口径**（拟真，见 [LocationFrameSynthesizer]）：测试提供者只接收显式写入的字段，
 * 不写即缺省 0。因此每帧的全部物理量统一取自会话级合成器 [LocationFrameSynthesizer.frameFor]
 * （wall/elapsedRealtime/sample 三钟同锚、位移–speed–bearing–500ms 一致、精度/高程为低频
 * 相关过程）；**每样本恰好取帧一次**（合成器按样本记忆），扇出的各物理提供者写入**同一帧**：
 * - `time` / `elapsedRealtimeNanos`：会话锚 + 样本时间（非逐帧独立取系统钟）；
 * - `speed`：位移/Δt；`bearing`：位移方向（位移过小保持上一帧）；
 * - `altitude`：操场尺度慢漂 + `verticalAccuracy`；
 * - `accuracy` / `speedAccuracy` / `bearingAccuracy`：低频相关过程（API 26+ 起 Location 提供
 *   setter，minSdk 26 恒可用）。
 * 均为正常 GNSS 应有的物理量；不构造卫星/星历类字段，不触碰 mock 标志。
 *
 * 本类**无帧间状态**：会话锚与拟真过程由 [LocationFrameSynthesizer] 持有
 * （PlaybackForegroundService 在会话开始/结束时 begin/end），remove 不再复位拟真状态。
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
        val added = ArrayList<String>(2)
        try {
            for (provider in physicalProviderNamesFor(name)) {
                addTestProvider(provider)
                added.add(provider)
            }
        } catch (t: Throwable) {
            // 部分失败回收（逆序）：不留「已注册但出口已弃」的测试提供者，再原样上抛。
            for (provider in added.asReversed()) {
                try {
                    locationManager.removeTestProvider(provider)
                } catch (ignored: Throwable) {
                }
            }
            throw t
        }
    }

    override fun set(name: String, sample: TrackSample) {
        // 物理字段统一来自会话级合成器：每样本恰好取帧一次——扇出的各物理提供者写同一帧
        // （合成器按样本记忆，重复查询不分叉）。
        val frame = LocationFrameSynthesizer.frameFor(sample)
        for (provider in physicalProviderNamesFor(name)) {
            val location = Location(provider).apply {
                latitude = frame.latitudeDeg
                longitude = frame.longitudeDeg
                altitude = frame.altitudeM
                speed = frame.speedMps.toFloat()
                bearing = frame.bearingDeg.toFloat()
                accuracy = frame.accuracyM.toFloat()
                time = frame.wallClockMs
                elapsedRealtimeNanos = frame.elapsedRealtimeNanos
            }
            // Location API 26+（minSdk=26，恒可用）：显式声明垂直/速度/方位精度，
            // 避免消费端读到 has*Accuracy=false。
            location.setVerticalAccuracyMeters(frame.verticalAccuracyM.toFloat())
            location.setSpeedAccuracyMetersPerSecond(frame.speedAccuracyMps.toFloat())
            location.setBearingAccuracyDegrees(frame.bearingAccuracyDeg.toFloat())
            locationManager.setTestProviderLocation(provider, location)
        }
    }

    override fun setEnabled(name: String, enabled: Boolean) {
        for (provider in physicalProviderNamesFor(name)) {
            locationManager.setTestProviderEnabled(provider, enabled)
        }
    }

    override fun remove(name: String) {
        var firstSecurity: SecurityException? = null
        for (provider in physicalProviderNamesFor(name)) {
            try {
                locationManager.removeTestProvider(provider)
            } catch (e: SecurityException) {
                // 权限被收回：记首个 SecurityException，仍继续清理其余提供者（清理完整性优先）。
                if (firstSecurity == null) firstSecurity = e
            } catch (ignored: Throwable) {
                // 清理容错：未注册（如 add 部分失败后的 stop）等非权限异常不得阻断其余提供者清理。
            }
        }
        firstSecurity?.let { throw it }
    }

    /** 单提供者注册（元数据与主提供者一致——仅 getProviderProperties 展示用，不影响模拟位置输出）。 */
    @SuppressLint("WrongConstant")
    private fun addTestProvider(provider: String) {
        locationManager.addTestProvider(
            provider,
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

    companion object {

        /** 官方测试提供者主名（= LocationManager.GPS_PROVIDER"gps"：走系统定位管线的标准出口）。 */
        fun defaultProviderName(): String = LocationManager.GPS_PROVIDER

        /**
         * 逻辑出口名 → 物理测试提供者名列表（去重、注册/写入顺序稳定）。
         *
         * 主提供者（[defaultProviderName]，"gps"）之外**恒附带 network**：部分应用从 network 侧
         * 读取位置（last-known 与注册），仅覆盖 gps 时其 network 侧收不到 mock 帧。逻辑名本身
         * 就是 network 时去重（不重复注册同一提供者）。全部仍走官方测试提供者 API，无隐藏通道
         * （§6，不触碰 mock 标志）。
         */
        fun physicalProviderNamesFor(logicalName: String): List<String> =
            if (logicalName == LocationManager.NETWORK_PROVIDER) listOf(logicalName)
            else listOf(logicalName, LocationManager.NETWORK_PROVIDER)
    }
}
