package io.github.haohaoo3o.tracklab.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.haohaoo3o.tracklab.MainActivity
import io.github.haohaoo3o.tracklab.R
import io.github.haohaoo3o.tracklab.TrackLabApp
import io.github.haohaoo3o.tracklab.core.model.TrackSample
import io.github.haohaoo3o.tracklab.core.motion.MotionContracts
import io.github.haohaoo3o.tracklab.device.LocationManagerTestLocationSink
import io.github.haohaoo3o.tracklab.device.TestLocationOutput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.floor

/**
 * 前台回放服务（Manifest：`foregroundServiceType="location"`）。**七项**（docs/CONTRACTS.md §7）：
 *
 * ① onCreate 建 NotificationChannel（minSdk 26），channel id = [ServiceContracts.NOTIFICATION_CHANNEL_ID]；
 * ② onStartCommand **同步路径第一句 startForeground**（ServiceCompat；Q+ 传
 *    [ServiceContracts.FOREGROUND_SERVICE_TYPE_LOCATION]），预计算放后台协程；
 * ③ 通知动作 PendingIntent **必带** [ServiceContracts.FLAG_IMMUTABLE]；
 * ④ 返回 **START_NOT_STICKY**（值 [ServiceContracts.START_NOT_STICKY_VALUE]）；
 * ⑤ 快照写时机 = **每次状态迁移 + 每 25 样本节流**（[ServiceContracts.SNAPSHOT_THROTTLE_SAMPLES]），
 *    Stop/完成即 clear（[SnapshotStore.clear]）；
 * ⑥ 通知常驻『**测试/模拟定位中**』+ HUD + 暂停/继续/停止；
 * ⑦ onDestroy 完整清理（协程/通知/注册：测试提供者移除、悬浮窗移除、会话暂存清除）。
 *
 * 双出口（需求固定项）：**输出一** 应用内地图回放——每帧经 [PlaybackBus]发布，
 * MainActivity `repeatOnLifecycle(STARTED)` 收集后由 PlaybackRenderer 渲染；
 * **输出二** 官方 Mock Location——[TestLocationOutput] 经 [LocationManagerTestLocationSink]
 * （LocationManager 测试提供者官方 API）输出；未在开发者选项选中本应用时 SecurityException →
 * 显式引导态（`mock_location_guide`），绝不绕过。
 *
 * 状态推进：[PlaybackStateMachine]（§7.1 纯 Kotlin）；逐样本 Δt=0.5s 实时步进
 *（[MotionContracts.DELTA_T_SEC]）。进程重建安全恢复：快照仅恢复累计值并置 PAUSED，
 * START_NOT_STICKY 不自动重建服务、不自动续跑。
 *
 * **线程模型**：预计算/回放步在 `Dispatchers.Default`，动作处理（通知
 * PendingIntent / HUD 回调）在主线程——两者并发触达同一状态。口径：
 * ① HUD（悬浮窗）创建/更新/移除一律经 [FloatingOverlayController] 主线程封送（后台线程
 * `WindowManager.addView` 直接 RuntimeException 崩溃、跨线程 setText 抛
 * CalledFromWrongThreadException）；
 * ② 共享状态（machine/output/session 字段）以服务实例锁（`synchronized(this)` 步进区/动作区，
 * [persistSnapshot]/[throttleSnapshot]/[uiState] 同锁）+ 字段 volatile 串行化——回放步
 * （查索引/推帧/推进）在锁内原子执行，与暂停/停止/完成互斥：暂停落地后不再推帧/推进/发布，
 * [PlaybackStateMachine]/[TestLocationOutput] 内部锁另封快照撕裂与 emit 闸门。
 */
class PlaybackForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var playJob: Job? = null

    @Volatile
    private var machine = PlaybackStateMachine()

    @Volatile
    private var samples: List<TrackSample> = emptyList()

    @Volatile
    private var laps: Int = 0

    @Volatile
    private var lapLengthM: Double = 0.0

    @Volatile
    private var sessionActive: Boolean = false

    @Volatile
    private var lastSnapshotSampleIndex: Int = 0

    @Volatile
    private var output: TestLocationOutput? = null

    @Volatile
    private var overlay: FloatingControlHost? = null

    private val appContainer get() = (application as TrackLabApp).appContainer

    // ---------------------------------------------------------------- 生命周期

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        overlay = FloatingControlHost()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 同步路径第一句 startForeground（Q+ 传 FOREGROUND_SERVICE_TYPE_LOCATION）。
        startForegroundCompat()
        // 预计算/会话装载放后台协程。
        when (intent?.action) {
            ACTION_START -> handleStart()
            ACTION_PAUSE -> handlePause()
            ACTION_RESUME -> handleResume()
            ACTION_STOP -> handleStop()
            else -> if (!sessionActive) stopAndCleanup()
        }
        // 绝不自动重建（值契约 START_NOT_STICKY == ServiceContracts.START_NOT_STICKY_VALUE=2，
        // 由 PlaybackStateMachineTest 钉死；此处返回平台常量以满足 int-def 检查）。
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // 完整清理（协程/通知/注册）。
        playJob?.cancel()
        scope.cancel()
        // 与在飞回放步（锁内 emit）互斥后关闭出口，避免 remove/set 交错。
        runCatching { synchronized(this) { output?.stop() } }
        output = null
        overlay?.hide()
        overlay = null
        PlaybackSessionStore.clear()
        sessionActive = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        NotificationManagerCompat.from(this).cancel(ServiceContracts.NOTIFICATION_ID)
        publish(null)
        super.onDestroy()
    }

    // ---------------------------------------------------------------- startForeground（第一句的实现体）

    private fun startForegroundCompat() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceContracts.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            ServiceContracts.NOTIFICATION_ID,
            buildNotification(paused = machine.state == PlaybackState.PAUSED),
            type,
        )
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            ServiceContracts.NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { setShowBadge(false) }
        manager.createNotificationChannel(channel)
    }

    // ---------------------------------------------------------------- 动作入口（通知 PendingIntent / HUD / 主界面同源）

    private fun handleStart() {
        scope.launch {
            val session = PlaybackSessionStore.peek()
            if (session == null || session.samples.isEmpty()) {
                stopAndCleanup()
                return@launch
            }
            // 预计算（后台协程）：会话装载 + 圈长估算。
            val totalRunM = session.samples.sumOf { it.speedMps * MotionContracts.DELTA_T_SEC }
            val lapLen = if (session.laps > 0) totalRunM / session.laps else 0.0

            // 输出二：官方测试提供者（SecurityException → 显式引导态，绝不绕过）。
            val out = TestLocationOutput(
                appContainer.testLocationSink,
                LocationManagerTestLocationSink.defaultProviderName(),
            )
            // 会话装载 + 状态机/出口安装 + START 同锁原子落地（与动作处理/回放步互斥）。
            synchronized(this@PlaybackForegroundService) {
                output?.stop()
                output = null
                samples = session.samples
                laps = session.laps
                lapLengthM = lapLen
                machine = PlaybackStateMachine()
                lastSnapshotSampleIndex = 0
                sessionActive = true
                output = out
                out.start()
                machine.onEvent(PlaybackEvent.START)
            }
            persistSnapshot()
            // HUD 创建封送主线程（后台线程 addView 会崩）；此处不持任何锁。
            overlay?.show()
            publish(null)
            startLoop()
        }
    }

    private fun handlePause() {
        // 回归（暂停后多发一帧）：『关 mock 出口 + 迁 PAUSED』同锁原子执行且**先关出口**——
        // 与回放步（锁内 emit）互斥：本块返回后任何 emit 都不再推帧（TestLocationOutput 闸门），
        // 在飞步的 advance 亦被拒（非 PLAYING 不推进）。
        val accepted = synchronized(this) {
            if (machine.state != PlaybackState.PLAYING) return
            output?.setOutputEnabled(false)
            machine.onEvent(PlaybackEvent.PAUSE)
        }
        if (accepted) {
            // 状态迁移写快照 + 刷新通知/HUD（publish 的 HUD 更新经主线程封送）。
            persistSnapshot()
            publish(null)
            playJob?.cancel()
            playJob = null
            refreshNotification()
        }
    }

    private fun handleResume() {
        // 与 handlePause 同口径：『迁 RESUME + 开 mock 出口』同锁原子执行（回放循环随后才启动）。
        val accepted = synchronized(this) {
            if (machine.state != PlaybackState.PAUSED) return
            output?.setOutputEnabled(true)
            machine.onEvent(PlaybackEvent.RESUME)
        }
        if (accepted) {
            persistSnapshot()
            publish(null)
            refreshNotification()
            startLoop()
        }
    }

    private fun handleStop() {
        synchronized(this) { machine.onEvent(PlaybackEvent.STOP) }
        appContainer.snapshotStore.clear()
        stopAndCleanup()
    }

    private fun handleComplete() {
        synchronized(this) { machine.onEvent(PlaybackEvent.COMPLETE) }
        appContainer.snapshotStore.clear()
        stopAndCleanup()
    }

    // ---------------------------------------------------------------- 回放循环（Δt=0.5s 实时步进）

    private fun startLoop() {
        playJob?.cancel()
        playJob = scope.launch {
            while (isActive && machine.state == PlaybackState.PLAYING) {
                // 回放步（查索引/推帧/推进）在服务锁内原子执行——与暂停/停止/完成互斥：
                // 暂停落地后不再推帧/推进（回归）。
                var completed = false
                val stepped: TrackSample? = synchronized(this@PlaybackForegroundService) {
                    if (machine.state != PlaybackState.PLAYING) {
                        null
                    } else {
                        val index = machine.sampleIndex
                        if (index >= samples.size) {
                            completed = true
                            null
                        } else {
                            val sample = samples[index]
                            // 取消检查前移（协作式取消）：cancel 落地后在飞步不再推帧（机制 (a)）。
                            ensureActive()
                            output?.emit(sample)
                            // advance 仅 PLAYING 生效；已迁出 PLAYING（返回 false）时不推进、
                            // 不发布——累计与发布保持一致，绝不出现暂停后多发的帧。
                            if (machine.advance(
                                    sample.speedMps * MotionContracts.DELTA_T_SEC,
                                    SAMPLE_STEP_MS,
                                )
                            ) {
                                sample
                            } else {
                                null
                            }
                        }
                    }
                }
                if (completed) {
                    handleComplete()
                    break
                }
                if (stepped == null) break // 暂停/停止已落地（或推进被拒）——退出循环
                publish(stepped)
                throttleSnapshot()
                delay(SAMPLE_STEP_MS)
            }
        }
    }

    // ---------------------------------------------------------------- 快照（迁移 + 每 25 样本节流；Stop/完成即 clear）

    /** 每次状态迁移写快照（与回放步同服务锁——lastSnapshotSampleIndex 复合读改写不撕裂）。*/
    @Synchronized
    private fun persistSnapshot() {
        appContainer.snapshotStore.save(machine.encode())
        lastSnapshotSampleIndex = machine.sampleIndex
    }

    @Synchronized
    private fun throttleSnapshot() {
        val throttle = ServiceContracts.SNAPSHOT_THROTTLE_SAMPLES
        if (machine.sampleIndex / throttle > lastSnapshotSampleIndex / throttle) {
            persistSnapshot()
        }
    }

    // ---------------------------------------------------------------- 通知 + HUD + 发布

    private fun refreshNotification() {
        // POST_NOTIFICATIONS（API 33+ 运行时）未授予时通知不可见——降级口径见
        // docs/permissions-android13-miui.md，不崩溃（显式检查同时满足 lint MissingPermission）。
        val canNotify = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!canNotify) return
        NotificationManagerCompat.from(this)
            .notify(ServiceContracts.NOTIFICATION_ID, buildNotification(machine.state == PlaybackState.PAUSED))
    }

    private fun buildNotification(paused: Boolean): Notification {
        val ui = uiState(null)
        val builder = NotificationCompat.Builder(this, ServiceContracts.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            // 常驻『测试/模拟定位中』（合规文案，任何 locale 含"测试"）。
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSubText(metricsLine(ui))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPendingIntent())
            .addAction(
                0,
                getString(if (paused) R.string.action_resume else R.string.action_pause),
                actionPendingIntent(if (paused) ACTION_RESUME else ACTION_PAUSE, REQ_PAUSE),
            )
            .addAction(0, getString(R.string.action_stop), actionPendingIntent(ACTION_STOP, REQ_STOP))
        return builder.build()
    }

    /** 通知动作 PendingIntent 必带 FLAG_IMMUTABLE（值 = ServiceContracts.FLAG_IMMUTABLE=0x04000000，
     *  值契约由 PlaybackStateMachineTest 钉死；此处用平台常量满足 int-def 检查）。 */
    private fun actionPendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, PlaybackForegroundService::class.java).setAction(action)
        return PendingIntent.getService(this, requestCode, intent, PendingIntent.FLAG_IMMUTABLE)
    }

    private fun contentPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(this, REQ_CONTENT, intent, PendingIntent.FLAG_IMMUTABLE)
    }

    private fun metricsLine(ui: PlaybackUiState): String = getString(
        R.string.overlay_metrics_format,
        ui.currentLap,
        ui.totalLaps,
        PlaybackMetricsFormat.distanceKm(ui.distanceM),
        PlaybackMetricsFormat.pace(ui.paceSecPerKm),
        PlaybackMetricsFormat.cadence(ui.cadenceSpm),
    )

    private fun publish(sample: TrackSample?) {
        val ui = uiState(sample)
        appContainer.playbackBus.publish(ui)
        // HUD 更新经 FloatingOverlayController 主线程封送（任意线程调用安全）。
        overlay?.update(ui)
    }

    /** 组装展示态（同服务锁读取 machine/output/session 字段——并发下不撕裂）。 */
    @Synchronized
    private fun uiState(sample: TrackSample?): PlaybackUiState {
        val currentLap = when {
            lapLengthM <= 0.0 || laps <= 0 || machine.sampleIndex == 0 -> 0
            else -> (floor(machine.distanceM / lapLengthM).toInt() + 1).coerceIn(1, laps)
        }
        return PlaybackUiState(
            state = machine.state,
            sessionActive = sessionActive,
            sampleIndex = machine.sampleIndex,
            totalSamples = samples.size,
            totalLaps = laps,
            currentLap = currentLap,
            distanceM = machine.distanceM,
            paceSecPerKm = sample?.paceSecPerKm ?: 0.0,
            cadenceSpm = sample?.cadenceSpm ?: 0.0,
            latitudeDeg = sample?.latitudeDeg,
            longitudeDeg = sample?.longitudeDeg,
            mockOutputActive = output?.outputEnabled == true,
            mockGuidance = output?.needsGuidance() == true,
        )
    }

    // ---------------------------------------------------------------- 清理（Stop / 完成）

    private fun stopAndCleanup() {
        playJob?.cancel()
        playJob = null
        // 与在飞回放步（锁内 emit）互斥后关闭出口；随后 hide（主线程封送）在无锁区调用。
        runCatching { synchronized(this) { output?.stop() } }
        output = null
        overlay?.hide()
        sessionActive = false
        publish(null)
        stopSelf()
    }

    /** HUD 托管（悬浮窗回调 → 服务动作；FloatingOverlayController 生命周期 = 服务）。
     *  控制器全部 View/WindowManager 突变封送主线程——本类可在任意线程调用。 */
    private inner class FloatingControlHost : io.github.haohaoo3o.tracklab.ui.overlay.FloatingOverlayController.Callbacks {

        @Volatile
        private var controller: io.github.haohaoo3o.tracklab.ui.overlay.FloatingOverlayController? = null

        fun show() {
            if (controller == null) {
                controller = io.github.haohaoo3o.tracklab.ui.overlay.FloatingOverlayController(
                    this@PlaybackForegroundService,
                    this,
                )
            }
            controller?.show()
        }

        fun update(ui: PlaybackUiState) {
            val c = controller ?: return
            if (!c.isShowing) return
            // 整帧一次封送（指标 + 暂停态）——拆两条投递会被并发线程的更新插队写花。
            c.update(metricsLine(ui), ui.state == PlaybackState.PAUSED)
        }

        fun hide() {
            controller?.hide()
            controller = null
        }

        override fun onPauseResume() {
            if (machine.state == PlaybackState.PLAYING) handlePause() else handleResume()
        }

        override fun onStop() {
            handleStop()
        }
    }

    companion object {

        /** 动作：开始（携带会话见 PlaybackSessionStore）。 */
        const val ACTION_START = "io.github.haohaoo3o.tracklab.action.START"

        /** 动作：暂停。 */
        const val ACTION_PAUSE = "io.github.haohaoo3o.tracklab.action.PAUSE"

        /** 动作：继续。 */
        const val ACTION_RESUME = "io.github.haohaoo3o.tracklab.action.RESUME"

        /** 动作：停止。 */
        const val ACTION_STOP = "io.github.haohaoo3o.tracklab.action.STOP"

        /** 逐样本实时步长（ms）＝ Δt=0.5s（§3 全文钉死，由 MotionContracts 派生）。 */
        val SAMPLE_STEP_MS: Long = (MotionContracts.DELTA_T_SEC * 1000).toLong()

        private const val REQ_PAUSE = 1
        private const val REQ_STOP = 2
        private const val REQ_CONTENT = 3
    }
}
