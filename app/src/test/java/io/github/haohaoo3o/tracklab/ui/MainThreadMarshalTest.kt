package io.github.haohaoo3o.tracklab.ui

import io.github.haohaoo3o.tracklab.ui.overlay.MainThreadMarshal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 回归测试：[MainThreadMarshal] 线程封送语义——HUD 的 View/WindowManager 突变
 * 必须恒在 UI（主）线程执行；后台线程调用同步封送返回值/异常原样回传；异步恒投递且 FIFO 保序
 * （在 UI 线程上也不内联，防后发先至写花 HUD）。纯 JVM 可执行（判据/投递口注入）。
 */
class MainThreadMarshalTest {

    /** 单线程伪 UI 线程（线程名 "fake-ui" 作判据）。 */
    private class FakeUi {
        val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
            Thread(r, "fake-ui").apply { isDaemon = true }
        }
        val isUiThread: () -> Boolean = { Thread.currentThread().name == "fake-ui" }
    }

    @Test
    fun syncRunsInlineWhenAlreadyOnUiThread() {
        val ui = FakeUi()
        try {
            val marshal = MainThreadMarshal(ui.isUiThread) { ui.executor.execute(it) }
            val from = AtomicReference<String>()
            val result = ui.executor.submit<Double> {
                marshal.sync {
                    from.set(Thread.currentThread().name)
                    42.5
                }
            }.get()
            assertEquals(42.5, result, 0.0)
            // UI 线程上必须内联（同线程执行），不得再投递。
            assertEquals("fake-ui", from.get())
        } finally {
            ui.executor.shutdownNow()
        }
    }

    @Test
    fun syncMarshalsOffUiThreadAndReturnsResult() {
        val ui = FakeUi()
        try {
            val marshal = MainThreadMarshal(ui.isUiThread) { ui.executor.execute(it) }
            val blockThread = AtomicReference<String>()
            val callerThread = Thread.currentThread().name
            val result = marshal.sync {
                blockThread.set(Thread.currentThread().name)
                "ok"
            }
            assertEquals("ok", result)
            assertEquals("块必须在 UI 线程执行", "fake-ui", blockThread.get())
            assertTrue("调用方线程不该是 UI 线程（测试线程≠fake-ui）", callerThread != "fake-ui")
        } finally {
            ui.executor.shutdownNow()
        }
    }

    @Test
    fun syncPropagatesExceptionsBackToCaller() {
        val ui = FakeUi()
        try {
            val marshal = MainThreadMarshal(ui.isUiThread) { ui.executor.execute(it) }
            try {
                marshal.sync<String> { throw IllegalStateException("boom") }
                fail("UI 线程块的异常须原样抛回调用线程")
            } catch (e: IllegalStateException) {
                assertEquals("boom", e.message)
            }
        } finally {
            ui.executor.shutdownNow()
        }
    }

    @Test
    fun asyncAlwaysPostsEvenWhenAlreadyOnUiThread() {
        // 在 UI 线程上也不得内联——内联会插队到先前投递的更新之前（后发先至）。
        val queue = ArrayDeque<Runnable>()
        val marshal = MainThreadMarshal(isUiThread = { true }, postToUiThread = { queue.add(it) })
        val ran = AtomicBoolean(false)
        marshal.async { ran.set(true) }
        assertFalse("async 不得内联执行", ran.get())
        assertEquals("async 必须经投递口（恒 1 条）", 1, queue.size)
        queue.remove().run()
        assertTrue(ran.get())
    }

    @Test
    fun asyncPreservesFifoOrderAcrossThreads() {
        val queue = ArrayDeque<Runnable>()
        val marshal = MainThreadMarshal(isUiThread = { false }, postToUiThread = { queue.add(it) })
        val order = mutableListOf<Int>()
        marshal.async { order.add(1) }
        marshal.async { order.add(2) }
        marshal.async { order.add(3) }
        assertEquals(3, queue.size)
        while (queue.isNotEmpty()) queue.remove().run()
        assertEquals(listOf(1, 2, 3), order)
    }
}
