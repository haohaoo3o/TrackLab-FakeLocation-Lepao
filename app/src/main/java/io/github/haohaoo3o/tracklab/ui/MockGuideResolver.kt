package io.github.haohaoo3o.tracklab.ui

/**
 * Mock 引导残留清除器。
 *
 * 背景：开始入口在运行时权限齐全、但模拟位置应用尚未选中（`mockSelected=false`，
 * 应用启动初值；公开 API 无查询接口）时，会投机性展示 mock 引导并继续启动回放；
 * 服务侧 `start()` 成功（会话 ACTIVE、mock 出口推帧中）后 `mockGuidance=false`，
 * 而状态行旧逻辑只在 `mockGuidance` 上升沿写引导、从不清除投机性残留，
 * 于是出现「状态行仍显示引导」与「指标行已在推进」同屏矛盾。
 *
 * 本类为纯 Kotlin（零 Android 依赖，JVM 可测）：把「引导是否正展示」与
 * 「服务引导上一帧」记成显式状态，调用方按返回值写 `txt_map_status`：
 * - [onSpeculativeGuideShown]：开始入口投机性展示引导后标记可见（幂等）；
 * - [onFrame]：服务 GUIDANCE 上升沿返回 `"mock_location_guide"`；
 *   非引导 + 曾可见 + 会话在 + mock 出口推帧中（恢复）返回
 *   `"map_hint_playback_requested"`（与 READY 路径同文案）；其余返回 null（不写屏，避免刷屏）。
 *
 * 键名与 [PermissionGate.guidanceMessageKey] / `map_hint_playback_requested`
 * 同源（字符串键，调用方映射 R.string；不新增文案键，双语同步不受影响）。
 */
class MockGuideResolver {

    /** 引导文案是否正展示在状态行（投机性展示或服务 GUIDANCE 上升沿置位）。 */
    var guideVisible: Boolean = false
        private set

    /** 服务侧上一帧的 mockGuidance（上升沿检测）。 */
    private var lastGuidance: Boolean = false

    /** 开始入口已投机性展示 mock 引导（MISSING_MOCK_SELECTION 路径）→ 标记可见。幂等。 */
    fun onSpeculativeGuideShown() {
        guideVisible = true
    }

    /**
     * 每帧调用（调用方经 PlaybackBus 收集）。
     *
     * @param mockGuidance 服务帧 `mockGuidance`（`output.needsGuidance()`）。
     * @param sessionActive 服务帧 `sessionActive`。
     * @param mockOutputActive 服务帧 `mockOutputActive`（`output.outputEnabled`）。
     * @return 应写入状态行的文案键（`"mock_location_guide"` /
     *   `"map_hint_playback_requested"`），无变化返回 null。
     */
    fun onFrame(mockGuidance: Boolean, sessionActive: Boolean, mockOutputActive: Boolean): String? {
        if (mockGuidance && !lastGuidance) {
            lastGuidance = true
            guideVisible = true
            return "mock_location_guide"
        }
        lastGuidance = mockGuidance
        if (!mockGuidance && guideVisible && sessionActive && mockOutputActive) {
            guideVisible = false
            return "map_hint_playback_requested"
        }
        return null
    }
}
