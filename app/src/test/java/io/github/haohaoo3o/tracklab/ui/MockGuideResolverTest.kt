package io.github.haohaoo3o.tracklab.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MockGuideResolver] 的上升沿 / 投机性清除 / 幂等语义回归（纯 JVM，零 Android 依赖）：
 * 断言「状态行残留引导」与「指标行已在推进」的同屏矛盾不再出现；
 * 键名与 [PermissionGate.guidanceMessageKey] 的 `mock_location_guide` 同源。
 */
class MockGuideResolverTest {

    @Test
    fun speculativeGuideIsClearedOnceMockFramesStream() {
        val resolver = MockGuideResolver()
        resolver.onSpeculativeGuideShown()
        assertTrue(resolver.guideVisible)

        // 服务 ACTIVE 恢复推帧：首个非引导帧清除残留，回到 READY 路径文案。
        assertEquals(
            "map_hint_playback_requested",
            resolver.onFrame(mockGuidance = false, sessionActive = true, mockOutputActive = true),
        )
        assertFalse(resolver.guideVisible)

        // 后续推帧不再写屏（避免刷屏）。
        assertNull(resolver.onFrame(mockGuidance = false, sessionActive = true, mockOutputActive = true))
    }

    @Test
    fun serviceGuidanceRisingEdgeStillShowsGuide() {
        val resolver = MockGuideResolver()
        assertEquals(
            "mock_location_guide",
            resolver.onFrame(mockGuidance = true, sessionActive = true, mockOutputActive = false),
        )
        assertTrue(resolver.guideVisible)

        // 同态重复帧不重复写屏（边沿触发）。
        assertNull(resolver.onFrame(mockGuidance = true, sessionActive = true, mockOutputActive = false))
    }

    @Test
    fun guidanceWithoutStreamingKeepsGuideVisible() {
        val resolver = MockGuideResolver()
        resolver.onSpeculativeGuideShown()

        // 服务 GUIDANCE 态（mock 出口未启用）：上升沿幂等重申引导（同文案复写无害），不误清。
        assertEquals(
            "mock_location_guide",
            resolver.onFrame(mockGuidance = true, sessionActive = true, mockOutputActive = false),
        )
        assertTrue(resolver.guideVisible)

        // 会话结束（STOPPED）：不借停止帧清除（停止行由 completed/stopped 逻辑负责），保留原样。
        assertNull(resolver.onFrame(mockGuidance = false, sessionActive = false, mockOutputActive = false))
        assertTrue(resolver.guideVisible)
    }

    @Test
    fun noGuideEverShownNeverWritesStatusLine() {
        val resolver = MockGuideResolver()
        // READY 路径从未展示引导：推帧帧不写屏（状态行保持 map_hint_playback_requested 的起点）。
        assertNull(resolver.onFrame(mockGuidance = false, sessionActive = true, mockOutputActive = true))
        assertFalse(resolver.guideVisible)
    }
}
