package io.github.haohaoo3o.tracklab.ui.overlay

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

/**
 * UI（主）线程封送器——**纯 Kotlin、零 Android 依赖**（判据/投递口构造注入，纯 JVM 可测，
 * 同 `ui/overlay/OverlayGeometry` 口径）。Android 侧由
 * [FloatingOverlayController] 注入 `Looper.getMainLooper()` 判据 + `Handler.post`。
 *
 * 回归背景：`WindowManager.addView` / View 突变**必须**在主（Looper）线程执行——
 * 后台线程直触 ViewRootImpl 即 `RuntimeException: Can't create handler inside thread that has
 * not called Looper.prepare()` 未捕获崩溃；跨线程 setText→requestLayout 抛
 * `CalledFromWrongThreadException`。故 HUD 的创建/更新/移除一律经本类封送到 UI 线程。
 *
 * 语义：
 * - [sync]：UI 线程上**内联**执行（保同步返回值契约）；其他线程投递 UI 线程并**等待完成**，
 *   返回值/异常原样回传（等价于「已在 UI 线程」的调用体验）。
 *   **调用方不得持任何锁调用**——等待期间 UI 线程若在等同一把锁即互锁（服务侧 show/hide
 *   调用点均在无锁区，见 PlaybackForegroundService）。
 * - [async]：**恒投递**（即使调用方已在 UI 线程），保证与先前投递严格 FIFO——
 *   若在 UI 线程上内联，会后发先至，把旧帧写回 HUD。
 */
class MainThreadMarshal(
    private val isUiThread: () -> Boolean,
    private val postToUiThread: (Runnable) -> Unit,
) {

    /** 同步在 UI 线程执行 [block] 并返回其结果（异常原样抛回调用线程）。 */
    fun <T> sync(block: () -> T): T {
        if (isUiThread()) return block()
        val result = AtomicReference<Result<T>?>()
        val done = CountDownLatch(1)
        postToUiThread(
            Runnable {
                result.set(runCatching { block() })
                done.countDown()
            },
        )
        // InterruptedException 原样上抛（中断语义不吞）。
        done.await()
        // 正常返回时 Runnable 必已执行完（countDown 在 set 之后）。
        return result.get()!!.getOrThrow()
    }

    /** 异步投递 UI 线程执行 [block]（恒经投递口，FIFO 保序；不等待完成）。 */
    fun async(block: () -> Unit) {
        postToUiThread(Runnable { block() })
    }
}
