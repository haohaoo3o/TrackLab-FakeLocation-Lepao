package io.github.haohaoo3o.tracklab.di

import android.content.Context
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.github.haohaoo3o.tracklab.core.motion.TrajectoryGenerator
import io.github.haohaoo3o.tracklab.data.ConnectivityScenarioRepository
import io.github.haohaoo3o.tracklab.device.LocationManagerTestLocationSink
import io.github.haohaoo3o.tracklab.device.TestLocationSink
import io.github.haohaoo3o.tracklab.service.PlaybackBus
import io.github.haohaoo3o.tracklab.service.PlaybackEntry
import io.github.haohaoo3o.tracklab.service.PrefsSnapshotStore
import io.github.haohaoo3o.tracklab.service.ServicePlaybackEntry
import io.github.haohaoo3o.tracklab.service.SnapshotStore
import io.github.haohaoo3o.tracklab.ui.PermissionCoordinator
import io.github.haohaoo3o.tracklab.ui.map.KeyProvider
import io.github.haohaoo3o.tracklab.ui.map.MapEditViewModel
import io.github.haohaoo3o.tracklab.ui.map.MetaDataKeyProvider
import io.github.haohaoo3o.tracklab.ui.privacy.ConsentStore
import io.github.haohaoo3o.tracklab.ui.privacy.MapsInitializerPrivacyGate
import io.github.haohaoo3o.tracklab.ui.privacy.PrivacyGateController
import io.github.haohaoo3o.tracklab.ui.privacy.SharedPreferencesConsentStore

/**
 * 组合根（手工 DI）。这是唯一允许接触具体实现的地方；排除项与蜂窝模拟仅限 DI 层
 *（docs/CONTRACTS.md §9）。
 *
 * 各组件挂接：
 * - 隐私同意存档：ConsentStore；
 * - 地图：KeyProvider（meta-data 注入链路）/ PrivacyGateController（三态）/
 *   MapEditViewModel 工厂（构造器注入）/ PlaybackEntry（测试回放入口，由前台服务实现替换）；
 *   MapEditController 需要 AMap 实例，由 MainActivity 在 MapView 创建后构造（不在本类持有）；
 * - 回放：PlaybackBus / PlaybackStateMachine / TestLocationSink 在此装配，
 *   并把 [playbackEntry] 替换为 PlaybackForegroundService 驱动的实现。
 */
class AppContainer(private val context: Context) {

    /** 隐私同意存档端口：SharedPreferences("tracklab_state") / 键 "consent_agreed"（CONTRACTS §5）。*/
    val consentStore: ConsentStore = SharedPreferencesConsentStore(context)

    /**
     * 高德 Key 端口：**只从构建配置注入的 meta-data 读取**
     * （local.properties|环境变量 → manifestPlaceholders → meta-data `com.amap.api.v2.apikey`），
     * 无任何其他来源；缺失/留空 → NEEDS_KEY 明确诊断，不崩溃。
     */
    val keyProvider: KeyProvider = MetaDataKeyProvider { key ->
        val appInfo = context.packageManager.getApplicationInfo(
            context.packageName,
            PackageManager.GET_META_DATA,
        )
        appInfo.metaData?.getString(key)
    }

    /** 隐私三态门：合规时序唯一触点 [PrivacyGateController.beforeMapViewCreated]。*/
    val privacyGateController: PrivacyGateController = PrivacyGateController(
        consentStore = consentStore,
        keyProvider = keyProvider,
        amapPrivacy = MapsInitializerPrivacyGate(context),
    )

    /** MapEditViewModel 构造器注入工厂（测试直接构造，无 Mockito/Robolectric）。*/
    val mapEditViewModelFactory: ViewModelProvider.Factory =
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(MapEditViewModel::class.java)) {
                    "未知 ViewModel 类型：$modelClass"
                }
                return MapEditViewModel(TrajectoryGenerator()) as T
            }
        }

    /** 测试回放入口（PlaybackForegroundService 驱动的实现，§7）。*/
    val playbackEntry: PlaybackEntry = ServicePlaybackEntry(context)

    /** 回放状态总线：服务发布，UI 经 repeatOnLifecycle(STARTED) 收集。*/
    val playbackBus: PlaybackBus = PlaybackBus()

    /** 回放快照端口：PrefsSnapshotStore 仅存在于 service 层，绝不进纯 JVM 测试。*/
    val snapshotStore: SnapshotStore = PrefsSnapshotStore(context)

    /**
     * 连接状态场景库：**仅供本应用 UI/业务测试**选择 Cellular/Wi-Fi/Offline 三态 + 默认态。
     * 蜂窝模拟仅限 DI 层——不改写系统连接/Telephony 状态、不影响其他应用。
     */
    val connectivityScenarioRepository: ConnectivityScenarioRepository = ConnectivityScenarioRepository()

    /** 权限协调器：授权集合 + sdkInt 注入 PermissionGate（纯逻辑可测）。*/
    val permissionCoordinator: PermissionCoordinator = PermissionCoordinator(context)

    /**
     * 官方测试提供者输出端口（§6）：生产实现 LocationManagerTestLocationSink（仅此端口，
     * 无隐藏 mock 路径）；服务生命周期内由 TestLocationOutput 驱动 add/set/setEnabled/remove。
     */
    val testLocationSink: TestLocationSink = LocationManagerTestLocationSink(context)
}
