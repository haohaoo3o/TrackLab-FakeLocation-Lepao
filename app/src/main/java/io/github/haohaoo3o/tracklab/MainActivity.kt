package io.github.haohaoo3o.tracklab

import android.content.Intent
import android.graphics.Paint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.ColorRes
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.amap.api.maps.AMap
import com.amap.api.maps.MapView
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.Marker
import io.github.haohaoo3o.tracklab.core.geo.CoordTransform
import io.github.haohaoo3o.tracklab.core.geo.LatLon
import io.github.haohaoo3o.tracklab.core.geo.TrackPreset
import io.github.haohaoo3o.tracklab.core.geo.TrackPresetLibrary
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/**
 * 主界面（F8：`android:exported="true"`）。View ID 表见 docs/CONTRACTS.md（SmokeTest B8 断言）。
 *
 * map 阶段接线：
 * - 隐私三态（）：NEEDS_CONSENT 显示 `privacy_dialog_root`；NEEDS_KEY 显示 `txt_key_missing`
 *   明确诊断（**不创建 MapView**，未配置 Key 不崩溃）；READY 过合规闸门（：同意之后、
 *   MapView 创建之前调用 updatePrivacyShow/Agree）后创建 MapView；
 * - 地图点击/长按按固定顺序采集六点（p0 顶部→p5 右上切点），编号 Marker、撤销/重置；
 * - **预制跑道线路**（`btn_preset_standard_400`）：一键载入六点
 *   （标准 400m 放地图视野中心），六点 Marker 可拖动微调
 *   （`OnMarkerDragListener` → WGS-84 回写 → 重算拟合），贴合实际跑道后走同一 TrackFitter 校验；
 * - 拟合预览 Polyline（中心线）+ 边界走廊（±W）显示；
 * - 配速与步频范围展示（模型固定界，MotionContracts 同源）、p_base/laps/seed 输入与校验（）；
 * - 导出 GPX/GeoJSON（应用私有 exports/ 目录，WGS-84，B1 口径）；
 * - 开始测试回放入口（`AppContainer.playbackEntry`替换实现）。
 *
 * SDK 生命周期与 Activity 一一对应（onCreate/onResume/onPause/onDestroy/onLowMemory/onSaveInstanceState）。
 * 界面常驻『测试/模拟定位』标识与测试轨迹声明；不隐藏 mock 标志、不自动控制其他应用（）。
 * btn_pause/btn_stop 为回放控制（无会话时可点、无异常、无副作用）。
 *
 * ENVIRONMENT_ONLY/SENSORS_ONLY/FULL，默认 FULL）+ 目标版本/模块心跳/各 Hook 组命中/
 * GNSS·计步状态/会话状态展示 + 遥测清零。控制面走既有 Provider 协议（控制端专属），
 * STARTED 期间按推送节流周期刷新。
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

    /** 权限门（：授权集合 + sdkInt 注入；未授权点『开始』给引导 UI）。 */
    private var permissionGate: PermissionGate? = null

    /**
     * Mock 引导残留清除器（R4 实证修复：开始入口 MISSING_MOCK_SELECTION 投机性引导
     * 在服务 ACTIVE 恢复推帧后必须被清除，否则状态行残留 mock_location_guide 与
     * playing 推帧中同屏矛盾——见 evidence/2026-10-08-loop r4-replay/r4-mock-recheck）。
     */
    private val mockGuideResolver = io.github.haohaoo3o.tracklab.ui.MockGuideResolver()

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

    // ------------------------------------------------ TL 外观层（页签 / 状态板折叠 / 日志跟随）
    //
    // 本区块**只做视图装配与样式**：不碰 service 逻辑，不改状态发布时序，
    // 不改回放控制函数出具的事实文案
    // （控制台的时间列与级别栏是视图层前缀，内容串原样透传）。
    //
    // - 同一 Activity 内 4 页靠 VISIBLE/GONE 切换（无 Fragment/Dialog/ViewPager2）；
    //   页签 ID matrix_tab_* 是采集脚本的导航锚点，不得改名；
    // - 默认页 = 编辑（PANEL_TRACK）：预设/变换/参数/导出全部默认展开、无折叠键；
    // - 日志页（PANEL_CONSOLE）默认不可见、可经页签进入；状态板整板收起态只存内存；
    // - 诊断日志只在控制台页展示，主舞台仅保留 HUD + 最新一条事件行；
    // - 动效只许 300ms 页内淡入（系统减少动态优先），无数字雨/呼吸光/扫描线/脉冲。

    /** 当前页（默认 P2 编辑：预设/参数首屏直露）。 */
    private var currentPanel = PANEL_TRACK

    /** 状态板整板收起/展开（收起 maxLines=12；既有交互保留）。 */

    /** 最近一次回放状态（LED/状态词都是它驱动；全局恒写，不随页签停更）。 */
    private var lastPlaybackState: PlaybackState = PlaybackState.IDLE

    private val decorHandler = Handler(Looper.getMainLooper())

    /** 控制台行内容去重表（键 = 视图 ID；：只在文本变化时写，变化才刷新时间列）。 */
    private val consoleLastText = HashMap<Int, String>()

    /**
     * 控制台**记录起点**表（键 = 视图 ID；值 = 该视图文本里的记录起始偏移，升序）。
     *
     * 「记录」= 一条完整事实（事件行 1 条；观测行 1 条；状态板 1 行 + 其 recovery/risk 子行）。
     * 只有一个记录的视图（段头、会话/心跳/目标/回执/轨迹行、GNSS 行）不入表 → 起点恒为 0。
     * 贴底吸附按本表把视口顶边钉在**记录起点**（[consoleSnapOffset]），
     * 故「滚动后首行是上一行的残尾」结构性不可能出现。
     */
    private val consoleRecordStarts = HashMap<Int, IntArray>()

    /** 上一次自动贴底落点（scrollY；-1 = 尚未自动滚过）——用于区分「吸附自身造成的位移」与「用户滚动」。 */
    private var consoleAutoScrollY = -1

    /**
     * 用户是否已**主动脱离**贴底跟随（2026-10-10 真机回归修复）。
     *
     * 旧判据「当前 scrollY ≥ 上一次落点 − 24dp ⇒ 仍跟随」在**任何向下滚动**下都恒真
     * （滚动位置只会变大）⇒ 每次遥测刷新都把视口拽回底部：Espresso 的 scrollTo 被拽走
     * （`labSectionDefaultFullAndControllerOnlyOperations` 确定性失败）、真机用户往下翻历史同样被拽回。
     * 现改为显式状态，唯一维护点 = 滚动观测 [onConsoleScrollChanged]；
     * 为 true 时 [autoScrollConsole] 直接返回、[consoleScrollToBottom] 也不动作。
     */
    private var consoleUserDetached = false

    /**
     * 正在由 [consoleScrollToBottom] 写入吸附落点（仅吸附执行期间为 true）。
     *
     * 吸附自身也会导致滚动回调，若不加区分就会被 [onConsoleScrollChanged] 判成「用户滚动」
     * ⇒ 吸附一次即自我脱离（跟随自锁），故吸附执行期间直接忽略回调。
     */
    private var consoleApplyingSnap = false

    /** 指标行最近一次写入的内容（同值不重绘，≤10Hz 限流口径）。 */
    private var lastMetricsBase: String? = null

    /** 顶栏状态词最近一次写入内容（同值不重绘）。 */
    private var lastStateText: String? = null

    /** 事件行时间列（8 字符定宽；单例，仅主线程使用）。 */
    private val consoleTimeFormat = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)

    /** 「错误 N」片段染色定位缓存（键 = errors 值；null = 定位失败，宁可不染不误染）。 */

    /**
     * 控制台贴底自动滚动（瞬时跳转，不用 smoothScrollTo）。
     *
     * 2026-10-10 真机修复（第一轮）：原来直接 `scrollTo(0, child.height)`，视口顶边落在**半行**上。
     * 2026-10-10 真机复审修复（第二轮）：吸附目标由「文本行边界」改为「**记录边界**」
     * （[consoleSnapOffset]）——原口径下续行本身也是合法落点，复审看到「级别图例下方首先出现孤立的
     * 『· 生效中』，其后可见编号从 05 开始」= 首行是上一条记录被裁掉后的残尾。
     * 现视口顶边只落在**记录起点**（或记录之间的分隔空白）；吸附只回退不足**一条记录**，
     * 且目标量 ≤ maxScroll ⇒ 视口底边始终被内容覆盖（不会露白）。
     *
     * 2026-10-10 真机回归修复（第三轮）：落点不再被用于「判定用户是否上滚」（旧①恒真，见
     * [consoleUserDetached]），改为只服务两个用途——(a) 吸附执行期间的「自身位移」判别；
     * (b) 执行点复判：post 与执行之间可能切面板或用户已脱离，此时整体放弃本次吸附。
     */
    private val consoleScrollToBottom = Runnable {
        // 执行点复判：只有「当前面板 == 控制台」且「未脱离跟随」才吸附；否则视口一个像素都不碰。
        if (currentPanel != PANEL_CONSOLE) return@Runnable
        if (consoleUserDetached) return@Runnable
        val scroll = binding.matrixConsoleScroll
        val content = scroll.getChildAt(0) as? ViewGroup ?: return@Runnable
        val maxScroll = (content.height - scroll.height).coerceAtLeast(0)
        val target = consoleSnapOffset(content, maxScroll)
        // 先写落点：让 [onConsoleScrollChanged] 的「本次变化 == 吸附目标」判据在**同步回调期间**也成立
        // （[consoleApplyingSnap] 是同一件事的兜底判据，两者任一成立即视为吸附自身位移）。
        consoleAutoScrollY = target
        // 空转收口：目标与当前位置相同（内容没长高 / 已在记录边界上）⇒ 无位移可言，不 scrollTo、不重绘
        // （View.scrollTo 对同位置本就是 no-op，这里把「不重绘」显式化并省掉一次同步回调）。
        if (target == scroll.scrollY) return@Runnable
        consoleApplyingSnap = true
        try {
            scroll.scrollTo(0, target)
        } finally {
            // try/finally：标志绝不卡在 true（否则用户滚动将永远被当成吸附自身位移，脱离判据失效）。
            consoleApplyingSnap = false
        }
    }

    /** 界面时钟（本机挂钟；1s 一次，onStop/onDestroy 移除）。 */
    private val clockTick = object : Runnable {
        override fun run() {
            binding.matrixClock.text = consoleTimeFormat.format(java.util.Date())
            decorHandler.postDelayed(this, CLOCK_TICK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        pendingMapState = savedInstanceState

        // 进程重建恢复（ViewModel 仅覆盖配置变更；字符串快照走 onSaveInstanceState，B2/B3 同风格）
        savedInstanceState?.getString(STATE_SNAPSHOT)?.let { encoded ->
            try {
                vm.restoreSnapshot(encoded)
            } catch (e: IllegalArgumentException) {
                // 快照损坏：保留默认值，不崩溃（明确诊断见状态行）
                writeMapStatus(getString(R.string.error_state_restore, e.message ?: "state"), ConsoleLevel.ERROR)
            }
        }

        // 贴底跟随的「脱离」态随重建恢复（滚动位置本身由 View 体系恢复：ScrollView 自己存取 scrollY）。
        // 不恢复的话，转屏/重建后会把**正在翻历史**的用户按「未脱离」处理、在下一次内容变化时拽回底部
        // （与 STATE_SNAPSHOT 同风格；首次启动 = false = 默认跟随）。
        consoleUserDetached = savedInstanceState?.getBoolean(STATE_CONSOLE_DETACHED) ?: false

        bindInputs()
        bindButtons()
        bindPanelTabs()
        bindMatrixUi()
        applyGateUi()
        initMapIfAllowed()
        refreshTrackUi()
        restorePlaybackIfAny()
        // MainActivity 恒 exported 且为 launcher（边界允许的唯二导出之一），不新增任何组件/按钮。
        collectPlaybackState()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    // ---------------------------------------------------------------- 生命周期 ⇄ MapView（一一对应）

    override fun onStart() {
        super.onStart()
        // 界面时钟（本机挂钟）+ 顶栏运行态重算（STARTED 才跑）。
        startClock()
        applyTopBarState()
    }

    override fun onResume() {
        super.onResume()
        mapView?.onResume()
    }

    override fun onPause() {
        mapView?.onPause()
        super.onPause()
    }

    override fun onStop() {
        // 时钟与 LED 在 onStop() 里复位，不碰回放逻辑。
        stopDecorAnimations()
        super.onStop()
    }

    override fun onDestroy() {
        stopDecorAnimations()
        decorHandler.removeCallbacks(consoleScrollToBottom)
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
        // 贴底跟随的「脱离」态：滚动位置由 View 体系自己恢复，跟随意图必须一起恢复（见 onCreate）。
        outState.putBoolean(STATE_CONSOLE_DETACHED, consoleUserDetached)
        super.onSaveInstanceState(outState)
    }

    // ---------------------------------------------------------------- 隐私三态（）

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
        // 白天底图 + 压暗层（2026-10-10 复审收口 / 第三轮）：上一版曾把底图切成 AMap.MAP_TYPE_NIGHT
        // 来压"整屏最大高饱和面"，真机复审判**反向**——夜间样式实测地图区平均 HSV 饱和度 **0.490**
        // （蓝底 + 亮青标注 + 黄绿跑道）、平均亮度 85，比白天的 0.156 / 215 **更艳**。
        // 现版：底图回默认白天样式（MAP_TYPE_NORMAL 显式写出，不依赖 SDK 默认值）。
        // TL 焕新后不再设整图压暗层（移除 matrix_map_dim），亮度/彩度以白天底图 + 深色叠加线为准
        // （见 activity_main.xml 与 MapEditController / PlaybackRenderer 的注释与实算）。
        // 叠加线/点位颜色随之改回 DESIGN 的"白天档"深色三色（见 MapEditController /
        // PlaybackRenderer 的注释与实算）：压暗后的浅色叠加线只剩 ≈1.9:1，深色档 3.1~3.4:1。
        amap.mapType = AMap.MAP_TYPE_NORMAL
        // 2026-10-10 复审收口（SDK 自带地图控件）：高德默认在右下角绘制白色缩放条，在压暗后的
        // 深色底图上是一块近白矩形（复审实测亮度 p90≈235），且盖住「统计学院楼」等标注。本界面的
        // 平移/缩放/旋转由自绘按钮行承担（activity_main.xml 的 btn_track_left/up/down/right +
        // btn_track_scale_down/up + btn_track_rotate_ccw/cw），屏幕上不需要 SDK 浮层控件，故五项
        // 显式关闭、不依赖 SDK 默认值：
        //   isZoomControlsEnabled   —— SDK 默认开，即复审所指的右下白色缩放条（本轮病灶）；
        //   isScaleControlsEnabled  —— 比例尺条，同为浅色浮层；
        //   isCompassEnabled        —— 罗盘；方向信息另有跑道方位读数（track_transformed）；
        //   isMyLocationButtonEnabled —— 定位按钮会把相机拉向设备真实位置，与本界面「编辑/回放
        //                             目标轨迹」的语义无关；
        //   isIndoorSwitchEnabled   —— 室内楼层切换器（校园建筑有室内图数据时会浮出，同属浅色控件）。
        // 只关控件、不动手势：双指缩放/拖动/旋转保持 SDK 默认（不调用任何 *GesturesEnabled）；
        // 高德 logo 是许可要求必须保留的，故本类不触碰任何 logo 相关 API（SDK 里 setLogoEnable
        // 为 protected，也没有可绕过的调用路径）。
        amap.uiSettings.apply {
            isZoomControlsEnabled = false
            isScaleControlsEnabled = false
            isCompassEnabled = false
            isMyLocationButtonEnabled = false
            isIndoorSwitchEnabled = false
        }
        mapController = MapEditController(amap)
        playbackRenderer = PlaybackRenderer(amap)
        bindMapListeners(amap)
        // 旋屏/进程重建恢复：已有 ≥2 点时相机直接框住跑道（快照只存点位，不存相机）
        if (vm.state.points.size >= 2) {
            mapController?.fitCameraToBounds(
                (vm.tryFit() as? MapEditViewModel.FitPreview.Ready)?.centerlineWgs84
                    ?: vm.state.points,
            )
        }
    }

    private fun bindMapListeners(amap: AMap) {
        // 长按/点击均按顺序采集（需求固定项）
        amap.setOnMapClickListener { latLng -> onMapPointSelected(latLng) }
        amap.setOnMapLongClickListener { latLng -> onMapPointSelected(latLng) }
        // 六点可拖动微调（预制跑道载入后贴合实际跑道）：拖动结束回写 WGS-84 并重算拟合
        amap.setOnMarkerDragListener(object : AMap.OnMarkerDragListener {
            override fun onMarkerDragStart(marker: Marker) = Unit
            override fun onMarkerDrag(marker: Marker) = Unit
            override fun onMarkerDragEnd(marker: Marker) = onMarkerNudged(marker)
        })
    }

    /** 拖动结束：GCJ-02 → WGS-84 回写对应点位并刷新拟合预览（拟合被拒时保留错误原因）。 */
    private fun onMarkerNudged(marker: Marker) {
        val index = mapController?.markerIndex(marker) ?: return
        val wgs = CoordTransform.gcj02ToWgs84(LatLon(marker.position.latitude, marker.position.longitude))
        if (!vm.replacePoint(index, wgs)) return
        refreshTrackUi()
        // 拟合被拒（拖动越界）时 refreshTrackUi 已显示中文原因，不覆盖；通过则提示微调生效
        if (vm.tryFit() is MapEditViewModel.FitPreview.Ready) {
            writeMapStatus(getString(R.string.preset_nudged, index), ConsoleLevel.INFO)
        }
    }

    private fun onMapPointSelected(latLng: LatLng) {
        // 地图口径 GCJ-02 → 状态口径 WGS-84（）
        val wgs = CoordTransform.gcj02ToWgs84(LatLon(latLng.latitude, latLng.longitude))
        val added = vm.addPoint(wgs)
        if (!added) {
            writeMapStatus(getString(R.string.map_hint_point_limit), ConsoleLevel.WARN)
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
        writeRangeRow(
            getString(
                R.string.range_info,
                vm.paceRangeSecPerKm.start.toInt(),
                vm.paceRangeSecPerKm.endInclusive.toInt(),
                vm.cadenceRangeSpm.start.toInt(),
                vm.cadenceRangeSpm.endInclusive.toInt(),
            ),
        )
        val applyOnFocusLoss = View.OnFocusChangeListener { _, hasFocus -> if (!hasFocus) applyInputsFromForm() }
        binding.editPBase.onFocusChangeListener = applyOnFocusLoss
        binding.editLaps.onFocusChangeListener = applyOnFocusLoss
        binding.editSeed.onFocusChangeListener = applyOnFocusLoss
    }

    private fun bindButtons() {
        // 隐私对话框（B2/）
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

        // 预制跑道线路（一键载入六点；随后拖动 Marker 微调贴合实际跑道）
        binding.btnPresetStandard400.setOnClickListener {
            // 标准 400m 放置到当前地图视野中心（GCJ-02 → WGS-84）
            val center = mapView?.map?.cameraPosition?.target
                ?.let { CoordTransform.gcj02ToWgs84(LatLon(it.latitude, it.longitude)) }
            if (center == null) {
                binding.txtMapStatus.text = getString(R.string.preset_map_not_ready)
                return@setOnClickListener
            }
            loadPreset(TrackPresetLibrary.standard400(center), R.string.preset_standard_400)
        }

        // 跑道整体变换（移动/缩放/旋转；对当前拟合六点整体生效，适配不同跑道）
        binding.btnTrackLeft.setOnClickListener { transformTrack(-panStep, 0.0) }
        binding.btnTrackRight.setOnClickListener { transformTrack(panStep, 0.0) }
        binding.btnTrackUp.setOnClickListener { transformTrack(0.0, panStep) }
        binding.btnTrackDown.setOnClickListener { transformTrack(0.0, -panStep) }
        binding.btnTrackScaleDown.setOnClickListener { scaleTrack(1.0 - scaleStep) }
        binding.btnTrackScaleUp.setOnClickListener { scaleTrack(1.0 + scaleStep) }
        binding.btnTrackRotateCcw.setOnClickListener { rotateTrack(-rotateStepDeg) }
        binding.btnTrackRotateCw.setOnClickListener { rotateTrack(rotateStepDeg) }

        // 导出 GPX / GeoJSON（B1 口径；WGS-84）
        binding.btnExportGpx.setOnClickListener { exportTrack(gpx = true) }
        binding.btnExportGeojson.setOnClickListener { exportTrack(gpx = false) }

        // 开始测试回放入口（PlaybackForegroundService 驱动）
        binding.btnStart.setOnClickListener { startTestPlayback() }
        // B8 ④ + 回放控制：暂停/继续切换、停止（无会话时可点、无异常、无副作用）
        binding.btnPause.setOnClickListener { togglePauseResume() }
        binding.btnStop.setOnClickListener { stopPlayback() }

        // 连接场景选择（：仅供本应用 UI/业务测试；不改写系统状态、不影响其他应用）
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

    // ---------------------------------------------------------------- v2 视图装配（分段面板 / 折叠 / 动效）

    /**
     * 4 枚页签 → 4 个分段面板（同一 Activity 内 VISIBLE/GONE 切换；无 Fragment/Dialog/ViewPager2）。
     * 页签 ID `matrix_tab_*` 是采集脚本的导航锚点（DESIGN），不得改名。
     */
    private fun bindPanelTabs() {
        binding.matrixTabConsole.setOnClickListener { showPanel(PANEL_CONSOLE) }
        binding.matrixTabTrack.setOnClickListener { showPanel(PANEL_TRACK) }
        binding.matrixTabMore.setOnClickListener { showPanel(PANEL_MORE) }
        for ((tab, labelRes) in listOf(
            binding.matrixTabConsole to R.string.matrix_tab_console,
            binding.matrixTabTrack to R.string.matrix_tab_track,
            binding.matrixTabMore to R.string.matrix_tab_more,
        )) {
            tab.contentDescription = getString(R.string.matrix_tab_cd, getString(labelRes))
        }
        showPanel(PANEL_TRACK)
    }

    /**
     * 切换页：瞬时 visibility + isActivated（无动画，避免拖住 Espresso 主线程空闲同步）。
     * 切换后**立即 render 一次**，控件态与观测行立刻与事实一致。
     */
    private fun showPanel(panel: Int) {
        currentPanel = panel
        binding.matrixPanelConsole.visibility = visible(panel == PANEL_CONSOLE)
        binding.matrixPanelTrack.visibility = visible(panel == PANEL_TRACK)
        binding.matrixPanelMore.visibility = visible(panel == PANEL_MORE)
        binding.matrixTabConsole.isActivated = panel == PANEL_CONSOLE
        binding.matrixTabTrack.isActivated = panel == PANEL_TRACK
        binding.matrixTabMore.isActivated = panel == PANEL_MORE
        applyTopBarState()
    }

    private fun visible(shown: Boolean): Int = if (shown) View.VISIBLE else View.GONE

    /** TL 视图装配（仅保留项）：日志贴底跟随观测。 */
    private fun bindMatrixUi() {
        bindConsoleScrollFollow()
    }


    // ---------------------------------------------------------------- TL 运行态外观（LED + 状态词，无装饰动效）

    /**
     * 顶栏状态词 + LED（**全局恒写**：不随页签停更，否则切页后 LED 会停在旧状态）。
     * 呼吸光/脉冲/淡入/光标闪烁已删除；LED 只换色不动形，呼吸恒关（系统减少动态优先）。
     */
    private fun applyTopBarState() {
        val state = lastPlaybackState
        val label = getString(stateTextRes(state))
        if (lastStateText != label) {
            lastStateText = label
            binding.matrixStateText.text = label
        }
        binding.matrixLedStatus.contentDescription = getString(R.string.matrix_led_cd, label)
        binding.matrixLedStatus.setState(
            getColor(ledColorRes(state)),
            halo = state == PlaybackState.PLAYING,
            breathing = false,
        )
    }

    /** 生命周期退出：停时钟 + LED 复位 + 移除待执行回调（幂等）。 */
    private fun stopDecorAnimations() {
        stopClock()
        binding.matrixLedStatus.stop()
        decorHandler.removeCallbacks(consoleScrollToBottom)
    }

    private fun startClock() {
        decorHandler.removeCallbacks(clockTick)
        clockTick.run()
    }

    private fun stopClock() {
        decorHandler.removeCallbacks(clockTick)
    }

    /** LED 五态取色（TL 令牌；非文本元素）。 */
    @ColorRes
    private fun ledColorRes(state: PlaybackState): Int = when (state) {
        PlaybackState.IDLE -> R.color.tl_led_idle
        PlaybackState.PLAYING -> R.color.tl_led_play
        PlaybackState.PAUSED -> R.color.tl_led_pause
        PlaybackState.STOPPED -> R.color.tl_led_stop
        PlaybackState.COMPLETED -> R.color.tl_led_done
    }

    /**
     * 控制台滚动观测：**唯一**维护「用户是否脱离跟随」（[consoleUserDetached]）的地方。
     *
     * 线程：View 体系回调，恒主线程（`scrollTo`/手势处理里同步发出），与 [decorHandler] 同线程，
     * 无需额外同步；监听随视图生命周期释放（视图归本 Activity 所有，无需在 onDestroy 里解绑）。
     *
     * 判据（三种情况，2026-10-10 真机回归修复，替换旧「scrollY ≥ 上次落点 − 24dp」——
     * 该判据遇到任何**向下**滚动都恒真，把 Espresso 的 scrollTo 与真机用户的向下翻页一并拽回底部）：
     * 1. **吸附自身造成的位移**：`consoleApplyingSnap` 为真（吸附执行期间），或落点恰好等于上一次
     *    吸附目标 [consoleAutoScrollY] ⇒ 不是用户操作，**状态不变**（跟随不会被自己打断）；
     * 2. **用户滚动到（或停在）距内容底 ≤ [CONSOLE_STICKY_BOTTOM_DP]**：复位 [consoleUserDetached]
     *    （= 恢复贴底跟随。手动滚到底、内容变矮被 ScrollView 夹紧后都回到这里）；
     * 3. **其余滚动位置变化**：置 [consoleUserDetached] = true（= 停止跟随，直到位置重新回到情况 2）。
     *    手势与程序化 `scrollTo()`（Espresso）在 View 层不可区分，二者都算「用户接管了滚动位置」——
     *    只看位置、**不看方向**：向下滚但没到底同样算脱离（这正是本次回归的成因）。
     */
    private fun onConsoleScrollChanged() {
        if (consoleApplyingSnap) return
        val scroll = binding.matrixConsoleScroll
        val scrollY = scroll.scrollY
        if (consoleAutoScrollY >= 0 && scrollY == consoleAutoScrollY) return
        val content = scroll.getChildAt(0) ?: return
        val threshold = CONSOLE_STICKY_BOTTOM_DP * resources.displayMetrics.density
        val atBottom = scrollY + scroll.height >= content.height - threshold
        consoleUserDetached = !atBottom
    }

    /** 挂上滚动观测（[onConsoleScrollChanged]；视图装配期一次）。 */
    private fun bindConsoleScrollFollow() {
        binding.matrixConsoleScroll.setOnScrollChangeListener(
            View.OnScrollChangeListener { _, _, _, _, _ -> onConsoleScrollChanged() },
        )
    }

    /**
     * 贴底跟随的唯一入口（瞬时跳转，不用 smoothScrollTo）。
     *
     * 跟随判据两条（2026-10-10 真机回归修复；与 DESIGN「仅当用户已在底部时跟随」同口径）：
     * - **未脱离**：`consoleUserDetached == false` ⇒ 允许把视口吸附到**记录边界**（[consoleSnapOffset]）；
     * - **面板**：只有「当前面板 == 控制台」才吸附（其他页签下控制台不可见，吸附即无谓重绘）。
     *
     * 旧判据（已删除）：`scrollY ≥ 上一次落点 − 24dp ⇒ 仍跟随` 与 `scrollY + 视口高 ≥ 内容高 − 24dp`。
     * 前者**任何向下滚动都恒为真**（滚动位置只会变大），于是每一次遥测刷新都把视口拽回底部
     * ——Espresso `scrollTo` 把目标行滚进视口后随即被拽走（`SmokeTest` 断言「不可见」失败），
     * 真机用户往下翻看历史同样被拽回；后者的语义（手动滚到底恢复跟随）已并入
     * [onConsoleScrollChanged] 的复位判据，故这里不再判距底距离（记录边界吸附后视口本就可能
     * 距底一条记录 ≈33.5dp > 24dp，留在这里会「刚吸附就自己停跟随」）。
     *
     * 空转收口：本函数只在**内容真的变了**时被调用（[onConsoleRowsChanged]），5 个写入点
     * （[writeEventRow] / [writeObservedBlock] / [writeGroups] / [showLabStatus] / [renderLabBoard]）
     * 全部先与 [consoleLastText] 去重，文本未变即 return ⇒ 不会为「文本未变」post 吸附；
     * `removeCallbacks` + `post` 还把同一帧内的多次内容变化合并成一次吸附。
     */
    private fun autoScrollConsole() {
        if (currentPanel != PANEL_CONSOLE) return
        if (consoleUserDetached) return
        decorHandler.removeCallbacks(consoleScrollToBottom)
        decorHandler.post(consoleScrollToBottom)
    }

    /**
     * 把目标滚动量吸附到「**记录**边界」（2026-10-10 真机复审修复）。
     *
     * 记录边界 = 每条记录**第一行**的行顶（[consoleRecordStarts] 的偏移 → `Layout.getLineForOffset`
     * → `getLineTop`），外加各子 View 的 top（记录之间的**分隔空白**：段头 8dp / 行间 2dp 的余量）。
     * 返回**不超过 target 的最大边界** ⇒ 视口顶边只可能落在记录起点或记录之间的空白上，
     * 不可能落在某条记录的续行/子行上（复审的「残尾」）。因 target ≤ maxScroll，
     * 视口底边始终被内容覆盖（不会在底部露出空白）。
     * 手动滚动不吸附（用户自己的位置不动）。
     */
    private fun consoleSnapOffset(content: ViewGroup, target: Int): Int {
        var snapped = 0
        for (i in 0 until content.childCount) {
            for (boundary in consoleRecordBoundaries(content.getChildAt(i), 0)) {
                if (boundary <= target) snapped = boundary else return snapped
            }
        }
        return snapped
    }

    /**
     * 单个子 View（可递归）的**记录边界**（相对 matrix_console_body 的 y；自上而下有序）。
     *
     * 先给子 View 自身的 top（分隔空白），再给该 TextView 里每条记录**起点所在行**的行顶；
     * 未登记记录起点的 TextView = 单记录视图（段头 / 事件行 / GNSS 行）⇒ 起点恒为 0。
     * 记录起点偏移即使因极端宽度被系统自然折行，取「该偏移所在行」仍是这条记录的第一行，
     * 故首行永远从记录开头读起。
     */
    private fun consoleRecordBoundaries(view: View, offset: Int): List<Int> {
        if (view.visibility != View.VISIBLE) return emptyList()
        val top = offset + view.top
        if (view is ViewGroup) {
            val out = mutableListOf(top)
            for (i in 0 until view.childCount) out += consoleRecordBoundaries(view.getChildAt(i), top)
            return out
        }
        val text = view as? TextView ?: return listOf(top)
        val layout = text.layout ?: return listOf(top)
        if (layout.lineCount <= 0) return listOf(top)
        // 取**当前 layout** 的文本长度：文本刚换、layout 还没跟上时，偏移夹到末行仍是合法落点。
        val length = layout.text.length
        val starts = consoleRecordStarts[text.id] ?: intArrayOf(0)
        val out = mutableListOf(top)
        for (start in starts) {
            val line = layout.getLineForOffset(start.coerceIn(0, length))
            out += top + text.paddingTop + layout.getLineTop(line)
        }
        return out
    }

    // ---------------------------------------------------------------- 日志控制台排版（等宽 + 级别栏 + 时间列）

    /**
     * 级别栏五值（只映射既有状态词，不新增语义）：VERIFIED→OK / PARTIAL→W / BLOCKED→X /
     * NOT_TESTED→- ；I 为信息/中性观测。
     */
    private enum class ConsoleLevel(val glyph: String, @ColorRes val colorRes: Int) {
        INFO("I ", R.color.tl_console_info),
        OK("OK", R.color.tl_console_ok),
        WARN("W ", R.color.tl_console_warn),
        ERROR("X ", R.color.tl_console_error),
        NONE("- ", R.color.tl_console_mute),
    }

    /**
     * 事件行（有发生时刻）：`HH:MM:SS  XX content`（8+2+2+1 定长列；续行缩进 12 个半角空格）。
     * 时间 = **UI 观测到该文本变化的挂钟时刻**，不是事件在服务/模块里发生的时刻（）；
     * 该列只用于把界面与 logcat 对齐，不参与断言、不落盘、不进导出 JSON。
     */
    private fun eventLine(
        text: String,
        level: ConsoleLevel,
        fragments: List<Pair<String, Int>> = emptyList(),
    ): SpannableString {
        val prefix = consoleTimeFormat.format(java.util.Date()) + "  "
        return leveledLine(prefix, text, level, CONTINUATION_EVENT, fragments)
    }

    /**
     * 观测行（无发生时刻）：`NN XX content`（序号列 2 字符 = `NN` + 空格 + 级别栏 2 字符 + 空格）。
     *
     * content 由 [wrapFields] **预折行**（续行 = 2 个等宽半角空格 + 原字段分隔符）；
     * 只有极端情形（单个字段本身宽于整行）才会由 TextView 自然折行，那种续行按
     * [CONTINUATION_FIELD] 补 2 个等宽半角空格悬挂缩进（[hangIndent] 不重复加）。
     */
    private fun observedLine(index: String, text: String, level: ConsoleLevel): SpannableString =
        leveledLine("$index ", text, level, CONTINUATION_FIELD, emptyList())

    /**
     * 观测行前缀（**唯一出处**）：`NN ` + 级别栏 2 字符 + 1 个空格 = 6 个等宽半角位。
     *
     * [observedLine] / [observedBlock] 据此渲染，[wrapFields] 据此从行宽里**扣掉前缀**——
     * 两者同源，折叠宽度才算得准（2026-10-10 复审修复：旧口径只按整行宽折行、漏算前缀，
     * 折出来的「一行」渲染时比可用宽多出 6 列 ⇒ 系统在词内折行）。
     */
    private fun observedPrefix(index: String, level: ConsoleLevel): String = "$index ${level.glyph} "

    /** 多行观测块（GNSS 1 行）：逐行 `NN XX ` 前缀 + 级别栏染色。 */
    private fun observedBlock(lines: List<String>, firstIndex: Int, level: ConsoleLevel): CharSequence {
        val out = SpannableStringBuilder()
        for ((offset, line) in lines.withIndex()) {
            if (offset > 0) out.append('\n')
            out.append(observedPrefix(consoleIndex(firstIndex + offset), level))
            val start = out.length - level.glyph.length - 1
            out.setSpan(
                ForegroundColorSpan(getColor(level.colorRes)),
                start,
                start + level.glyph.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            out.append(hangIndent(line, CONTINUATION_FIELD))
        }
        return out
    }

    /**
     * 观测**记录**（Hook 组，2026-10-10 真机复审修复）：一条记录**显式占两行**，绝不交给系统折行。
     *
     * ```
     * NN I  <head>                        ← 第 1 行：wireName + 命中/错误 计数（lab_group_line）
     *   · <install> · <gate>              ← 第 2 行：状态 token（lab_group_status_line，更暗）
     * ```
     *
     * 为什么必须显式两行：真机（1080px 屏，行宽实测 1030px）上「21 字符 wireName +
     * `未安装/未知` + `生效中`」的一条记录宽 1050–1107px > 1030px，**单行放不下**；
     * 而等宽控制台的 CJK 可在任意两字之间断行 ⇒ 复审看到「行末『生效』/ 下一行『中』」。
     * 拆成两行后每行都有余量（见 DESIGN 的字符数算式），且断点由本函数**显式**给出。
     * 第 2 行用 [R.color.tl_ink_sub]（次级文本，仍过 AA）。
     */
    private fun observedRecord(
        index: String,
        head: String,
        status: String,
        level: ConsoleLevel,
    ): CharSequence {
        val out = SpannableStringBuilder()
        val prefix = observedPrefix(index, level)
        out.append(prefix)
        val glyphStart = prefix.length - level.glyph.length - 1
        out.setSpan(
            ForegroundColorSpan(getColor(level.colorRes)),
            glyphStart,
            glyphStart + level.glyph.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        out.append(hangIndent(head, CONTINUATION_FIELD))
        out.append('\n').append(CONTINUATION_FIELD)
        val statusStart = out.length
        out.append(status)
        out.setSpan(
            ForegroundColorSpan(getColor(R.color.tl_ink_sub)),
            statusStart,
            out.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        return out
    }

    private fun leveledLine(
        prefix: String,
        text: String,
        level: ConsoleLevel,
        indent: String,
        fragments: List<Pair<String, Int>>,
    ): SpannableString {
        val body = hangIndent(text, indent)
        val raw = prefix + level.glyph + " " + body
        val spannable = SpannableString(raw)
        spannable.setSpan(
            ForegroundColorSpan(getColor(level.colorRes)),
            prefix.length,
            prefix.length + level.glyph.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        for ((fragment, color) in fragments) {
        }
        return spannable
    }

    /**
     * 内容里的显式换行 → 悬挂缩进（**仅当换行后紧跟非空白**）。
     * 预折行的续行自带缩进（`\n` 后是空格）⇒ 不重复加，避免缩进叠加。
     */
    private fun hangIndent(text: String, indent: String): String {
        if (indent.isEmpty() || !text.contains('\n')) return text
        val out = StringBuilder(text.length + indent.length * 4)
        for (i in text.indices) {
            out.append(text[i])
            if (text[i] == '\n' && i + 1 < text.length && !text[i + 1].isWhitespace()) out.append(indent)
        }
        return out.toString()
    }

    // ---------------------------------------------------------------- 控制台按字段折行（禁止词内断行）

    /**
     * 观测行按**字段边界**折行（2026-10-10 真机修复；2026-10-10 复审收口）。
     *
     * 背景（真机逐屏验收证据）：「03/04/05/06 行都把『生效中』拆成下一行的『效中』」——
     * 等宽控制台里 CJK 可在任意两字之间断行，字段被从中间劈开。
     * 规则：只在字段分隔符（[FIELD_BREAKS]：` · ` / `｜` / ` | `）处断；续行 =
     * [CONTINUATION_FIELD]（2 个等宽半角空格）+ 原分隔符；单字段仍宽于整行时整段保留
     * （**宁可溢出也不在词内断开**）。
     *
     * **复审修复（关键）**：可用宽 = 行宽 − **前缀实测宽** [prefix]（`NN XX ` 6 个半角位）。
     * 旧口径按整行宽折行、漏算前缀 ⇒ 折出来的「一行」渲染时比可用宽多 6 列（≈110px），
     * TextView 只能在词内折行（复审的「05 行末『生效』/ 下一行『中』」就是这样来的）。
     * 另留 [CONSOLE_WRAP_SAFETY] 余量吸收测量/渲染差异：宁可提前在字段边界折行，也不让系统在词内折。
     *
     * 宽度用与行同样式（typeface / textSize / letterSpacing）的 Paint **实测**，不做字符数估算。
     */
    private fun wrapFields(text: String, view: TextView, prefix: String = ""): String {
        if (text.isEmpty() || !FIELD_BREAKS.any { text.contains(it) }) return text
        val rowWidth = consoleRowWidthPx(view)
        if (rowWidth <= 0f) return text
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = view.typeface
            textSize = view.textSize
            letterSpacing = view.letterSpacing
        }
        val width = (rowWidth - paint.measureText(prefix)) * CONSOLE_WRAP_SAFETY
        if (width <= 0f) return text
        val segments = splitAtFieldBoundaries(text)
        if (segments.size <= 1) return text
        val out = StringBuilder(text.length + 16)
        out.append(segments[0])
        var lineWidth = paint.measureText(segments[0])
        for (i in 1 until segments.size) {
            val segment = segments[i]
            val segmentWidth = paint.measureText(segment)
            if (lineWidth + segmentWidth <= width) {
                out.append(segment)
                lineWidth += segmentWidth
            } else {
                out.append('\n').append(CONTINUATION_FIELD).append(segment)
                lineWidth = paint.measureText(CONTINUATION_FIELD) + segmentWidth
            }
        }
        return out.toString()
    }

    /**
     * 按字段分隔符切段：**每段保留其前置分隔符**（首段无前缀），故续行以「· 」/「｜」/「| 」起头，
     * 一个字都不删（诚实口径：只加换行与缩进，不改文案）。
     */
    private fun splitAtFieldBoundaries(text: String): List<String> {
        val out = mutableListOf<String>()
        var start = 0
        var i = 0
        while (i < text.length) {
            val marker = FIELD_BREAKS.firstOrNull { text.startsWith(it, i) }
            if (marker != null && i > start) {
                out.add(text.substring(start, i))
                start = i
                i += marker.length
            } else {
                i++
            }
        }
        out.add(text.substring(start))
        return out
    }

    /**
     * 行可用文本宽（px）：优先用已布局的行宽（match_parent 行，宽 = 控制台列宽 − body 内边距）；
     * 首帧尚未布局（width=0）时按「屏幕宽 − 控制台固定内边距 [CONSOLE_TEXT_INSET_DP]」估算
     * （控制台列横跨整屏：2dp 侧栏 + body 左右各 8dp；估算值与布局实测同源）。
     */
    private fun consoleRowWidthPx(view: TextView): Float {
        val laidOut = view.width - view.paddingStart - view.paddingEnd
        if (laidOut > 0) return laidOut.toFloat()
        val metrics = resources.displayMetrics
        return metrics.widthPixels - CONSOLE_TEXT_INSET_DP * metrics.density
    }

    private fun consoleIndex(value: Int): String = String.format(java.util.Locale.US, "%02d", value)

    /** 事件行写入：只在内容变化时写，变化即刷新时间列并给出「新行」反馈（）。 */
    private fun writeEventRow(
        view: TextView,
        text: String,
        level: ConsoleLevel,
        fragments: List<Pair<String, Int>> = emptyList(),
    ) {
        if (consoleLastText[view.id] == text) return
        consoleLastText[view.id] = text
        view.text = eventLine(text, level, fragments)
        onConsoleRowsChanged()
    }

    /** 轨迹范围行（无时点、无级别 → 序号列占位 `··` + `- `；纯模型固定界）；按字段预折行。 */
    private fun writeRangeRow(text: String) {
        val view = binding.txtRangeInfo
        val prefix = observedPrefix(OBSERVED_INDEX_PLACEHOLDER, ConsoleLevel.NONE)
        val wrapped = wrapFields(text, view, prefix)
        if (consoleLastText[view.id] == wrapped) return
        consoleLastText[view.id] = wrapped
        view.text = observedLine(OBSERVED_INDEX_PLACEHOLDER, wrapped, ConsoleLevel.NONE)
    }

    /** 地图/轨迹状态行（事件行，级别在写入点显式给出—— 级别指派表）。 */
    private fun writeMapStatus(text: String, level: ConsoleLevel) =
        writeEventRow(binding.txtMapStatus, text, level)

    /** 新行反馈：贴底自动滚动（脉冲/淡入/光标装饰已删除）。 */
    private fun onConsoleRowsChanged() {
        autoScrollConsole()
    }



    private fun tintBoardWord(spannable: SpannableString, raw: String, word: String, color: Int) {
        if (word.isEmpty()) return
        var from = 0
        while (true) {
            val hit = raw.indexOf(word, from)
            if (hit < 0) return
            spannable.setSpan(
                ForegroundColorSpan(color),
                hit,
                hit + word.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            from = hit + word.length
        }
    }

    /** risk/recovery 前缀染色（蓝；只染前缀，正文颜色不动）。 */
    private fun tintBoardPrefix(spannable: SpannableString, raw: String, prefixTemplate: String) {
        val prefix = prefixTemplate.substringBefore("%1\$s")
        if (prefix.isEmpty()) return
        var from = 0
        while (true) {
            val hit = raw.indexOf(prefix, from)
            if (hit < 0) return
            spannable.setSpan(
                ForegroundColorSpan(getColor(R.color.tl_console_info)),
                hit,
                hit + prefix.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            from = hit + prefix.length
        }
    }

    /**
     * 指标行写入：同值不重绘（≤10Hz 限流）；闪烁光标已删除（减少动态与可读性）。
     */
    private fun applyMatrixCursor(base: String) {
        if (lastMetricsBase == base) return
        lastMetricsBase = base
        binding.txtPlaybackMetrics.alpha = 1.0f
        binding.txtPlaybackMetrics.text = base
    }






    private fun errorTextRes(error: MapEditViewModel.InputError): Int = when (error) {
        MapEditViewModel.InputError.P_BASE -> R.string.error_invalid_p_base
        MapEditViewModel.InputError.LAPS -> R.string.error_invalid_laps
        MapEditViewModel.InputError.SEED -> R.string.error_invalid_seed
        MapEditViewModel.InputError.NONE -> R.string.map_hint_all_collected
    }

    // ---------------------------------------------------------------- 拟合预览 / 状态行

    /** 单步步进常量（与 ViewModel 伴生同源，避免散落魔法数）。 */
    private val panStep: Double get() = MapEditViewModel.PAN_STEP_M
    private val scaleStep: Double get() = MapEditViewModel.SCALE_STEP
    private val rotateStepDeg: Double get() = MapEditViewModel.ROTATE_STEP_DEG

    /** 跑道整体平移（米；东正北正）→ 重算拟合并刷新预览。 */
    private fun transformTrack(dEastM: Double, dNorthM: Double) {
        if (vm.panTrack(dEastM, dNorthM)) {
            refreshTrackUi()
            showTrackTransformed()
        } else {
            writeMapStatus(getString(R.string.track_transform_rejected), ConsoleLevel.WARN)
        }
    }

    /** 跑道整体缩放（a/R 同比例）。 */
    private fun scaleTrack(factor: Double) {
        if (vm.scaleTrack(factor)) {
            refreshTrackUi()
            showTrackTransformed()
        } else {
            writeMapStatus(getString(R.string.track_transform_rejected), ConsoleLevel.WARN)
        }
    }

    /** 跑道整体旋转（度；正=顺时针）。 */
    private fun rotateTrack(deltaDeg: Double) {
        if (vm.rotateTrack(deltaDeg)) {
            refreshTrackUi()
            showTrackTransformed()
        } else {
            writeMapStatus(getString(R.string.track_transform_rejected), ConsoleLevel.WARN)
        }
    }

    /** 变换后状态行：回显当前 a/R/方位（方位取模 180°——u 与 −u 描述同一条跑道）。 */
    private fun showTrackTransformed() {
        val model = (vm.tryFit() as? MapEditViewModel.FitPreview.Ready)?.model ?: return
        var heading = Math.toDegrees(kotlin.math.atan2(model.u.x, model.u.y))
        heading = ((heading % 180.0) + 180.0) % 180.0
        writeMapStatus(
            getString(
                R.string.track_transformed,
                String.format(java.util.Locale.US, "%.1f", model.a),
                String.format(java.util.Locale.US, "%.1f", model.r),
                String.format(java.util.Locale.US, "%.0f", heading),
            ),
            ConsoleLevel.INFO,
        )
    }

    /**
     * 载入预制跑道：六点整体替换当前点位，相机框住全图并刷新拟合预览。
     * [nameRes] 为 UI 展示名（双语字符串资源；坐标与几何在 [TrackPreset] 内，core 不依赖 res）。
     */
    private fun loadPreset(preset: TrackPreset, nameRes: Int) {
        val fit = vm.loadPreset(preset)
        refreshTrackUi()
        mapController?.fitCameraToBounds(
            (fit as? MapEditViewModel.FitPreview.Ready)?.centerlineWgs84 ?: vm.state.points,
        )
        when (fit) {
            is MapEditViewModel.FitPreview.Ready ->
                writeMapStatus(getString(R.string.preset_loaded, getString(nameRes)), ConsoleLevel.INFO)
            is MapEditViewModel.FitPreview.Rejected ->
                writeMapStatus(getString(R.string.map_hint_preview_failed, fit.message), ConsoleLevel.WARN)
            else -> writeMapStatus(getString(R.string.map_hint_all_collected), ConsoleLevel.INFO)
        }
    }

    private fun refreshTrackUi() {
        val titles = pointTitles()
        mapController?.renderPoints(vm.state.points, titles)
        when (val fit = vm.tryFit()) {
            is MapEditViewModel.FitPreview.Incomplete -> {
                mapController?.renderPreview(emptyList(), emptyList(), emptyList())
                val next = vm.state.points.size + 1
                writeMapStatus(
                    getString(R.string.map_hint_next_point, next, titles[next - 1]),
                    ConsoleLevel.INFO,
                )
            }
            is MapEditViewModel.FitPreview.Rejected -> {
                mapController?.renderPreview(emptyList(), emptyList(), emptyList())
                writeMapStatus(
                    getString(R.string.map_hint_preview_failed, fit.message),
                    ConsoleLevel.WARN,
                )
            }
            is MapEditViewModel.FitPreview.Ready -> {
                mapController?.renderPreview(
                    fit.centerlineWgs84, fit.edgePlusWgs84, fit.edgeMinusWgs84,
                )
                writeMapStatus(getString(R.string.map_hint_all_collected), ConsoleLevel.INFO)
            }
        }
    }

    /** p0..p5 标题（ 固定命名）。 */
    private fun pointTitles(): List<String> {
        val names = listOf(
            R.string.point_name_p0, R.string.point_name_p1, R.string.point_name_p2,
            R.string.point_name_p3, R.string.point_name_p4, R.string.point_name_p5,
        )
        return names.mapIndexed { i, res -> getString(R.string.point_title_format, i, getString(res)) }
    }

    /** 三项输入（p_base/laps/seed）即时校验提交；非法输入写状态行并返回 false。 */
    private fun applyInputsFromForm(): Boolean {
        val error = vm.applyInputs(
            binding.editPBase.text?.toString().orEmpty(),
            binding.editLaps.text?.toString().orEmpty(),
            binding.editSeed.text?.toString().orEmpty(),
        )
        if (error != MapEditViewModel.InputError.NONE) {
            writeMapStatus(getString(errorTextRes(error)), ConsoleLevel.ERROR)
            return false
        }
        return true
    }

    // ---------------------------------------------------------------- 导出（应用私有目录，无额外权限）

    private fun exportTrack(gpx: Boolean) {
        if (!applyInputsFromForm()) return
        val content = if (gpx) vm.buildGpx() else vm.buildGeoJson()
        if (content == null) {
            writeMapStatus(getString(R.string.error_no_track), ConsoleLevel.ERROR)
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
            writeMapStatus(getString(R.string.export_success, file.absolutePath), ConsoleLevel.INFO)
            Toast.makeText(this, R.string.export_success_toast, Toast.LENGTH_LONG).show()
        } catch (e: IOException) {
            writeMapStatus(getString(R.string.error_export_failed, e.message ?: name), ConsoleLevel.ERROR)
        }
    }

    // ---------------------------------------------------------------- 测试回放入口（）

    private fun startTestPlayback() {
        if (!applyInputsFromForm()) return
        val samples = vm.generateSamples()
        if (samples == null) {
            writeMapStatus(getString(R.string.error_no_track), ConsoleLevel.ERROR)
            return
        }
        // 权限门（）：缺运行时权限 → 引导 UI + 发起申请；模拟位置应用未选中 → 引导 UI（回放继续）。
        val gate = appContainer.permissionCoordinator.createGate(
            mockSelected = permissionGate?.mockSelected ?: false,
        )
        permissionGate = gate
        if (gate.state == PermissionGate.State.MISSING_PERMISSIONS) {
            writeMapStatus(getString(R.string.permission_guide_message), ConsoleLevel.WARN)
            permissionLauncher.launch(gate.missingPermissions.toTypedArray())
            return
        }
        if (gate.state == PermissionGate.State.MISSING_MOCK_SELECTION) {
            writeMapStatus(getString(R.string.mock_location_guide), ConsoleLevel.WARN)
            // 投机性引导已展示：若随后服务 ACTIVE 恢复推帧，renderPlaybackUi 经
            // mockGuideResolver 清除残留（R4 实证：r4-replay/r4-mock-recheck）。
            mockGuideResolver.onSpeculativeGuideShown()
        }
        val s = vm.state
        playbackRenderer?.showRoute(samples.map { LatLon(it.latitudeDeg, it.longitudeDeg) })
        appContainer.playbackEntry.startTestPlayback(samples, s.laps, s.pBaseSecPerKm, s.seed)
        if (gate.state == PermissionGate.State.READY) {
            writeMapStatus(getString(R.string.map_hint_playback_requested), ConsoleLevel.INFO)
        }
    }

    /** 暂停/继续（无活动会话：B8 ④ 可点、无异常、不起服务）。 */
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
     * 进程重建安全恢复（）：快照 → [PlaybackStateMachine.fromSnapshot] → **一律 PAUSED
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
            writeMapStatus(getString(R.string.playback_restored_paused), ConsoleLevel.INFO)
        } catch (e: IllegalArgumentException) {
            appContainer.snapshotStore.clear()
            writeMapStatus(getString(R.string.error_state_restore, e.message ?: "snapshot"), ConsoleLevel.ERROR)
        }
    }

    /** F10/：`repeatOnLifecycle(STARTED)` 收集 PlaybackBus（指标/按钮态/mock 引导/地图回放）。 */
    private fun collectPlaybackState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                appContainer.playbackBus.state.collect { ui -> renderPlaybackUi(ui) }
            }
        }
    }

    private fun renderPlaybackUi(ui: PlaybackUiState) {
        // 顶栏（LED / 状态词）与控制台动效都以这一帧的状态为准：先落状态，再写外观。
        lastPlaybackState = ui.state

        val base = getString(
            R.string.overlay_metrics_format,
            ui.currentLap,
            ui.totalLaps,
            PlaybackMetricsFormat.distanceKm(ui.distanceM),
            PlaybackMetricsFormat.pace(ui.paceSecPerKm),
            PlaybackMetricsFormat.cadence(ui.cadenceSpm),
        )
        // 指标行：同值不重绘（≤10Hz 限流）；文本口径不变，仍是 overlay_metrics_format 同源。
        applyMatrixCursor(base)
        applyTopBarState()
        binding.btnPause.setText(if (ui.state == PlaybackState.PAUSED) R.string.action_resume else R.string.action_pause)
        // 回放控制三键的图标随状态切换（DESIGN R3-2：两个独立字形，不存在合成资源名）。
        binding.btnPause.setCompoundDrawablesWithIntrinsicBounds(
            0,
            if (ui.state == PlaybackState.PAUSED) R.drawable.ic_ui_play else R.drawable.ic_ui_pause,
            0,
            0,
        )
        // mock 引导（ + R4 实证修复）：GUIDANCE 上升沿写 mock_location_guide；
        // 投机性残留（开始入口 MISSING_MOCK_SELECTION 已展示、随后服务 ACTIVE 恢复推帧）
        // 经 MockGuideResolver 清除回 map_hint_playback_requested；其余帧不写屏。
        // 试探结果回灌权限门（公开 API 无查询接口，由推帧 side-channel 驱动）。
        val guideKey = mockGuideResolver.onFrame(ui.mockGuidance, ui.sessionActive, ui.mockOutputActive)
        when (guideKey) {
            "mock_location_guide" -> writeMapStatus(getString(R.string.mock_location_guide), ConsoleLevel.WARN)
            "map_hint_playback_requested" ->
                writeMapStatus(getString(R.string.map_hint_playback_requested), ConsoleLevel.INFO)
        }
        if (ui.sessionActive) {
            permissionGate?.onMockSelectionChanged(!ui.mockGuidance)
        }
        if (ui.state == PlaybackState.COMPLETED && ui.sessionActive.not()) {
            writeMapStatus(getString(R.string.playback_completed_notice), ConsoleLevel.INFO)
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

        /** onSaveInstanceState 键：MapEditViewModel 快照字符串（B2/B3 同风格编码）。 */
        private const val STATE_SNAPSHOT = "map_edit_snapshot"

        /**
         * onSaveInstanceState 键：控制台是否已被用户「脱离贴底跟随」（[consoleUserDetached]）。
         * 转屏/重建后与 View 体系恢复的 scrollY 一起还原，避免把正在翻历史的用户拽回底部。
         */
        private const val STATE_CONSOLE_DETACHED = "console_user_detached"

        /** 无界面探针启动 extra（am start --ez lab_probe_start true；与按钮同语义）。 */

        /** 无界面探针停止 extra（am start --ez lab_probe_stop true；与按钮同语义）。 */

        /** 状态板整板折叠收起态行数（收起 maxLines=12+ellipsize=end，展开全文）。 */

        /** Hook 组**记录**数上限（恒 10 组；超限必须显式提示，绝不静默吞行）。
         *  一条记录显式占两行（计数行 + 状态子行）⇒ 视图 maxLines = 12 × 2 = 24（见 activity_main.xml）。 */
        private const val CONSOLE_GROUPS_MAX_LINES = 12

        // ---- 页序号 ----

        /** 页序号（页签顺序 = 编辑 / 实验 / 诊断 / 日志）。 */
        private const val PANEL_TRACK = 0
        private const val PANEL_LAB = 1
        private const val PANEL_MORE = 2
        private const val PANEL_CONSOLE = 3

        /** 界面时钟步长（ms；本机挂钟，非事件时刻）。 */
        private const val CLOCK_TICK_MS = 1000L

        /** 贴底判定的容差（dp，自动滚动）。 */
        private const val CONSOLE_STICKY_BOTTOM_DP = 24f

        // ---- 控制台列宽（列对齐靠字符数，不用 padding） ----

        /** 事件行续行缩进（等宽半角空格 = 8 + 1 + 2 + 1）。 */
        private const val CONTINUATION_EVENT = "            "

        /**
         * 观测行**字段折行**的续行悬挂缩进 = 2 个等宽半角空格（2026-10-10 真机修复口径）；
         * 其后保留原字段分隔符（`· ` / `｜` / ` | `）。单个字段仍宽于整行时的自然折行也用这个缩进。
         */
        private const val CONTINUATION_FIELD = "  "

        /**
         * 字段折行的宽度余量（2026-10-10 真机复审修复）：可用宽 = (行宽 − 前缀实测宽) × 本系数。
         * Paint 实测与渲染器之间可能差半个字（字距/取整/回退字形），留 3% 余量（1030px 行 ≈ 31px）
         * ⇒ 宁可提前在**字段边界**折行，也绝不让系统在**词内**折行。
         */
        private const val CONSOLE_WRAP_SAFETY = 0.97f

        /** 控制台字段分隔符（与资源文案同源：lab_group_line / lab_gnss_step_line / range_info）。 */
        private val FIELD_BREAKS = listOf(" · ", "｜", " | ")

        /**
         * 控制台行的固定横向内边距（dp）：2dp 侧栏 + body 左右各 8dp。
         * 仅用于首帧（行尚未布局、width=0）时的宽度估算；布局后一律用行实测宽。
         */
        private const val CONSOLE_TEXT_INSET_DP = 18f

        /** 观测行序号占位（轨迹范围行无序号；`··` 为既有上线字符）。 */
        private const val OBSERVED_INDEX_PLACEHOLDER = "··"

        /** GNSS / 计步行在统一序号列里的位置（板 01–09 之后）。 */
        private const val GNSS_STEP_INDEX = 11
    }

    /** 回放状态词（5 值；顶栏全局恒写，不随页签停更）。 */
    private fun stateTextRes(state: PlaybackState): Int = when (state) {
        PlaybackState.IDLE -> R.string.matrix_state_idle
        PlaybackState.PLAYING -> R.string.matrix_state_playing
        PlaybackState.PAUSED -> R.string.matrix_state_paused
        PlaybackState.STOPPED -> R.string.matrix_state_stopped
        PlaybackState.COMPLETED -> R.string.matrix_state_completed
    }
}
