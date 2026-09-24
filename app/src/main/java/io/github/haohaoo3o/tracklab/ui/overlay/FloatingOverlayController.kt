package io.github.haohaoo3o.tracklab.ui.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import io.github.haohaoo3o.tracklab.R

/**
 * 可拖动悬浮窗 HUD（docs/CONTRACTS.md §8）：『测试/模拟定位中』指标
 * （当前圈数/距离/配速/步频）+ 暂停/继续 + 停止。
 *
 * - 悬浮窗权限（SYSTEM_ALERT_WINDOW）**手工授予**（Settings.ACTION_MANAGE_OVERLAY_PERMISSION），
 *   未授予时 [show] 返回 false——静默跳过 HUD（通知与主界面仍在）；
 * - **线程契约**：全部 View/WindowManager 突变**恒在主（Looper）线程执行**，
 *   经 [MainThreadMarshal] 封送——调用方在任意线程调用 [show]/[update]/[updateMetrics]/
 *   [setPaused]/[hide] 均安全。后台线程 `WindowManager.addView` 会因 ViewRootImpl 挂主线程
 *   Handler 直接 `RuntimeException` 崩溃、跨线程 setText 抛 `CalledFromWrongThreadException`，
 *   故创建/更新/移除一律封送（[show]/[hide] 同步封送保返回值/清理时序；更新恒异步 FIFO 保序）。
 *   拖动触摸回调由 View 体系派发（恒主线程）直接改布局，无需再封送；
 * - 拖动：按住 HUD 主体（指标区/空白）拖动整窗，落点经 [OverlayGeometry.clampWindowPosition]
 *   钳回视口（含 margin）；按钮点击不触发拖动（touch slop 判别）；
 * - 完整清理：[hide] 移除视图并释放引用，可重复调用。
 *
 * 只做展示与回调，不持有服务逻辑（暂停/停止经 [Callbacks] 上抛）。
 */
class FloatingOverlayController(
    private val context: Context,
    private val callbacks: Callbacks,
) {

    /** HUD 动作回调（上抛给服务统一走状态机）。 */
    interface Callbacks {
        /** 暂停/继续切换。 */
        fun onPauseResume()

        /** 停止。 */
        fun onStop()
    }

    private val windowManager =
        context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 主线程封送器（判据/投递口 = Looper 主线程；语义与约束见 [MainThreadMarshal]）。 */
    private val marshal = MainThreadMarshal(
        isUiThread = { Looper.myLooper() == Looper.getMainLooper() },
        postToUiThread = { mainHandler.post(it) },
    )

    /** HUD 根视图（写=主线程；[isShowing] 跨线程读 → volatile）。 */
    @Volatile
    private var root: View? = null

    // 以下仅主线程触碰（全部经封送体/触摸回调）。
    private var metricsView: TextView? = null
    private var pauseButton: Button? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    /** HUD 是否在显示。 */
    val isShowing: Boolean get() = root != null

    /**
     * 显示 HUD。无悬浮窗权限 / WindowManager 拒绝 → 返回 false（不崩溃，HUD 缺席由通知兜底）。
     * 任意线程可调（同步封送主线程执行）；**调用方不得持锁调用**（见 [MainThreadMarshal.sync]）。
     */
    fun show(): Boolean = marshal.sync { showOnMainThread() }

    /**
     * 整帧更新（指标行 + 暂停/继续态）——一次封送原子应用，避免拆两条投递被其他线程的
     * 更新插队写花。任意线程可调（异步 FIFO 投递主线程）。
     */
    fun update(metricsText: String, paused: Boolean) = marshal.async {
        applyOnMainThread(metricsText, paused)
    }

    /** 更新指标行（当前圈数/距离/配速/步频，预格式化文本）。任意线程可调（异步 FIFO）。 */
    fun updateMetrics(text: String) = marshal.async { setMetricsOnMainThread(text) }

    /** 暂停/继续按钮文案切换（[paused] true → 显示『继续』）。任意线程可调（异步 FIFO）。 */
    fun setPaused(paused: Boolean) = marshal.async { setPausedOnMainThread(paused) }

    /** 移除 HUD（完整清理；可重复调用）。任意线程可调（同步封送，返回即已移除）。 */
    fun hide() = marshal.sync { hideOnMainThread() }

    // ---------------------------------------------------------------- 主线程执行体（View/WindowManager 突变只落这里与触摸回调）

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    private fun showOnMainThread(): Boolean {
        if (root != null) return true
        if (!Settings.canDrawOverlays(context)) return false
        val view = LayoutInflater.from(context).inflate(R.layout.view_floating_control, null)
        val density = context.resources.displayMetrics.density
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // WindowManager x/y 单位 px（dp 常量 × density 换算）。
            x = (INITIAL_X_DP * density).toInt()
            y = (INITIAL_Y_DP * density).toInt()
        }
        attachDragHandler(view, params)
        view.findViewById<Button>(R.id.btn_overlay_pause).setOnClickListener { callbacks.onPauseResume() }
        view.findViewById<Button>(R.id.btn_overlay_stop).setOnClickListener { callbacks.onStop() }
        return try {
            windowManager.addView(view, params)
            root = view
            metricsView = view.findViewById(R.id.txt_overlay_metrics)
            pauseButton = view.findViewById(R.id.btn_overlay_pause)
            layoutParams = params
            true
        } catch (e: WindowManager.BadTokenException) {
            false
        } catch (e: IllegalStateException) {
            false
        }
    }

    private fun applyOnMainThread(metricsText: String, paused: Boolean) {
        setMetricsOnMainThread(metricsText)
        setPausedOnMainThread(paused)
    }

    private fun setMetricsOnMainThread(text: String) {
        metricsView?.text = text
    }

    private fun setPausedOnMainThread(paused: Boolean) {
        pauseButton?.setText(if (paused) R.string.action_resume else R.string.action_pause)
        pauseButton?.contentDescription =
            context.getString(if (paused) R.string.cd_overlay_resume else R.string.cd_overlay_pause)
    }

    private fun hideOnMainThread() {
        val view = root ?: return
        root = null
        metricsView = null
        pauseButton = null
        layoutParams = null
        try {
            windowManager.removeView(view)
        } catch (e: IllegalArgumentException) {
            // 视图已移除（重复清理）——忽略，清理必须幂等。
        }
    }

    /**
     * 拖动处理：HUD 主体按下记录锚点，移动超 touch slop 开始拖动（[OverlayGeometry.clampWindowPosition]
     * 钳位）；按钮的点击由子 View 消费，不进入拖动。
     * （触摸事件由 View 体系统一在主线程派发，回调内直接改布局，无跨线程触碰。）
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachDragHandler(view: View, params: WindowManager.LayoutParams) {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var startX = 0
        var startY = 0
        var startRawX = 0f
        var startRawY = 0f
        var dragging = false
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    startRawX = event.rawX
                    startRawY = event.rawY
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startRawX
                    val dy = event.rawY - startRawY
                    if (!dragging && (dx * dx + dy * dy) > slop * slop) dragging = true
                    if (dragging) {
                        val metrics = context.resources.displayMetrics
                        val density = metrics.density
                        val clamped = OverlayGeometry.clampWindowPosition(
                            x = ((startX + dx) / density).toInt(),
                            y = ((startY + dy) / density).toInt(),
                            windowWidthDp = (view.width / density).toInt().coerceAtLeast(1),
                            windowHeightDp = (view.height / density).toInt().coerceAtLeast(1),
                            viewportWidthDp = (metrics.widthPixels / density).toInt(),
                            viewportHeightDp = (metrics.heightPixels / density).toInt(),
                        )
                        // clamp 以 dp 计，WindowManager x/y 以 px 计（dp→px 换算回写）。
                        params.x = (clamped.x * density).toInt()
                        params.y = (clamped.y * density).toInt()
                        windowManager.updateViewLayout(view, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) v.performClick()
                    true
                }
                else -> false
            }
        }
    }

    companion object {

        /** HUD 初始落点（dp，左上原点）。 */
        private const val INITIAL_X_DP = 16
        private const val INITIAL_Y_DP = 96
    }
}
