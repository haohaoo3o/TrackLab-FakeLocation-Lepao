package io.github.haohaoo3o.tracklab.ui.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.Button
import android.widget.TextView
import io.github.haohaoo3o.tracklab.R
import io.github.haohaoo3o.tracklab.ui.motion.ReducedMotion
import kotlin.math.roundToInt

/**
 * 可拖动悬浮窗 HUD（F6 ⑥ / 工程契约 、设计资产 ）：
 * TrackLab v2 **玻璃 HUD**——① 跨窗高斯模糊（API 31+ 且系统允许时给窗口开
 * `FLAG_BLUR_BEHIND` + `setBlurBehindRadius`）**默认关闭**（开关 [BLUR_BEHIND_ENABLED]；
 * 真机实证它在 MIUI 上让整屏发虚，见该常量注释与类尾）；② 玻璃底**单套** `v2_glass_hud`
 * （实底 alpha 199/255 = 0.7804 + 自上而下轻遮罩，真的透底）；③ 发丝双描边与顶部 12dp 高光。
 * 展示指标（**主行 = 圈 + 距离**〔宽度实算见 `setMetricsOnMainThread`〕，**第二行 4 格 = 圈 /
 * 距离 / 配速 / 步频全量**）+ 暂停/继续 + 停止 + 折叠。
 *
 * - 悬浮窗权限（SYSTEM_ALERT_WINDOW）**手工授予**（Settings.ACTION_MANAGE_OVERLAY_PERMISSION），
 *   未授予时 [show] 返回 false——静默跳过 HUD（通知与主界面仍在），如实记录 notCovered；
 * - **线程契约（回归：评审问题 1）**：全部 View/WindowManager 突变**恒在主（Looper）线程执行**，
 *   经 [MainThreadMarshal] 封送——调用方在任意线程调用 [show]/[update]/[updateMetrics]/
 *   [setPaused]/[hide]/[setExpanded] 均安全。后台线程 `WindowManager.addView` 会因 ViewRootImpl
 *   挂主线程 Handler 直接 `RuntimeException` 崩溃、跨线程 setText 抛 `CalledFromWrongThreadException`，
 *   故创建/更新/移除一律封送（[show]/[hide] 同步封送保返回值/清理时序；更新恒异步 FIFO 保序）。
 *   窗口属性（模糊/玻璃/窗宽）只在 `showOnMainThread()` 内设置；
 *   渐变/呼吸/折叠三类动画的创建与取消也只在 `*OnMainThread` 执行体内，绝无跨线程触碰；
 * - **模糊可达条件（实读平台源码，非猜测）**：`Build.VERSION.SDK_INT >= 31` 且
 *   `WindowManager.isCrossWindowBlurEnabled()`（AOSP android-15.0.0_r1 `core/java/android/view/
 *   WindowManager.java:1790`：跨窗模糊在 `ro.surface_flinger.supports_background_blur` 为假、
 *   省电模式、`Settings.Global.DISABLE_WINDOW_BLURS`、tunnel mode、临界热状态任一为真时关闭）；
 *   半径用 `setBlurBehindRadius(px)`（同文件 :5048），**且必须同时置 `FLAG_BLUR_BEHIND`**
 *   （同文件 :5031「Requires {@link #FLAG_BLUR_BEHIND} to be set」+ `WindowState.shouldDrawBlurBehind()`
 *   只在 `(mAttrs.flags & FLAG_BLUR_BEHIND) != 0 && mBlurController.getBlurEnabled()` 时成立，
 *   该常量在 API 35 stub 中**未标 @Deprecated**——`javap -v` 实测）；
 *   不可用（**开关关闭（出厂默认）** / 低版本 / 系统关模糊 / OEM 无 GPU 支持 / addView 被拒）
 *   一律**静默走无模糊外观**——玻璃底是**同一套完整外观**（20dp 圆角 + 顶部高光 + 双发丝），
 *   不是"关掉模糊的裸框"，且绝不抛异常；
 * - 拖动：按住 HUD 主体（指标区/空白）拖动整窗，落点经 [OverlayGeometry.clampWindowPosition]
 *   钳回视口（含 margin）；按钮点击不触发拖动（touch slop 判别）；
 * - **初始落点（2026-10-10 真机复审问题 1 修复）**：[showOnMainThread] 在 addView 之后按实测尺寸
 *   经 [OverlayGeometry.initialWindowPosition] 取「水平居中、左右边距相等」的落点——1080×2400 @440dpi
 *   上窗宽 1036px、x = 22px ⇒ 左右各 22px = 8dp（修复前是固定 x = 16dp=44px 且不钳位，
 *   `44 + 1037 = 1081 > 1080` ⇒ 右边距 0、左 43px 不对称）；窗宽 px 换算同步改为向下取整（见 show 内注释）；
 * - 完整清理（F6 ⑦）：[hide] 取消**全部**动画（出现淡入 / 呼吸光 / 折叠 / 指标脉冲）并置空引用，
 *   再移除视图，可重复调用（幂等）。
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

    /** 曲线令牌（DESIGN ：只有两条；PathInterpolator = API 21+ 平台件，非 Gradle 依赖）。 */
    private val easeEnter = PathInterpolator(0f, 0f, 0.2f, 1f)
    private val easeExit = PathInterpolator(0.4f, 0f, 1f, 1f)

    /** HUD 根视图（写=主线程；[isShowing] 跨线程读 → volatile）。 */
    @Volatile
    private var root: View? = null

    // 以下仅主线程触碰（全部经封送体/触摸回调）。
    private var metricsView: TextView? = null
    private var pauseButton: Button? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var foldButton: Button? = null
    private var gridWrap: View? = null
    private var ledView: View? = null
    private var gridCircle: TextView? = null
    private var gridDistance: TextView? = null
    private var gridPace: TextView? = null
    private var gridCadence: TextView? = null
    private var lastPaused: Boolean = false
    private var lastMetrics: String = ""

    /**
     * 上一次收到的指标串（**剥后缀后的整串**，含配速/步频）——用于判断"数字变了"才播脉冲
     * （不重复播同串）。注意不是主行可见串：主行只放前两格，但配速/步频变化同样算"数据更新"，
     * 仍应给主行一次脉冲（脉冲语义 = 新数据，不是"文本变了"）。
     */
    private var shownMetrics: String = ""

    /**
     * 跨窗模糊能力缓存（**只探测一次**；null = 尚未探测）。
     * 只在 `showOnMainThread()` 内读写（恒主线程），失败/被拒也缓存，避免每次 show 都重探。
     */
    private var blurCapability: Boolean? = null

    /** 呼吸光（仅 PAUSED 运行 alpha 0.6→1.0、周期 1600ms；他态 cancel；hide 幂等清理 cancel 置空）。 */
    private var breathAnimator: ValueAnimator? = null

    /** 折叠/展开动效。 */
    private var foldAnimator: ValueAnimator? = null

    /** 窗口出现淡入（160ms；hide cancel 置空——绝不让动画持有已 detach 的 View）。 */
    private var appearAnimator: ValueAnimator? = null

    /** 指标数字变化脉冲（160ms alpha 0.62→1.0；下次变化 cancel 重放、hide cancel 置空）。 */
    private var metricsPulseAnimator: ValueAnimator? = null

    /** HUD 是否在显示。 */
    val isShowing: Boolean get() = root != null

    /** 悬浮窗折叠态（默认展开 true；只存内存，进程重启/重建回展开）。 */
    @Volatile
    var overlayExpanded: Boolean = true
        private set

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

    /**
     * 悬浮窗折叠切换（v2 外观；默认展开，状态只存内存）。
     * 任意线程可调（同步封送，返回即已应用）。
     */
    fun setExpanded(expanded: Boolean) = marshal.sync { setExpandedOnMainThread(expanded) }

    // ---------------------------------------------------------------- 主线程执行体（View/WindowManager 突变只落这里与触摸回调）

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    private fun showOnMainThread(): Boolean {
        if (root != null) return true
        if (!Settings.canDrawOverlays(context)) return false
        val view = LayoutInflater.from(context).inflate(R.layout.view_floating_control, null)
        val metrics = context.resources.displayMetrics
        val density = metrics.density
        val viewportWidthDp = (metrics.widthPixels / density).roundToInt()
        val viewportHeightDp = (metrics.heightPixels / density).roundToInt()
        // 窗宽公式（DESIGN ）：min(380, 视口 − 16)dp —— 根 overlay_root 用 match_parent。
        // px 换算**向下取整**（不是 roundToInt）：377dp @2.75 = 1036.75 → 1036px。
        // 为什么：1080px 屏上 round 会取 1037px，左右各只剩 21.5px（一侧 21px = 7.6dp < margin 8dp）；
        // 1036px 时左右各 22px = 8dp，与下面 initialWindowPosition 的居中落点合成
        // 「左右边距相等且都 ≥ 8dp」（2026-10-10 真机复审问题 1 的验收口径）。
        val windowWidthDp = OverlayGeometry.computeWindowWidthDp(viewportWidthDp)
        val params = WindowManager.LayoutParams(
            (windowWidthDp * density).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            // TRANSLUCENT 是模糊的前提（半透明窗口才透出背后内容），也是圆角玻璃的载体。
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // x/y 在下方「初始落点」块里按测量尺寸设定（addView 之前就定好，不依赖 addView 后的更新）。
        }
        // ── 初始落点（2026-10-10 真机复审问题 1）────────────────────────────────────────────────
        // 缺陷：原来 x 固定 16dp 且不钳位 ⇒ 1080px 屏上 44 + 1037 = 1081 > 1080 —— 右边贴死屏幕、
        // 右边距 0 / 左边 43px、右侧圆角被切平。根因：窗宽 = 视口 − 2·margin 是满载情形，
        // 固定 x 必然让一侧掉到 0；且钳位只在拖动时发生，初始落点没有任何约束。
        // 修法：落点交 OverlayGeometry.initialWindowPosition（纯函数；与拖动钳位同一套语义）——
        // 水平**居中**、左右边距相等。真机 1080×2400 @440dpi：窗宽 1036px、x = 22px ⇒
        // 左右各 22px = 8dp（= margin）。尺寸取测量值：addView 之前窗口还没被量过（首帧遍历未跑
        // ⇒ view.width/height 恒 0），故先 measure 预量一次（宽 EXACTLY × params.width、
        // 高 UNSPECIFIED = wrap_content）；addView 后 view.width 恒等于 params.width（根 match_parent）。
        val (initialWidthPx, initialHeightPx) = measuredWindowSizePx(view, params)
        val initialPosition = OverlayGeometry.initialWindowPosition(
            windowWidthDp = (initialWidthPx / density).roundToInt(),
            windowHeightDp = (initialHeightPx / density).roundToInt(),
            viewportWidthDp = viewportWidthDp,
            viewportHeightDp = viewportHeightDp,
            desiredYDp = INITIAL_Y_DP,
        )
        // WindowManager x/y 单位 px（dp → px 换算回写）；x ≥ margin 与左右对称由 initialWindowPosition 保证。
        params.x = (initialPosition.x * density).roundToInt()
        params.y = (initialPosition.y * density).roundToInt()
        // ① 模糊层 + ② 玻璃底：能力探测（只探一次、有缓存）→ 应用窗口属性与底件。
        // 探测照旧执行（代码路径在位、结果被缓存），但是否**采用**由 [BLUR_BEHIND_ENABLED] 决定：
        // 出厂默认 false —— MIUI V816 实测整屏发虚（常量注释有数据），默认走无模糊的玻璃外观。
        val blurAvailable = crossWindowBlurEnabledOnMainThread()
        applyGlassOnMainThread(view, params, useBlur = blurAvailable)
        attachDragHandler(view, params)
        view.findViewById<Button>(R.id.btn_overlay_pause).setOnClickListener { callbacks.onPauseResume() }
        view.findViewById<Button>(R.id.btn_overlay_stop).setOnClickListener { callbacks.onStop() }
        view.findViewById<Button>(R.id.matrix_overlay_fold_btn).setOnClickListener {
            setExpandedOnMainThread(!overlayExpanded)
        }
        var attached = false
        try {
            windowManager.addView(view, params)
            attached = true
        } catch (e: WindowManager.BadTokenException) {
            attached = false
        } catch (e: IllegalStateException) {
            attached = false
        }
        if (!attached && blurAvailable) {
            // 带模糊的窗口被系统拒绝（OEM 不支持 / 策略拒绝）：**静默回退**到无模糊玻璃底再挂一次。
            // （仅当 [BLUR_BEHIND_ENABLED] 为 true 才会真的申请模糊；开关默认关闭时本分支等于
            //   用同一套外观重挂一次，无副作用，保留它是为了开关翻回 true 时行为立刻正确。）
            // 重挂安全：WindowManagerGlobal.addView 在 root.setView 抛 RuntimeException 时会先
            // removeViewLocked 清理（AOSP android-15.0.0_r1 core/java/android/view/WindowManagerGlobal.java
            // :441-450），故同一 view 可再次 addView；这一次连异常类型都放宽，绝不让 show() 崩。
            blurCapability = false
            applyGlassOnMainThread(view, params, useBlur = false)
            try {
                windowManager.addView(view, params)
                attached = true
            } catch (e: RuntimeException) {
                attached = false
            }
        }
        if (!attached) return false
        root = view
        metricsView = view.findViewById(R.id.txt_overlay_metrics)
        pauseButton = view.findViewById(R.id.btn_overlay_pause)
        foldButton = view.findViewById(R.id.matrix_overlay_fold_btn)
        gridWrap = view.findViewById(R.id.matrix_overlay_grid_wrap)
        ledView = view.findViewById(R.id.matrix_overlay_led)
        gridCircle = view.findViewById(R.id.matrix_overlay_grid_circle)
        gridDistance = view.findViewById(R.id.matrix_overlay_grid_distance)
        gridPace = view.findViewById(R.id.matrix_overlay_grid_pace)
        gridCadence = view.findViewById(R.id.matrix_overlay_grid_cadence)
        layoutParams = params
        overlayExpanded = true
        setExpandedOnMainThread(true)
        startAppearOnMainThread(view)
        if (lastMetrics.isNotEmpty() || lastPaused) applyOnMainThread(lastMetrics, lastPaused)
        return true
    }

    /**
     * 窗口尺寸（px）：已布局过（`view.width/height > 0`）直接用实测值，否则按窗口参数预量一次
     * ——宽 `EXACTLY × params.width`（窗宽由 [OverlayGeometry.computeWindowWidthDp] 定为 px 精确值）、
     * 高 `UNSPECIFIED`（= wrap_content，由 XML 决定：8+48+56+8 = 120dp）。被 [showOnMainThread]
     * 用来给 [OverlayGeometry.initialWindowPosition] 提供窗口尺寸（2026-10-10 真机复审问题 1）。
     * 只读 view/params，不触 WindowManager；恒主线程调用（随 showOnMainThread）。
     */
    private fun measuredWindowSizePx(view: View, params: WindowManager.LayoutParams): Pair<Int, Int> {
        if (view.width > 0 && view.height > 0) return view.width to view.height
        view.measure(
            View.MeasureSpec.makeMeasureSpec(params.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        return (view.width.takeIf { it > 0 } ?: view.measuredWidth) to
            (view.height.takeIf { it > 0 } ?: view.measuredHeight)
    }

    private fun applyOnMainThread(metricsText: String, paused: Boolean) {
        lastMetrics = metricsText
        lastPaused = paused
        setMetricsOnMainThread(metricsText)
        setPausedOnMainThread(paused)
    }

    /**
     * 指标行写入（**主行 = 前两格：圈 + 距离**）。
     *
     * 宽度实算（真机 1080×2400 @440dpi = 393dp 视口；窗宽 min(380, 393−16)=377dp、内容 361dp、
     * 指标格 = 361 − 216 = **145dp**；12sp 等宽 ≈ ASCII 0.6em+0.01em 字距 = 7.32dp、CJK 1em = 12.12dp）：
     * - 全串「第 2/3 圈 · 0.76 km · 5:30 /km · 176 spm」≈ 2×12.12 + 36×7.32 ≈ **288dp** —— 塞进 145dp
     *   需要 ≈6.0sp 字号，不可读；实测截断点恰在「…km ·」之后（可见段 ≈141dp + 省略号），
     *   与该宽度模型一致 ⇒ **靠调字号解决不了**，按验收给的退路：主行只放两项。
     * - 前两格「第 2/3 圈 · 0.76 km」≈ 2×12.12 + 15×7.32 ≈ **134dp ≤ 145dp** ✔ 不再截断；
     *   配速/步频在第二行 4 格网格（[applyGridOnMainThread]）**全量显示**，一个字都不省。
     * 合规后缀**不在这里承担**（格式串已不带它；即便带，[stripComplianceSuffix] 也会剥掉）：
     * 合规载体 = 标记格 `matrix_overlay_test_marker` + 通知标题 + 主界面徽标（DESIGN ）。
     */
    private fun setMetricsOnMainThread(text: String) {
        lastMetrics = text
        val display = stripComplianceSuffix(text)
        val cells = splitMetricCells(display)
        val mainLine = mainRowTextOf(cells, display)
        val changed = display != shownMetrics
        shownMetrics = display
        metricsView?.text = mainLine
        applyGridOnMainThread(cells)
        if (changed) restartMetricsPulseOnMainThread()
    }

    private fun setPausedOnMainThread(paused: Boolean) {
        lastPaused = paused
        pauseButton?.setText(if (paused) R.string.action_resume else R.string.action_pause)
        pauseButton?.contentDescription =
            context.getString(if (paused) R.string.cd_overlay_resume else R.string.cd_overlay_pause)
        // 状态变色（DESIGN  白名单内）：指标行 primary / warning；
        // 网格首格（圈）success / warning；其余格 primary / secondary；LED 换件 play / pause。
        // 悬浮窗只拿得到 paused 二值（服务的 update(ui) 传 ui.state == PAUSED，PlaybackForegroundService:481），
        // 且停止/完成时服务已 hide()（:589）——故"停止态配色"（v2_led_stop / text_secondary）只用于
        // 不显示的场景，不在此处伪造第三态。
        metricsView?.setTextColor(
            context.getColor(if (paused) R.color.matrix_warning else R.color.matrix_text_primary),
        )
        ledView?.setBackgroundResource(if (paused) R.drawable.v2_led_pause else R.drawable.v2_led_play)
        pauseButton?.setCompoundDrawableTintList(
            ColorStateList.valueOf(
                context.getColor(if (paused) R.color.matrix_warning else R.color.matrix_text_primary),
            ),
        )
        gridCircle?.setTextColor(
            context.getColor(if (paused) R.color.matrix_warning else R.color.matrix_success),
        )
        val otherCellColor =
            context.getColor(if (paused) R.color.matrix_text_secondary else R.color.matrix_text_primary)
        gridDistance?.setTextColor(otherCellColor)
        gridPace?.setTextColor(otherCellColor)
        gridCadence?.setTextColor(otherCellColor)
        applyBreathOnMainThread(paused)
    }

    /** 悬浮窗折叠应用（默认展开；状态只存内存）。 */
    private fun setExpandedOnMainThread(expanded: Boolean) {
        overlayExpanded = expanded
        applyFoldVisualOnMainThread(expanded)
    }

    /**
     * 折叠外观（网格行高度动效 + 折叠键换件）。
     * 说明：本函数只被 [setExpandedOnMainThread]/[showOnMainThread]（主线程执行体）调用，
     * 不属于 OverlayThreadingGuardTest 允许名单——该测试的 mutation 正则只覆盖
     * metricsView/pauseButton/root/layoutParams/windowManager，fold/grid/LED 行不在其扫描面内，
     * 故不触发其断言；线程安全性由调用点（恒主线程）保证。
     *
     * 「折叠」是几何变化（高度插值），不是 alpha；减少动态时直接置终态。
     */
    private fun applyFoldVisualOnMainThread(expanded: Boolean) {
        // 折叠键是纯图标键：文案只进 contentDescription（展开中文案会把主行撑爆，R1-6）。
        foldButton?.setCompoundDrawablesWithIntrinsicBounds(
            0,
            if (expanded) R.drawable.ic_ui_fold else R.drawable.ic_ui_expand,
            0,
            0,
        )
        foldButton?.contentDescription = context.getString(
            if (expanded) R.string.matrix_overlay_fold else R.string.matrix_overlay_expand,
        )
        // 显式重上色：换件是运行期行为，不依赖框架"新 drawable 是否继承 XML drawableTint"的实现细节。
        foldButton?.setCompoundDrawableTintList(
            ColorStateList.valueOf(context.getColor(R.color.matrix_text_primary)),
        )
        val wrap = gridWrap ?: return
        foldAnimator?.cancel()
        foldAnimator = null
        val density = context.resources.displayMetrics.density
        // 56dp = GRID_ROW_GAP_DP(8) + GRID_ROW_HEIGHT_DP(48)：gap 就在 wrap 的 paddingTop 里。
        val expandedHeightPx =
            ((OverlayGeometry.GRID_ROW_GAP_DP + OverlayGeometry.GRID_ROW_HEIGHT_DP) * density).roundToInt()
        val targetHeightPx = if (expanded) expandedHeightPx else 0
        if (!ReducedMotion.allows()) {
            wrap.visibility = if (expanded) View.VISIBLE else View.GONE
            // 原地改 LayoutParams（不是"赋新 LayoutParams"）：高度插值必须走
            // layoutParams.height + requestLayout()，不得用 setAlpha 代替折叠语义。
            wrap.layoutParams.height = targetHeightPx
            wrap.requestLayout()
            return
        }
        if (expanded) wrap.visibility = View.VISIBLE
        val animator = ValueAnimator.ofInt(wrap.height.coerceAtLeast(0), targetHeightPx).apply {
            duration = FOLD_DURATION_MS
            interpolator = if (expanded) easeEnter else easeExit
            addUpdateListener { animation ->
                wrap.layoutParams.height = animation.animatedValue as Int
                wrap.requestLayout()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (!expanded) wrap.visibility = View.GONE
                    // 同一动画才清引用：旧动画取消时的回调不得把新动画的引用抹掉（否则 hide 漏取消）。
                    if (foldAnimator === animation) foldAnimator = null
                }
            })
        }
        foldAnimator = animator
        animator.start()
    }

    /**
     * 窗口出现淡入（160ms / ease_enter；减少动态时直接置 1.0）。
     * 只被 [showOnMainThread] 调用；[hideOnMainThread] 一并 cancel 并置空。
     *
     * 消失方向**不做**淡出：`hide()` 是同步封送且契约要求"返回即已移除"
     * （FloatingOverlayThreadingTest 断言 hide 返回后 `isShowing == false`），
     * 淡出后再移除会让窗口在引用置空后仍留在屏幕上（并可能与下一次 show 并发）。
     */
    private fun startAppearOnMainThread(view: View) {
        appearAnimator?.cancel()
        appearAnimator = null
        if (!ReducedMotion.allows()) {
            view.alpha = 1f
            return
        }
        view.alpha = 0f
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = APPEAR_DURATION_MS
            interpolator = easeEnter
            addUpdateListener { animation -> view.alpha = animation.animatedValue as Float }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    view.alpha = 1f
                    if (appearAnimator === animation) appearAnimator = null
                }
            })
        }
        appearAnimator = animator
        animator.start()
    }

    /**
     * 指标数字变化脉冲（160ms alpha 0.62→1.0；只作用于指标格本体，不动玻璃底与整窗透明度）。
     * 只被 [setMetricsOnMainThread]（主线程执行体）在同一串真正变化时调用；减少动态时直接置 1.0。
     */
    private fun restartMetricsPulseOnMainThread() {
        val target = metricsView ?: return
        metricsPulseAnimator?.cancel()
        metricsPulseAnimator = null
        if (!ReducedMotion.allows()) {
            target.alpha = 1f
            return
        }
        val animator = ValueAnimator.ofFloat(METRICS_PULSE_FROM_ALPHA, 1f).apply {
            duration = METRICS_PULSE_DURATION_MS
            interpolator = easeEnter
            addUpdateListener { animation -> target.alpha = animation.animatedValue as Float }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    target.alpha = 1f
                    if (metricsPulseAnimator === animation) metricsPulseAnimator = null
                }
            })
        }
        metricsPulseAnimator = animator
        animator.start()
    }

    /**
     * 呼吸光：仅 PAUSED 运行（alpha 0.6→1.0、周期 1600ms、INFINITE/REVERSE），他态 cancel。
     * ValueAnimator 非 Gradle 依赖，自 API 11 可用且 minSdk26 合规；
     * 全部主线程经 Marshal（本函数只在 *OnMainThread 内调用），hide 幂等清理 cancel 置空，
     * 无权限 show()=false 时不建动画（show 未成功即无 pauseButton）。
     */
    private fun applyBreathOnMainThread(paused: Boolean) {
        val target = pauseButton
        if (!paused || target == null || !ReducedMotion.allows()) {
            breathAnimator?.cancel()
            breathAnimator = null
            pauseButton?.alpha = 1.0f
            return
        }
        if (breathAnimator != null) return
        val animator = ValueAnimator.ofFloat(0.6f, 1.0f).apply {
            duration = BREATH_DURATION_MS
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { animation -> target.alpha = animation.animatedValue as Float }
        }
        breathAnimator = animator
        animator.start()
    }

    private fun hideOnMainThread() {
        // 取消**全部**动画并置空引用（红线：绝不让动画持有已 detach 的 View）。
        appearAnimator?.cancel()
        appearAnimator = null
        breathAnimator?.cancel()
        breathAnimator = null
        foldAnimator?.cancel()
        foldAnimator = null
        metricsPulseAnimator?.cancel()
        metricsPulseAnimator = null
        val view = root ?: return
        root = null
        metricsView = null
        pauseButton = null
        foldButton = null
        gridWrap = null
        ledView = null
        gridCircle = null
        gridDistance = null
        gridPace = null
        gridCadence = null
        layoutParams = null
        shownMetrics = ""
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

    /**
     * 跨窗模糊能力探测：API 31+ 且 `WindowManager.isCrossWindowBlurEnabled()`（**只探测一次并缓存**）。
     * 判定依据见类头 KDoc（AOSP WindowManager.java:1790）；OEM 无 GPU 支持 / 系统关模糊 /
     * 省电模式都落在 isCrossWindowBlurEnabled() == false。
     * 探测本身抛任何 RuntimeException 都按"不可用"处理（绝不因能力探测让 show() 崩）。
     * 本函数只被 [showOnMainThread] 调用（恒主线程）。
     */
    private fun crossWindowBlurEnabledOnMainThread(): Boolean {
        blurCapability?.let { return it }
        var enabled = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            enabled = try {
                windowManager.isCrossWindowBlurEnabled()
            } catch (e: RuntimeException) {
                false
            }
        }
        blurCapability = enabled
        return enabled
    }

    /**
     * 模糊层 + 玻璃底。
     *
     * **单套玻璃**（2026-10-10 真机修复）：平台模糊默认关闭（[BLUR_BEHIND_ENABLED] = false）后，
     * "模糊可用态"与"回退态"两套外观已无区分意义，故合并为一套 `v2_glass_hud`
     * （实底 alpha 0.7804 + 顶部轻遮罩 + 12dp 白高光 + 双发丝）；`v2_glass_hud_fallback.xml`
     * 及其颜色/别名一并删除（grep 全仓无引用）。
     *
     * 模糊分支（仅当开关为 true 且平台确认支持）：`FLAG_BLUR_BEHIND` + `setBlurBehindRadius(px)`；
     * 其余情况一律清掉标志与半径（二者必须同生同灭——重挂路径复用同一个 params 对象）。
     * 半径 = 10dp 换算 px 后钳在 [16, 40]（上限 40px 控 GPU 合成开销，DESIGN ）;
     * 底件在两条分支上都**同一件**（不再切换 drawable）。
     * `Build.VERSION.SDK_INT >= S` 的判据在此**词法支配**平台调用（lint NewApi 要求；
     * 上游 useBlur 已由 [crossWindowBlurEnabledOnMainThread] 在 API 31+ 上确认，此处是双保险）。
     */
    private fun applyGlassOnMainThread(
        view: View,
        params: WindowManager.LayoutParams,
        useBlur: Boolean,
    ) {
        val density = view.resources.displayMetrics.density
        val radiusPx = (BLUR_RADIUS_DP * density).roundToInt()
            .coerceIn(BLUR_RADIUS_MIN_PX, BLUR_RADIUS_MAX_PX)
        if (BLUR_BEHIND_ENABLED && useBlur && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
            params.setBlurBehindRadius(radiusPx)
        } else {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // 重挂路径：清掉上一次尝试设过的半径（标志已清，二者必须同生同灭）。
                params.setBlurBehindRadius(0)
            }
        }
        // 单套玻璃：两条分支同件（合并原因见本函数 KDoc 与类头）。
        view.setBackgroundResource(R.drawable.v2_glass_hud)
    }

    /**
     * 剥离合规后缀：zh 全角「（…）」/ en 半角「 (…)」两种口径都覆盖。
     * 为什么可以剥：合规标记格 `matrix_overlay_test_marker` 是静态 XML 文本、永不裁剪，
     * 指标串截断/剥离不会让悬浮窗失去「测试」载体（DESIGN  载体规则）。
     * 现状：`overlay_metrics_format` 已不再带「（测试/模拟定位）」后缀（本轮回合的悬浮窗修复），
     * 本函数保留为**双保险**——任何回潮的后缀都不会吃掉主行宽度。
     */
    private fun stripComplianceSuffix(raw: String): String =
        raw.substringBefore("（").substringBefore(" (").trim()

    /** 指标串按 `·` 切格（与 `overlay_metrics_format` 同源；无分隔符时整串即唯一格）。 */
    private fun splitMetricCells(display: String): List<String> =
        display.split(METRIC_CELL_SEPARATOR).map { it.trim() }

    /**
     * 主行文本 = **前两格**（圈 + 距离），join 回原分隔符「 · 」保持同源观感；
     * 格数不足（旧口径/无分隔符串）时用整串——不伪造、不丢信息。
     * 为什么只放两格：见 [setMetricsOnMainThread] 的宽度实算（四格全串 ≈288dp vs 指标格 145dp）。
     */
    private fun mainRowTextOf(cells: List<String>, display: String): String =
        if (cells.size >= MAIN_ROW_CELL_COUNT) {
            cells.take(MAIN_ROW_CELL_COUNT).joinToString(METRIC_CELL_SEPARATOR_SPACED)
        } else {
            display
        }

    /**
     * 展开态 4 格网格（圈 / 距离 / 配速 / 步频）：直接落同一份切分结果（与主行同源，不多一次 split）。
     * 串里没有 `·` 时 [splitMetricCells] 只出一格（= 整串）：首格照写整串、其余留空——
     * **不伪造数据**、不重复填满。
     */
    private fun applyGridOnMainThread(cells: List<String>) {
        gridCircle?.text = cells.getOrElse(0) { "" }
        gridDistance?.text = cells.getOrElse(1) { "" }
        gridPace?.text = cells.getOrElse(2) { "" }
        gridCadence?.text = cells.getOrElse(3) { "" }
    }

    companion object {

        /**
         * HUD 初始纵向落点（dp，左上原点）。**横向不再有常量**：初始 x 恒由
         * [OverlayGeometry.initialWindowPosition] 取「水平居中、左右边距相等」——
         * 原先的 `INITIAL_X_DP = 16` 在 377dp 窗宽（= 视口 − 2·margin）下必然右侧出屏
         * （`16 + 377 + 16 = 409 > 393`，2026-10-10 真机复审问题 1），已删除。
         */
        private const val INITIAL_Y_DP = 96

        /** 呼吸光周期（ms， #5）。 */
        private const val BREATH_DURATION_MS = 1600L

        /** 折叠/展开动效时长（ms， #9）。 */
        private const val FOLD_DURATION_MS = 180L

        /** 窗口出现淡入时长（ms）。 */
        private const val APPEAR_DURATION_MS = 160L

        /** 指标数字变化脉冲：时长（ms）与起始 alpha。 */
        private const val METRICS_PULSE_DURATION_MS = 160L
        private const val METRICS_PULSE_FROM_ALPHA = 0.62f

        /** 模糊半径（dp；DESIGN ：10dp × density 后钳在 [16, 40] px）。 */
        private const val BLUR_RADIUS_DP = 10
        private const val BLUR_RADIUS_MIN_PX = 16
        private const val BLUR_RADIUS_MAX_PX = 40

        /**
         * **平台模糊（跨窗高斯）总开关——出厂默认 `false`（关闭）**。
         *
         * **为什么关**（真机实证，非推测）：小米 21091116UC / Android 13 / MIUI V816 /
         * 1080×2400 @440dpi 上，给 `TYPE_APPLICATION_OVERLAY` 窗口开 `FLAG_BLUR_BEHIND` 后，
         * 合成器**把整块屏幕的背景层都模糊了**，远超出"窗后"范围——同一静止桌面、只切换悬浮窗
         * 有无（内容逐像素相同）时，桌面图标第 1/2/3 行与 Dock 的拉普拉斯锐度 lapAbs
         * 从 19.68 / 28.32 / 32.37 / 28.44 掉到 **0.99 / 1.43 / 0.86 / 0.88（3%~5%，整屏失焦）**；
         * 同期窗口 `mAttrs` 里 `blurBehindRadius=28` 且 `fl=BLUR_BEHIND`（申请已被 WindowManager
         * 接受）、系统 `disable_window_blurs` 未设置 ⇒ 这是**平台合成行为**，应用侧无法通过调参
         * 把它变成本地（仅窗后）模糊，故默认不走这条路。
         *
         * **何时可以再打开**：只有在 **AOSP**（未被 OEM 改写模糊语义）设备上实测确认
         * "只模糊窗后、屏幕其余区域不失焦"之后（同口径：非重叠区 lapAbs ≥ 关闭时的 90%），
         * 把本常量置 `true` 即恢复完整路径——能力探测（[crossWindowBlurEnabledOnMainThread]）
         * 与 [applyGlassOnMainThread] 的回退分支**一直在位**，不需要改任何其它代码。
         * **不要**用反射或 `Build.MODEL` / 版本号去猜 OEM 来决定开合——那是猜，不是实测。
         */
        private const val BLUR_BEHIND_ENABLED = false

        /** 指标串格分隔符（与 `overlay_metrics_format` 的 `·` 同源）。 */
        private const val METRIC_CELL_SEPARATOR = "·"

        /** 主行 join 回原观感用的带空格分隔符（`overlay_metrics_format` 即「 · 」）。 */
        private const val METRIC_CELL_SEPARATOR_SPACED = " · "

        /**
         * 主行显示的格数 = 2（圈 + 距离）；配速/步频在第二行 4 格网格全量显示。
         * 为什么不是 4：见 [setMetricsOnMainThread] 的宽度实算（全串 ≈288dp vs 指标格 145dp）。
         */
        private const val MAIN_ROW_CELL_COUNT = 2
    }
}
