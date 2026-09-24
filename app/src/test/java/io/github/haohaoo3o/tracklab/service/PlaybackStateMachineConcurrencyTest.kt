package io.github.haohaoo3o.tracklab.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * 回归测试（撕裂快照）：主线程动作处理（onEvent/encode）与 Default 回放线程
 * （advance/encode/读取）并发访问同一 [PlaybackStateMachine] 实例——
 *
 * 1. `encode()` 快照**不得撕裂**：sampleIndex/distanceM/elapsedMs 必出自同一次推进序列
 *    （修复口径：全部读写经实例内部锁，[PlaybackStateMachine.advance] 与 encode 互斥）；
 * 2. 每次 `advance` 返回 true **恰好推进一次**（与 onEvent 并发下累计不失不重）；
 * 3. 跨线程可见性：一号线程写入的累计量，另一号线程 join 后恒可读到。
 *
 * 竞态窗口属非确定性——用高频并发采样钉死不变量；结构性保证见 PlaybackStateMachine 内部锁。
 * 纯 JVM 可执行。
 */
class PlaybackStateMachineConcurrencyTest {

    /** 固定步长 (1.0m, 500ms)：不变量 distanceM == sampleIndex·1.0、elapsedMs == sampleIndex·500。 */
    @Test
    fun encodeNeverTearsWhileConcurrentAdvanceRuns() {
        val machine = playingMachine()
        val stepM = 1.0
        val stepMs = 500L
        val writers = 4
        val perWriter = 2_500
        val done = AtomicBoolean(false)
        val torn = CopyOnWriteArrayList<String>()

        val reader = thread(name = "snapshot-reader") {
            while (!done.get()) {
                val encoded = machine.encode()
                val parts = encoded.split("|")
                val index = parts[2].toInt()
                val distance = parts[3].toDouble()
                val elapsed = parts[4].toLong()
                // 撕裂判据：三项累计量必须同源一致（sampleIndex 已 +1 而 distanceM/elapsedMs 未 + 即撕裂）。
                if (distance != index * stepM || elapsed != index * stepMs) {
                    torn.add(encoded)
                }
            }
        }
        val writersJoined = (1..writers).map { w ->
            thread(name = "advancer-$w") { repeat(perWriter) { machine.advance(stepM, stepMs) } }
        }
        writersJoined.forEach { it.join() }
        done.set(true)
        reader.join()

        assertEquals("并发推进下 encode 快照不得撕裂：$torn", emptyList<String>(), torn.toList())
        val expectedIndex = writers * perWriter
        assertEquals(expectedIndex, machine.sampleIndex)
        assertEquals(expectedIndex * stepM, machine.distanceM, 1e-9)
        assertEquals(expectedIndex * stepMs, machine.elapsedMs)
        // 终态快照与取值器同源一致（同一内部锁读出）。
        assertEquals("v1|PLAYING|$expectedIndex|${expectedIndex * stepM}|${expectedIndex * stepMs}", machine.encode())
    }

    /** 与迁移并发时，每次 advance 返回 true 恰好推进一次（不失不重）。 */
    @Test
    fun everyAcceptedAdvanceIncrementsExactlyOnceUnderConcurrency() {
        val machine = playingMachine()
        val stepM = 1.0
        val stepMs = 500L
        val accepted = AtomicLong(0)
        val done = AtomicBoolean(false)

        // 主线程动作处理的等价扰动：PAUSE/RESUME 连发。
        val toggler = thread(name = "main-actions") {
            while (!done.get()) {
                if (machine.onEvent(PlaybackEvent.PAUSE)) {
                    machine.onEvent(PlaybackEvent.RESUME)
                }
            }
        }
        val workers = (1..4).map { w ->
            thread(name = "loop-$w") {
                repeat(2_000) {
                    if (machine.advance(stepM, stepMs)) accepted.incrementAndGet()
                }
            }
        }
        workers.forEach { it.join() }
        done.set(true)
        toggler.join()

        assertEquals("accepted 次数必须恰等于 sampleIndex", accepted.get(), machine.sampleIndex.toLong())
        assertEquals(accepted.get().toDouble(), machine.distanceM, 1e-9)
        assertEquals(accepted.get() * stepMs, machine.elapsedMs)
    }

    /** 跨线程可见性：一号线程推进后，另一号线程读取恒见全部累计（内部锁 happens-before）。 */
    @Test
    fun accumulatorReadsAreVisibleAcrossThreads() {
        val machine = playingMachine()
        repeat(1_000) { assertTrue(machine.advance(1.0, 500L)) }

        val seenIndex = AtomicInteger(-1)
        val seenDistance = java.util.concurrent.atomic.AtomicReference(Double.NaN)
        val seenElapsed = AtomicLong(-1L)
        val reader = thread(name = "reader") {
            seenIndex.set(machine.sampleIndex)
            seenDistance.set(machine.distanceM)
            seenElapsed.set(machine.elapsedMs)
        }
        reader.join()
        assertEquals(1_000, seenIndex.get())
        assertEquals(1_000.0, seenDistance.get(), 1e-9)
        assertEquals(500_000L, seenElapsed.get())
    }

    private fun playingMachine(): PlaybackStateMachine {
        val machine = PlaybackStateMachine()
        assertTrue(machine.onEvent(PlaybackEvent.START))
        return machine
    }
}
