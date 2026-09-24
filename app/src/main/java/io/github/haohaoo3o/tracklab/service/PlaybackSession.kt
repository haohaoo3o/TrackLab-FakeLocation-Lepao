package io.github.haohaoo3o.tracklab.service

import io.github.haohaoo3o.tracklab.core.model.TrackSample

/**
 * 一次测试回放会话（进程内传递载体）。
 *
 * 轨迹样本列表可能达数万点（Δt=0.5s 逐点，§3），超过 Binder Intent 传输上限；
 * 服务与入口同进程（本应用组件），故经 [PlaybackSessionStore] 进程内传递而不走序列化。
 * **进程重建后安全恢复**：本载体随进程消亡（[PlaybackSessionStore.clear] 于服务 onDestroy），
 * 快照（[SnapshotStore]）只恢复累计值并置 PAUSED（不自动续跑）——绝不基于残留会话重启。
 */
data class PlaybackSession(
    val samples: List<TrackSample>,
    val laps: Int,
    val pBaseSecPerKm: Int,
    val seed: Long,
)

/**
 * 进程级会话暂存（[PlaybackSession] 载体；service 层唯一写入者 = [PlaybackForegroundService] /
 * [ServicePlaybackEntry]，onDestroy 清除——完整资源清理）。
 */
object PlaybackSessionStore {

    @Volatile
    private var session: PlaybackSession? = null

    fun set(session: PlaybackSession) {
        this.session = session
    }

    fun peek(): PlaybackSession? = session

    fun clear() {
        session = null
    }
}
