package io.github.haohaoo3o.tracklab.service

import android.content.Context

/**
 * [SnapshotStore] 的 SharedPreferences 实现（docs/CONTRACTS.md §7.2）。
 * 文件名 [ServiceContracts.PREFS_PLAYBACK]（"playback_state"）、键 [ServiceContracts.KEY_SNAPSHOT]、
 * MODE_PRIVATE。本类是唯一允许存在于 service 层的 SharedPreferences 快照实现；
 * 纯 JVM 测试一律使用 InMemorySnapshotStore，禁止引用本类。
 */
class PrefsSnapshotStore(context: Context) : SnapshotStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(ServiceContracts.PREFS_PLAYBACK, Context.MODE_PRIVATE)

    override fun load(): String? =
        prefs.getString(ServiceContracts.KEY_SNAPSHOT, null)

    override fun save(snapshot: String) {
        prefs.edit().putString(ServiceContracts.KEY_SNAPSHOT, snapshot).apply()
    }

    override fun clear() {
        prefs.edit().remove(ServiceContracts.KEY_SNAPSHOT).apply()
    }
}
