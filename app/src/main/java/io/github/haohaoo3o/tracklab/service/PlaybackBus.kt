package io.github.haohaoo3o.tracklab.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 回放 UI 状态（悬浮窗 HUD / 通知 / 主界面同源展示；当前圈数/距离/配速/步频）。
 *
 * 纯 Kotlin 数据类（PlaybackRenderer + PlaybackBus 由 `repeatOnLifecycle(STARTED)` 收集）。
 * [currentLap] 为展示口径（1-based，0=无回放）：`floor(distanceM / lapLengthM) + 1` 钳到 [totalLaps]，
 * lapLengthM = 生成序列总推进距离 / laps（圈等长 L，展示级精度）。
 */
data class PlaybackUiState(
    val state: PlaybackState = PlaybackState.IDLE,
    val sessionActive: Boolean = false,
    val sampleIndex: Int = 0,
    val totalSamples: Int = 0,
    val totalLaps: Int = 0,
    val currentLap: Int = 0,
    val distanceM: Double = 0.0,
    val paceSecPerKm: Double = 0.0,
    val cadenceSpm: Double = 0.0,
    val latitudeDeg: Double? = null,
    val longitudeDeg: Double? = null,
    val mockOutputActive: Boolean = false,
    val mockGuidance: Boolean = false,
)

/**
 * 回放状态总线：服务发布、UI 侧 `repeatOnLifecycle(STARTED)` 收集。
 * 挂入 `di/AppContainer.playbackBus`。StateFlow 天然『当前值 + 后续更新』，
 * 重建 UI（Activity 重建/进程恢复）时直接拿到最新快照。
 */
class PlaybackBus {

    private val mutableState = MutableStateFlow(PlaybackUiState())

    /** 只读状态流（UI 收集面）。 */
    val state: StateFlow<PlaybackUiState> = mutableState.asStateFlow()

    /** 发布新状态（服务侧每帧/每次状态迁移调用）。 */
    fun publish(state: PlaybackUiState) {
        mutableState.value = state
    }

    /** 当前值（同步读取）。 */
    fun current(): PlaybackUiState = mutableState.value
}
