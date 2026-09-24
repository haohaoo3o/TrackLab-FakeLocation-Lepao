package io.github.haohaoo3o.tracklab.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 断言契约见 docs/CONTRACTS.md §7（状态机全迁移/非法迁移/累计正确；InMemorySnapshotStore
 * 字符串→fromSnapshot→一律 PAUSED（不自动续跑）——纯 JVM 可执行，不再断言 SharedPreferences）。
 */
class PlaybackStateMachineTest {

    // ---------------------------------------------------------------- §7.1 合法迁移（全覆盖）

    @Test
    fun legalTransitionsAllAccepted() {
        val start = newMachine()
        assertTrue("IDLE+START 合法", start.onEvent(PlaybackEvent.START))
        assertEquals(PlaybackState.PLAYING, start.state)

        val pause = atState(PlaybackState.PLAYING)
        assertTrue("PLAYING+PAUSE 合法", pause.onEvent(PlaybackEvent.PAUSE))
        assertEquals(PlaybackState.PAUSED, pause.state)

        val resume = atState(PlaybackState.PAUSED)
        assertTrue("PAUSED+RESUME 合法", resume.onEvent(PlaybackEvent.RESUME))
        assertEquals(PlaybackState.PLAYING, resume.state)

        val stopPlaying = atState(PlaybackState.PLAYING)
        assertTrue("PLAYING+STOP 合法", stopPlaying.onEvent(PlaybackEvent.STOP))
        assertEquals(PlaybackState.STOPPED, stopPlaying.state)

        val stopPaused = atState(PlaybackState.PAUSED)
        assertTrue("PAUSED+STOP 合法", stopPaused.onEvent(PlaybackEvent.STOP))
        assertEquals(PlaybackState.STOPPED, stopPaused.state)

        val complete = atState(PlaybackState.PLAYING)
        assertTrue("PLAYING+COMPLETE 合法", complete.onEvent(PlaybackEvent.COMPLETE))
        assertEquals(PlaybackState.COMPLETED, complete.state)
    }

    // ---------------------------------------------------------------- §7.1 非法迁移（断言全覆盖：5 状态 × 5 事件 = 25 格）

    @Test
    fun illegalTransitionsAllRejectedAndKeepState() {
        val legal = setOf(
            Triple(PlaybackState.IDLE, PlaybackEvent.START, PlaybackState.PLAYING),
            Triple(PlaybackState.PLAYING, PlaybackEvent.PAUSE, PlaybackState.PAUSED),
            Triple(PlaybackState.PAUSED, PlaybackEvent.RESUME, PlaybackState.PLAYING),
            Triple(PlaybackState.PLAYING, PlaybackEvent.STOP, PlaybackState.STOPPED),
            Triple(PlaybackState.PAUSED, PlaybackEvent.STOP, PlaybackState.STOPPED),
            Triple(PlaybackState.PLAYING, PlaybackEvent.COMPLETE, PlaybackState.COMPLETED),
        )
        var illegalCount = 0
        for (state in PlaybackState.entries) {
            for (event in PlaybackEvent.entries) {
                val m = atState(state)
                val accepted = m.onEvent(event)
                val expected = legal.firstOrNull { it.first == state && it.second == event }
                if (expected == null) {
                    illegalCount++
                    assertFalse("非法迁移应被拒绝：$state + $event", accepted)
                    assertEquals("非法迁移应保持原状态：$state + $event", state, m.state)
                } else {
                    assertTrue("合法迁移应被接受：$state + $event", accepted)
                    assertEquals(expected.third, m.state)
                }
            }
        }
        // 全表 25 格中非法 19 格（钉死数量，防漏覆盖）
        assertEquals("非法迁移格数（25 − 6 合法）", 19, illegalCount)
    }

    // ---------------------------------------------------------------- §7.1 累计量仅 PLAYING 推进

    @Test
    fun accumulatorsAdvanceOnlyWhilePlaying() {
        // 非 PLAYING 状态一律不推进（IDLE/PAUSED/STOPPED/COMPLETED 全覆盖）
        for (state in listOf(PlaybackState.IDLE, PlaybackState.PAUSED, PlaybackState.STOPPED, PlaybackState.COMPLETED)) {
            val m = atState(state)
            val beforeIndex = m.sampleIndex
            val beforeDistance = m.distanceM
            val beforeElapsed = m.elapsedMs
            assertFalse("非 PLAYING 不推进：$state", m.advance(1.5, 500L))
            assertEquals(beforeIndex, m.sampleIndex)
            assertEquals(beforeDistance, m.distanceM, 0.0)
            assertEquals(beforeElapsed, m.elapsedMs)
        }
        // PLAYING 累计正确（多次推进求和）
        val m = atState(PlaybackState.PLAYING)
        assertTrue(m.advance(1.5, 500L))
        assertTrue(m.advance(2.5, 500L))
        assertEquals(2, m.sampleIndex)
        assertEquals(4.0, m.distanceM, 1e-12)
        assertEquals(1000L, m.elapsedMs)
    }

    @Test
    fun advanceRejectsNegativeSteps() {
        val m = atState(PlaybackState.PLAYING)
        assertThrowsIllegalArgument { m.advance(-0.1, 500L) }
        assertThrowsIllegalArgument { m.advance(1.0, -1L) }
    }

    // ---------------------------------------------------------------- 快照编码 / fromSnapshot 一律 PAUSED

    @Test
    fun encodeUsesPinnedFormat() {
        val m = atState(PlaybackState.PLAYING)
        assertTrue(m.advance(1.5, 500L))
        assertTrue(m.advance(2.5, 500L))
        // "v1|<state>|<sampleIndex>|<distanceM>|<elapsedMs>"（distanceM=Double.toString、elapsedMs 十进制）
        assertEquals("v1|PLAYING|2|4.0|1000", m.encode())
    }

    @Test
    fun fromSnapshotAlwaysPausedAndRestoresAccumulators() {
        for (snapState in PlaybackState.entries) {
            val restored = PlaybackStateMachine.fromSnapshot("v1|$snapState|12|123.5|6000")
            assertEquals("fromSnapshot 状态一律 PAUSED（编码态=$snapState）", PlaybackState.PAUSED, restored.state)
            assertEquals(12, restored.sampleIndex)
            assertEquals(123.5, restored.distanceM, 0.0)
            assertEquals(6000L, restored.elapsedMs)
        }
    }

    @Test
    fun fromSnapshotRejectsCorruptEncodings() {
        val corrupt = listOf(
            "",                                      // 空
            "v1|PLAYING|12|123.5",                   // 少一段
            "v1|PLAYING|12|123.5|6000|extra",        // 第 5 段混入分隔符（elapsed 解析失败）
            "v2|PLAYING|12|123.5|6000",              // 版本不符
            "v1|RUNNING|12|123.5|6000",              // 非法状态名
            "v1|PLAYING|x|123.5|6000",               // sampleIndex 非整数
            "v1|PLAYING|-1|123.5|6000",              // sampleIndex 负
            "v1|PLAYING|12|abc|6000",                // distanceM 非数值
            "v1|PLAYING|12|-3.0|6000",               // distanceM 负
            "v1|PLAYING|12|NaN|6000",                // distanceM 非有限
            "v1|PLAYING|12|123.5|y",                 // elapsedMs 非整数
            "v1|PLAYING|12|123.5|-5",                // elapsedMs 负
        )
        for (encoded in corrupt) {
            try {
                PlaybackStateMachine.fromSnapshot(encoded)
                fail("损坏快照应抛 IllegalArgumentException：$encoded")
            } catch (e: IllegalArgumentException) {
                assertTrue("解析错误须中文可读消息：$encoded → ${e.message}", e.message.orEmpty().contains("快照"))
            }
        }
    }

    // ---------------------------------------------------------------- InMemorySnapshotStore：fake 字符串 → fromSnapshot → PAUSED

    @Test
    fun inMemorySnapshotStoreRoundTripRestoresPaused() {
        val store = InMemorySnapshotStore()
        store.save("v1|PLAYING|25|250.0|12500")
        val loaded = store.load()
        assertEquals("v1|PLAYING|25|250.0|12500", loaded)
        val restored = PlaybackStateMachine.fromSnapshot(loaded!!)
        assertEquals(PlaybackState.PAUSED, restored.state)
        assertEquals(25, restored.sampleIndex)
        assertEquals(250.0, restored.distanceM, 0.0)
        assertEquals(12500L, restored.elapsedMs)
        // Stop/完成即 clear（store 侧语义）
        store.clear()
        assertEquals(null, store.load())
    }

    // ---------------------------------------------------------------- §7.1 常量值契约（不 import android.*，纯 JVM 可引用）

    @Test
    fun serviceContractConstantValues() {
        assertEquals(0x04000000, ServiceContracts.FLAG_IMMUTABLE)
        assertEquals(2, ServiceContracts.START_NOT_STICKY_VALUE)
        assertEquals(8, ServiceContracts.FOREGROUND_SERVICE_TYPE_LOCATION)
        assertEquals(25, ServiceContracts.SNAPSHOT_THROTTLE_SAMPLES)
        assertEquals("v1", ServiceContracts.SNAPSHOT_FORMAT_VERSION)
        // 平台常量与值契约的等同性（FLAG_IMMUTABLE / START_NOT_STICKY 调用点用平台常量过 lint int-def）：
        assertEquals(ServiceContracts.FLAG_IMMUTABLE, android.app.PendingIntent.FLAG_IMMUTABLE)
        assertEquals(ServiceContracts.START_NOT_STICKY_VALUE, android.app.Service.START_NOT_STICKY)
    }

    @Test
    fun notificationPendingIntentsAreFlagImmutableInSource() {
        // 静态护栏（实机行为仍属人工核对）：服务源码中全部 PendingIntent
        // 创建点必带 FLAG_IMMUTABLE，且全文件无 FLAG_MUTABLE。
        var dir = java.io.File(System.getProperty("user.dir") ?: ".").absoluteFile
        var source: java.io.File? = null
        for (step in 0 until 6) {
            val candidate = java.io.File(dir, "app/src/main/java/io/github/haohaoo3o/tracklab/service/PlaybackForegroundService.kt")
            if (candidate.isFile) {
                source = candidate
                break
            }
            dir = dir.parentFile ?: break
        }
        val text = source?.readText() ?: error("找不到 PlaybackForegroundService.kt")
        val creationPoints = Regex("PendingIntent\\.(getService|getActivity)\\(").findAll(text).count()
        assertEquals("PendingIntent 创建点应恰 2 处（动作+内容）", 2, creationPoints)
        val immutableUses = Regex("FLAG_IMMUTABLE").findAll(text).count()
        assertTrue("每个创建点必带 FLAG_IMMUTABLE（≥2 处使用）：$immutableUses", immutableUses >= 2)
        assertFalse("禁止 FLAG_MUTABLE", text.contains("FLAG_MUTABLE"))
    }

    // ---------------------------------------------------------------- helpers

    private fun newMachine(): PlaybackStateMachine = PlaybackStateMachine()

    /** 走合法迁移构造目标状态的新机（IDLE=新机；其余经事件序列到达——终态不可二次进入属契约）。 */
    private fun atState(state: PlaybackState): PlaybackStateMachine {
        val m = newMachine()
        when (state) {
            PlaybackState.IDLE -> Unit
            PlaybackState.PLAYING -> m.onEvent(PlaybackEvent.START)
            PlaybackState.PAUSED -> {
                m.onEvent(PlaybackEvent.START)
                m.onEvent(PlaybackEvent.PAUSE)
            }
            PlaybackState.STOPPED -> {
                m.onEvent(PlaybackEvent.START)
                m.onEvent(PlaybackEvent.STOP)
            }
            PlaybackState.COMPLETED -> {
                m.onEvent(PlaybackEvent.START)
                m.onEvent(PlaybackEvent.COMPLETE)
            }
        }
        assertEquals(state, m.state)
        return m
    }

    private fun assertThrowsIllegalArgument(block: () -> Unit) {
        try {
            block()
            fail("应抛 IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // 预期
        }
    }
}
