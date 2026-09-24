package io.github.haohaoo3o.tracklab

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.amap.api.maps.AMap
import com.amap.api.maps.MapView
import com.amap.api.maps.model.LatLng
import io.github.haohaoo3o.tracklab.core.geo.CoordTransform
import io.github.haohaoo3o.tracklab.core.geo.LatLon
import io.github.haohaoo3o.tracklab.data.ConnectivityScenarioRepository
import io.github.haohaoo3o.tracklab.databinding.ActivityMainBinding
import io.github.haohaoo3o.tracklab.service.PlaybackForegroundService
import io.github.haohaoo3o.tracklab.service.PlaybackMetricsFormat
import io.github.haohaoo3o.tracklab.service.PlaybackState
import io.github.haohaoo3o.tracklab.service.PlaybackStateMachine
import io.github.haohaoo3o.tracklab.service.PlaybackUiState
import io.github.haohaoo3o.tracklab.ui.PermissionGate
import io.github.haohaoo3o.tracklab.ui.map.MapEditController
import io.github.haohaoo3o.tracklab.ui.map.MapEditViewModel
import io.github.haohaoo3o.tracklab.ui.playback.PlaybackRenderer
import io.github.haohaoo3o.tracklab.ui.privacy.PrivacyGateController
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/**
 * 主界面（`android:exported="true"`）。布局 ID 见 activity_main.xml；冒烟断言见 SmokeTest。
 *
 * 接线：
 * - 隐私三态：NEEDS_CONSENT 显示 `privacy_dialog_root`；NEEDS_KEY 显示 `txt_key_missing`
 *   明确诊断（**不创建 MapView**，未配置 Key 不崩溃）；READY 过合规闸门（同意之后、
 *   MapView 创建之前调用 updatePrivacyShow/Agree）后创建 MapView；
 * - 地图点击/长按按固定顺序采集六点（p0 顶部→p5 右上切点），编号 Marker、撤销/重置；
 * - 拟合预览 Polyline（中心线）+ 边界走廊（±W）显示；
 * - 配速与步频范围展示（模型固定界，MotionContracts 同源）、p_base/laps/seed 输入与校验；
 * - 导出 GPX/GeoJSON（应用私有 exports/ 目录，WGS-84，导出保序）；
 * - 开始测试回放入口（`AppContainer.playbackEntry`，由前台服务实现替换）。
 *
 * SDK 生命周期与 Activity 一一对应（onCreate/onResume/onPause/onDestroy/onLowMemory/onSaveInstanceState）。
 * 界面常驻『测试/模拟定位』标识与测试轨迹声明；不隐藏 mock 标志、不自动控制其他应用。
 * btn_pause/btn_stop 的回放控制保持可点、不崩溃。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val vm: MapEditViewModel by lazy {
        ViewModelProvider(this, appContainer.mapEditViewModelFactory)[MapEditViewModel::class.java]
    }

    private val appContainer get() = (application as TrackLabApp).appContainer

    private val gate: PrivacyGateController get() = appContainer.privacyGateController

    private var mapView: MapView? = null
    private var mapController: MapEditController? = null

    /** 输出一（应用内地图回放）渲染器（MapView 创建后可用；NEEDS_KEY 态为 null，不崩溃）。 */
    private var playbackRenderer: PlaybackRenderer? = null

    /** 权限门（授权集合 + sdkInt 注入；未授权点『开始』给引导 UI）。*/
    private var permissionGate: PermissionGate? = null

    /** 上次观测的 mock 引导态（边沿触发引导文案，避免刷屏）。 */
    private var lastMockGuidance: Boolean = false

    /** 运行时权限申请回调（授权集合变化 → 重评权限门）。 */
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        val gate = permissionGate
        permissionGate = appContainer.permissionCoordinator.createGate(
            mockSelected = gate?.mockSelected ?: false,
        )
    }

    /** MapView 创建前暂存的实例状态（延迟创建场景——同意后建图时转交 MapView.onCreate）。 */
    private var pendingMapState: Bundle? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        pendingMapState = savedInstanceState

        // 进程重建恢复（ViewModel 仅覆盖配置变更；字符串快照走 onSaveInstanceState，与服务快照同风格）
        savedInstanceState?.getString(STATE_SNAPSHOT)?.let { encoded ->
            try {
                vm.restoreSnapshot(encoded)
            } catch (e: IllegalArgumentException) {
                // 快照损坏：保留默认值，不崩溃（明确诊断见状态行）
                binding.txtMapStatus.text = getString(R.string.error_state_restore, e.message ?: "state")
            }
        }

        bindInputs()
        bindButtons()
        applyGateUi()
        initMapIfAllowed()
        refreshTrackUi()
        restorePlaybackIfAny()
        collectPlaybackState()
    }

    // ---------------------------------------------------------------- 生命周期 ⇄ MapView（一一对应）

    override fun onResume() {
        super.onResume()
        mapView?.onResume()
    }

    override fun onPause() {
        mapView?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        mapView?.onDestroy()
        super.onDestroy()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView?.onLowMemory()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        mapView?.onSaveInstanceState(outState)
        outState.putString(STATE_SNAPSHOT, vm.snapshot())
        super.onSaveInstanceState(outState)
    }

    // ---------------------------------------------------------------- 隐私三态

    private fun applyGateUi() {
        binding.privacyDialogRoot.visibility =
            if (gate.shouldShowConsentDialog()) View.VISIBLE else View.GONE
        binding.txtKeyMissing.visibility =
            if (gate.shouldShowKeyMissingHint()) View.VISIBLE else View.GONE
    }

    /**
     * READY 时创建 MapView（合规时序唯一落点：[PrivacyGateController.beforeMapViewCreated]
     * 内部先 updatePrivacyShow/Agree 再放行；未同意/无 Key 返回 false——明确诊断而不创建地图）。
     */
    private fun initMapIfAllowed() {
        if (mapView != null) return
        if (!gate.beforeMapViewCreated()) return
        val mv = MapView(this)
        binding.mapContainer.addView(mv)
        mv.onCreate(pendingMapState)
        pendingMapState = null
        mapView = mv
        val amap = mv.map
        mapController = MapEditController(amap)
        playbackRenderer = PlaybackRenderer(amap)
        bindMapListeners(amap)
    }

    private fun bindMapListeners(amap: AMap) {
        // 长按/点击均按顺序采集（需求固定项）
        amap.setOnMapClickListener { latLng -> onMapPointSelected(latLng) }
        amap.setOnMapLongClickListener { latLng -> onMapPointSelected(latLng) }
    }

    private fun onMapPointSelected(latLng: LatLng) {
        // 地图口径 GCJ-02 → 状态口径 WGS-84
        val wgs = CoordTransform.gcj02ToWgs84(LatLon(latLng.latitude, latLng.longitude))
        val added = vm.addPoint(wgs)
        if (!added) {
            binding.txtMapStatus.text = getString(R.string.map_hint_point_limit)
            return
        }
        when (vm.state.points.size) {
            1 -> mapController?.focusOn(wgs)
            MapEditViewModel.POINT_COUNT -> mapController?.fitCameraToBounds(
                (vm.tryFit() as? MapEditViewModel.FitPreview.Ready)?.centerlineWgs84 ?: vm.state.points
            )
        }
        refreshTrackUi()
    }

    // ---------------------------------------------------------------- 输入 / 按钮

    private fun bindInputs() {
        binding.editPBase.setText(vm.state.pBaseSecPerKm.toString())
        binding.editLaps.setText(vm.state.laps.toString())
        binding.editSeed.setText(vm.state.seed.toString())
        binding.txtRangeInfo.text = getString(
            R.string.range_info,
            vm.paceRangeSecPerKm.start.toInt(),
            vm.paceRangeSecPerKm.endInclusive.toInt(),
            vm.cadenceRangeSpm.start.toInt(),
            vm.cadenceRangeSpm.endInclusive.toInt(),
        )
        val applyOnFocusLoss = View.OnFocusChangeListener { _, hasFocus -> if (!hasFocus) applyInputsFromForm() }
        binding.editPBase.onFocusChangeListener = applyOnFocusLoss
        binding.editLaps.onFocusChangeListener = applyOnFocusLoss
        binding.editSeed.onFocusChangeListener = applyOnFocusLoss
    }

    private fun bindButtons() {
        // 隐私对话框
        binding.btnPrivacyAgree.setOnClickListener {
            gate.onAgree()
            applyGateUi()
            initMapIfAllowed()
            refreshTrackUi()
        }
        binding.btnPrivacyDeny.setOnClickListener {
            gate.onDeny()
            applyGateUi()
        }
        binding.btnPrivacyPolicy.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_POLICY_URL)))
        }

        // 撤销 / 重置（六点采集）
        binding.btnUndo.setOnClickListener {
            vm.undoPoint()
            refreshTrackUi()
        }
        binding.btnReset.setOnClickListener {
            vm.resetPoints()
            mapController?.clearOverlays()
            refreshTrackUi()
        }

        // 导出 GPX / GeoJSON（导出保序口径；WGS-84）
        binding.btnExportGpx.setOnClickListener { exportTrack(gpx = true) }
        binding.btnExportGeojson.setOnClickListener { exportTrack(gpx = false) }

        // 开始测试回放入口（PlaybackForegroundService 驱动，§7）
        binding.btnStart.setOnClickListener { startTestPlayback() }
        // 主控三键 + 回放控制：暂停/继续切换、停止（无会话时可点、无异常、无副作用）
        binding.btnPause.setOnClickListener { togglePauseResume() }
        binding.btnStop.setOnClickListener { stopPlayback() }

        // 连接场景选择（仅供本应用 UI/业务测试；不改写系统状态、不影响其他应用）
        binding.btnConnDefault.setOnClickListener { selectConnectivity(ConnectivityScenarioRepository.Scenario.DEFAULT) }
        binding.btnConnCellular.setOnClickListener { selectConnectivity(ConnectivityScenarioRepository.Scenario.CELLULAR) }
        binding.btnConnWifi.setOnClickListener { selectConnectivity(ConnectivityScenarioRepository.Scenario.WIFI) }
        binding.btnConnOffline.setOnClickListener { selectConnectivity(ConnectivityScenarioRepository.Scenario.OFFLINE) }
        refreshConnectivityUi()
    }

    /** 连接场景选择（DI 层场景库；仅本应用内 UI/业务分支取值）。 */
    private fun selectConnectivity(scenario: ConnectivityScenarioRepository.Scenario) {
        appContainer.connectivityScenarioRepository.select(scenario)
        refreshConnectivityUi()
    }

    private fun refreshConnectivityUi() {
        val current = appContainer.connectivityScenarioRepository.current()
        binding.btnConnDefault.isActivated = current == ConnectivityScenarioRepository.Scenario.DEFAULT
        binding.btnConnCellular.isActivated = current == ConnectivityScenarioRepository.Scenario.CELLULAR
        binding.btnConnWifi.isActivated = current == ConnectivityScenarioRepository.Scenario.WIFI
        binding.btnConnOffline.isActivated = current == ConnectivityScenarioRepository.Scenario.OFFLINE
    }

    /** 表单 → ViewModel（校验）；返回是否全部合法。*/
    private fun applyInputsFromForm(): Boolean {
        val error = vm.applyInputs(
            binding.editPBase.text?.toString().orEmpty(),
            binding.editLaps.text?.toString().orEmpty(),
            binding.editSeed.text?.toString().orEmpty(),
        )
        if (error != MapEditViewModel.InputError.NONE) {
            binding.txtMapStatus.text = getString(errorTextRes(error))
            return false
        }
        return true
    }

    private fun errorTextRes(error: MapEditViewModel.InputError): Int = when (error) {
        MapEditViewModel.InputError.P_BASE -> R.string.error_invalid_p_base
        MapEditViewModel.InputError.LAPS -> R.string.error_invalid_laps
        MapEditViewModel.InputError.SEED -> R.string.error_invalid_seed
        MapEditViewModel.InputError.NONE -> R.string.map_hint_all_collected
    }

    // ---------------------------------------------------------------- 拟合预览 / 状态行

    private fun refreshTrackUi() {
        val titles = pointTitles()
        mapController?.renderPoints(vm.state.points, titles)
        when (val fit = vm.tryFit()) {
            is MapEditViewModel.FitPreview.Incomplete -> {
                mapController?.renderPreview(emptyList(), emptyList(), emptyList())
                val next = vm.state.points.size + 1
                binding.txtMapStatus.text = getString(
                    R.string.map_hint_next_point, next, titles[next - 1],
                )
            }
            is MapEditViewModel.FitPreview.Rejected -> {
                mapController?.renderPreview(emptyList(), emptyList(), emptyList())
                binding.txtMapStatus.text = getString(R.string.map_hint_preview_failed, fit.message)
            }
            is MapEditViewModel.FitPreview.Ready -> {
                mapController?.renderPreview(
                    fit.centerlineWgs84, fit.edgePlusWgs84, fit.edgeMinusWgs84,
                )
                binding.txtMapStatus.text = getString(R.string.map_hint_all_collected)
            }
        }
    }

    /** p0..p5 标题（固定命名）。*/
    private fun pointTitles(): List<String> {
        val names = listOf(
            R.string.point_name_p0, R.string.point_name_p1, R.string.point_name_p2,
            R.string.point_name_p3, R.string.point_name_p4, R.string.point_name_p5,
        )
        return names.mapIndexed { i, res -> getString(R.string.point_title_format, i, getString(res)) }
    }

    // ---------------------------------------------------------------- 导出（应用私有目录，无额外权限）

    private fun exportTrack(gpx: Boolean) {
        if (!applyInputsFromForm()) return
        val content = if (gpx) vm.buildGpx() else vm.buildGeoJson()
        if (content == null) {
            binding.txtMapStatus.text = getString(R.string.error_no_track)
            return
        }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        val name = if (gpx) "tracklab_$stamp.gpx" else "tracklab_$stamp.geojson"
        try {
            val dir = File(getExternalFilesDir(null) ?: filesDir, "exports")
            if (!dir.isDirectory && !dir.mkdirs()) {
                throw IOException("无法创建导出目录 $dir")
            }
            val file = File(dir, name)
            file.writeText(content)
            binding.txtMapStatus.text = getString(R.string.export_success, file.absolutePath)
            Toast.makeText(this, R.string.export_success_toast, Toast.LENGTH_LONG).show()
        } catch (e: IOException) {
            binding.txtMapStatus.text = getString(R.string.error_export_failed, e.message ?: name)
        }
    }

    // ---------------------------------------------------------------- 测试回放入口（§7）

    private fun startTestPlayback() {
        if (!applyInputsFromForm()) return
        val samples = vm.generateSamples()
        if (samples == null) {
            binding.txtMapStatus.text = getString(R.string.error_no_track)
            return
        }
        // 权限门：缺运行时权限 → 引导 UI + 发起申请；模拟位置应用未选中 → 引导 UI（回放继续）。
        val gate = appContainer.permissionCoordinator.createGate(
            mockSelected = permissionGate?.mockSelected ?: false,
        )
        permissionGate = gate
        if (gate.state == PermissionGate.State.MISSING_PERMISSIONS) {
            binding.txtMapStatus.text = getString(R.string.permission_guide_message)
            permissionLauncher.launch(gate.missingPermissions.toTypedArray())
            return
        }
        if (gate.state == PermissionGate.State.MISSING_MOCK_SELECTION) {
            binding.txtMapStatus.text = getString(R.string.mock_location_guide)
        }
        val s = vm.state
        playbackRenderer?.showRoute(samples.map { LatLon(it.latitudeDeg, it.longitudeDeg) })
        appContainer.playbackEntry.startTestPlayback(samples, s.laps, s.pBaseSecPerKm, s.seed)
        if (gate.state == PermissionGate.State.READY) {
            binding.txtMapStatus.text = getString(R.string.map_hint_playback_requested)
        }
    }

    /** 暂停/继续（无活动会话：可点、无异常、不起服务）。*/
    private fun togglePauseResume() {
        val ui = appContainer.playbackBus.current()
        if (!ui.sessionActive) return
        val action = if (ui.state == PlaybackState.PLAYING) {
            PlaybackForegroundService.ACTION_PAUSE
        } else {
            PlaybackForegroundService.ACTION_RESUME
        }
        startService(Intent(this, PlaybackForegroundService::class.java).setAction(action))
    }

    /** 停止（无活动会话——如进程重建后恢复的 PAUSED——本地安全收尾，不拉起服务）。 */
    private fun stopPlayback() {
        val ui = appContainer.playbackBus.current()
        if (ui.sessionActive) {
            startService(
                Intent(this, PlaybackForegroundService::class.java)
                    .setAction(PlaybackForegroundService.ACTION_STOP),
            )
            return
        }
        appContainer.snapshotStore.clear()
        appContainer.playbackBus.publish(PlaybackUiState(state = PlaybackState.STOPPED))
        playbackRenderer?.clear()
    }

    /**
     * 进程重建安全恢复：快照 → [PlaybackStateMachine.fromSnapshot] → **一律 PAUSED
     * （不自动续跑）**，恢复累计值展示；快照损坏 → 明确诊断、清快照、不崩溃。
     */
    private fun restorePlaybackIfAny() {
        val snapshot = appContainer.snapshotStore.load() ?: return
        try {
            val restored = PlaybackStateMachine.fromSnapshot(snapshot)
            appContainer.playbackBus.publish(
                PlaybackUiState(
                    state = restored.state,
                    sessionActive = false,
                    sampleIndex = restored.sampleIndex,
                    totalSamples = 0,
                    totalLaps = vm.state.laps,
                    currentLap = 0,
                    distanceM = restored.distanceM,
                ),
            )
            binding.txtMapStatus.text = getString(R.string.playback_restored_paused)
        } catch (e: IllegalArgumentException) {
            appContainer.snapshotStore.clear()
            binding.txtMapStatus.text = getString(R.string.error_state_restore, e.message ?: "snapshot")
        }
    }

    /** `repeatOnLifecycle(STARTED)` 收集 PlaybackBus（指标/按钮态/mock 引导/地图回放）。*/
    private fun collectPlaybackState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                appContainer.playbackBus.state.collect { ui -> renderPlaybackUi(ui) }
            }
        }
    }

    private fun renderPlaybackUi(ui: PlaybackUiState) {
        binding.txtPlaybackMetrics.text = getString(
            R.string.overlay_metrics_format,
            ui.currentLap,
            ui.totalLaps,
            PlaybackMetricsFormat.distanceKm(ui.distanceM),
            PlaybackMetricsFormat.pace(ui.paceSecPerKm),
            PlaybackMetricsFormat.cadence(ui.cadenceSpm),
        )
        binding.btnPause.setText(if (ui.state == PlaybackState.PAUSED) R.string.action_resume else R.string.action_pause)
        // mock 引导：边沿触发 mock_location_guide；试探结果回灌权限门。
        if (ui.mockGuidance && !lastMockGuidance) {
            binding.txtMapStatus.text = getString(R.string.mock_location_guide)
        }
        lastMockGuidance = ui.mockGuidance
        if (ui.sessionActive) {
            permissionGate?.onMockSelectionChanged(!ui.mockGuidance)
        }
        if (ui.state == PlaybackState.COMPLETED && ui.sessionActive.not()) {
            binding.txtMapStatus.text = getString(R.string.playback_completed_notice)
        }
        // 输出一：应用内地图回放（NEEDS_KEY 态无 MapView，跳过不崩溃）。
        val lat = ui.latitudeDeg
        val lon = ui.longitudeDeg
        if (lat != null && lon != null) {
            playbackRenderer?.updatePosition(LatLon(lat, lon))
        }
        if (ui.state == PlaybackState.STOPPED && !ui.sessionActive) {
            playbackRenderer?.clear()
        }
    }

    companion object {

        /** onSaveInstanceState 键：MapEditViewModel 的版本化快照字符串。 */
        private const val STATE_SNAPSHOT = "map_edit_snapshot"

        private const val PRIVACY_POLICY_URL =
            "https://github.com/haohaoo3o/TrackLab-FakeLocation-Lepao/blob/main/PRIVACY.md"
    }
}
