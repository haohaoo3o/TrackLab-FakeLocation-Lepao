package io.github.haohaoo3o.tracklab.device

import io.github.haohaoo3o.tracklab.core.model.TrackSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 断言契约见 docs/CONTRACTS.md §6（4 方法端口接口 add/set/setEnabled/remove，
 * 仅此序列；SecurityException→显式引导态；无隐藏 mock 路径）。通过注入 fake 端口在纯 JVM 断言，
 * 不直接触碰 android.location.LocationManager。
 */
class TestLocationSinkTest {

    /** 记录调用序列的 fake 端口（可注入 SecurityException 时机）。 */
    private class FakeSink(private val throwOn: String? = null) : TestLocationSink {
        val calls = mutableListOf<String>()

        private fun record(op: String) {
            calls.add(op)
            if (throwOn != null && op.startsWith(throwOn)) {
                throw SecurityException("未在开发者选项中选择本应用为模拟位置信息应用")
            }
        }

        override fun add(name: String) = record("add($name)")
        override fun set(name: String, sample: TrackSample) = record("set($name)")
        override fun setEnabled(name: String, enabled: Boolean) = record("setEnabled($name,$enabled)")
        override fun remove(name: String) = record("remove($name)")
    }

    private fun sample(index: Int) = TrackSample(
        latitudeDeg = 30.0 + index * 1e-5, // 合成境内测试坐标，不对应个人位置
        longitudeDeg = 110.0 + index * 1e-5,
        elapsedMs = index * 500L,
        speedMps = 3.0,
        paceSecPerKm = 333.3,
        cadenceSpm = 170.0,
    )

    // ---------------------------------------------------------------- 仅 add/set/setEnabled/remove 序列

    @Test
    fun lifecycleCallSequenceIsExactlyTheFourPortMethods() {
        val sink = FakeSink()
        val output = TestLocationOutput(sink, "gps")
        assertTrue(output.start())
        assertTrue(output.emit(sample(0)))
        assertTrue(output.emit(sample(1)))
        assertTrue(output.setOutputEnabled(false))
        assertTrue(output.setOutputEnabled(true))
        assertTrue(output.stop())
        assertEquals(
            listOf(
                "add(gps)",
                "setEnabled(gps,true)",
                "set(gps)",
                "set(gps)",
                "setEnabled(gps,false)",
                "setEnabled(gps,true)",
                "setEnabled(gps,false)",
                "remove(gps)",
            ),
            sink.calls,
        )
        assertEquals(TestLocationOutput.State.IDLE, output.state)
    }

    @Test
    fun stopWithoutEnabledDisableStillRemovesExactlyOnce() {
        val sink = FakeSink()
        val output = TestLocationOutput(sink, "gps")
        output.start()
        output.stop()
        assertEquals(
            listOf("add(gps)", "setEnabled(gps,true)", "setEnabled(gps,false)", "remove(gps)"),
            sink.calls,
        )
        // 幂等：IDLE 再 stop 不触碰端口
        assertTrue(output.stop())
        assertEquals(4, sink.calls.size)
    }

    @Test
    fun emitOnlyWhileActive() {
        val sink = FakeSink()
        val output = TestLocationOutput(sink, "gps")
        assertFalse("IDLE 不推送", output.emit(sample(0)))
        assertTrue(sink.calls.isEmpty())
        output.start()
        output.stop()
        assertFalse("IDLE（stop 后）不推送", output.emit(sample(1)))
        assertEquals(4, sink.calls.size)
    }

    // ---------------------------------------------------------------- SecurityException → 显式引导态

    @Test
    fun securityExceptionOnAddFallsToExplicitGuidance() {
        val sink = FakeSink(throwOn = "add(")
        val output = TestLocationOutput(sink, "gps")
        assertFalse(output.start())
        assertEquals(TestLocationOutput.State.GUIDANCE, output.state)
        assertTrue(output.needsGuidance())
        // 失败后不再触碰端口（无隐藏重试路径）
        assertFalse(output.emit(sample(0)))
        assertEquals(listOf("add(gps)"), sink.calls)
    }

    @Test
    fun securityExceptionOnSetFallsToExplicitGuidance() {
        val sink = FakeSink(throwOn = "set(")
        val output = TestLocationOutput(sink, "gps")
        assertTrue(output.start())
        assertFalse(output.emit(sample(0)))
        assertEquals(TestLocationOutput.State.GUIDANCE, output.state)
        assertTrue(output.needsGuidance())
        assertFalse("引导态不得继续报告 mock 输出已启用", output.outputEnabled)
        // 显式引导态下推送/停用均不触碰端口
        assertFalse(output.emit(sample(1)))
        assertFalse(output.setOutputEnabled(false))
        assertEquals(listOf("add(gps)", "setEnabled(gps,true)", "set(gps)"), sink.calls)
    }

    @Test
    fun securityExceptionOnRemoveFallsToExplicitGuidanceThenReset() {
        val sink = FakeSink(throwOn = "remove(")
        val output = TestLocationOutput(sink, "gps")
        output.start()
        assertFalse(output.stop())
        assertEquals(TestLocationOutput.State.GUIDANCE, output.state)
        assertFalse("清理失败进入引导态后不得报告输出仍启用", output.outputEnabled)
        // reset：仅本地复位，不触碰端口
        output.reset()
        assertEquals(TestLocationOutput.State.IDLE, output.state)
        assertFalse(output.needsGuidance())
        assertEquals(
            listOf("add(gps)", "setEnabled(gps,true)", "setEnabled(gps,false)", "remove(gps)"),
            sink.calls,
        )
    }

    // ---------------------------------------------------------------- 回归：暂停（setEnabled false）后不得再推帧

    @Test
    fun emitIsGatedOnOutputEnabledSoPausedOutputNeverPushes() {
        val sink = FakeSink()
        val output = TestLocationOutput(sink, "gps")
        output.start()
        assertTrue(output.emit(sample(0)))
        assertTrue(output.setOutputEnabled(false))
        // 修复前 emit 只查 state（仍 ACTIVE）不查 outputEnabled → 暂停后照样 sink.set 推帧。
        assertFalse("暂停（输出停用）后 emit 不得推帧", output.emit(sample(1)))
        assertFalse(output.emit(sample(2)))
        assertEquals(
            listOf("add(gps)", "setEnabled(gps,true)", "set(gps)", "setEnabled(gps,false)"),
            sink.calls,
        )
        assertTrue(output.setOutputEnabled(true))
        assertTrue("继续（输出启用）后恢复推帧", output.emit(sample(3)))
        assertEquals(TestLocationOutput.State.ACTIVE, output.state)
    }

    @Test
    fun pauseOnOneThreadSilencesEmitFromPlaybackThread() {
        // 时序回归（多发一帧机制 (b) 的跨线程面）：主线程 setOutputEnabled(false) 返回后，
        // 回放线程任何 emit 均不推帧——内部锁与 emit 闸门共同封口（在飞 emit 亦在停用返回前完成）。
        val sink = FakeSink()
        val output = TestLocationOutput(sink, "gps")
        output.start()
        val pushing = AtomicBoolean(true)
        val playback = thread(name = "playback-loop") {
            while (pushing.get()) {
                output.emit(sample(0))
            }
        }
        // 等回放线程确实进入推帧节奏（>2 次端口调用 = add + setEnabled(true) 之外已有 set）。
        val deadline = System.currentTimeMillis() + 5_000
        while (sink.calls.size <= 2 && System.currentTimeMillis() < deadline) Thread.sleep(1)
        assertTrue("停用前应已有推帧（否则本测试空转）", sink.calls.size > 2)

        assertTrue(output.setOutputEnabled(false))
        val callsAtPause = sink.calls.size
        val setsAtPause = sink.calls.count { it == "set(gps)" }
        Thread.sleep(20) // 在飞迭代收尾窗口
        assertEquals("setEnabled(false) 返回后端口必须恒静默", callsAtPause, sink.calls.size)

        pushing.set(false)
        playback.join()
        assertFalse("暂停后 emit 一律不推帧", output.emit(sample(1)))
        assertEquals(callsAtPause, sink.calls.size)
        // 端口序列构成：add/setEnabled(true)（开启）+ setEnabled(false)（本次停用）之外全部是 set，
        // 且停用后 set 数量不再增长。
        assertTrue("停用前应已有 set 推帧（否则本测试空转）", setsAtPause > 0)
        assertEquals(setsAtPause, sink.calls.count { it == "set(gps)" })
    }

    // ---------------------------------------------------------------- 无隐藏 mock 路径（§9）

    @Test
    fun portInterfaceIsExactlyFourMethods() {
        val methods = TestLocationSink::class.java.declaredMethods.map { it.name }.toSet()
        assertEquals(setOf("add", "set", "setEnabled", "remove"), methods)
    }

    @Test
    fun outputDependsOnlyOnThePortNoHiddenMockChannel() {
        // 构造器注入面 = 端口 + 提供者名（String），无 Android 类型、无其他输出通道
        val ctorTypes = TestLocationOutput::class.java.constructors
            .map { ctor -> ctor.parameterTypes.toList() }
        assertTrue(ctorTypes.isNotEmpty())
        for (types in ctorTypes) {
            assertEquals(listOf(TestLocationSink::class.java, String::class.java), types)
        }
        // 全部方法签名不含 android.* 类型（纯 JVM 可执行性 + 无隐藏通道）
        val androidRefs = TestLocationOutput::class.java.declaredMethods
            .flatMap { listOf(it.returnType) + it.parameterTypes.toList() }
            .filter { it.name.startsWith("android.") }
        assertEquals("TestLocationOutput 不得引用 android.* 类型", emptyList<Any>(), androidRefs)
    }
}
