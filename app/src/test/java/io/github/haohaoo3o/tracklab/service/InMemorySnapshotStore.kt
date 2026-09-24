package io.github.haohaoo3o.tracklab.service

/**
 * 内存 fake（docs/CONTRACTS.md §7）：纯 JVM 可执行的 [SnapshotStore] 测试替身。
 * PlaybackStateMachineTest 重建语义断言使用本 fake（fake 字符串 → fromSnapshot → 一律 PAUSED）。
 * 纯 JVM 测试禁止引用 PrefsSnapshotStore（android.jar stub 不可执行）。
 */
class InMemorySnapshotStore : SnapshotStore {

    var stored: String? = null

    override fun load(): String? = stored

    override fun save(snapshot: String) {
        stored = snapshot
    }

    override fun clear() {
        stored = null
    }
}
