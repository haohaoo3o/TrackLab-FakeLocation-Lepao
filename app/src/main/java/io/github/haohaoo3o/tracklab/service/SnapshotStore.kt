package io.github.haohaoo3o.tracklab.service

/**
 * 回放快照持久化端口（docs/CONTRACTS.md §7）。
 *
 * 生产实现：[PrefsSnapshotStore]（SharedPreferences("playback_state")，仅 service 层）。
 * 测试替身：`InMemorySnapshotStore`（app/src/test，纯 JVM）。
 * 纯 JVM 测试禁止引用 [PrefsSnapshotStore]（android.jar stub 在 JVM 单测下不可执行）。
 *
 * PlaybackStateMachineTest 重建语义：fake 中的字符串快照 → `fromSnapshot` → 状态一律
 * [PAUSED][PlaybackState]（不自动续跑），累计值恢复。
 */
interface SnapshotStore {

    /** 读取快照字符串；无快照返回 null。 */
    fun load(): String?

    /** 原子写入快照字符串（覆盖）。 */
    fun save(snapshot: String)

    /** 清除快照（Stop/完成时调用）。*/
    fun clear()
}
