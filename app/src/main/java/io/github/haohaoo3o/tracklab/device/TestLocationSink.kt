package io.github.haohaoo3o.tracklab.device

import io.github.haohaoo3o.tracklab.core.model.TrackSample

/**
 * 官方测试提供者（Mock Location）输出端口（docs/CONTRACTS.md §6，4 方法钉死）。
 *
 * - `add(name)` / `set(name, sample)` / `setEnabled(name, enabled)` / `remove(name)`——**仅此 4 方法**；
 *   实现可抛 SecurityException（未在『开发者选项 → 选择模拟位置信息应用』中选中本应用时，
 *   官方测试提供者 API 一律拒绝）。
 * - 生产实现 `LocationManagerTestLocationSink`（Android 侧，LocationManager 测试提供者官方 API）；
 *   单测用 fake 端口（纯 JVM）。
 * - **无隐藏 mock 路径**：本端口是唯一输出通道；不隐藏 mock 标志、不 Hook、不影响其他应用。
 */
interface TestLocationSink {

    /** 注册测试提供者 [name]（官方 addTestProvider 语义）。可抛 SecurityException。 */
    fun add(name: String)

    /** 向测试提供者 [name] 推送一个模拟位置样本 [sample]（WGS-84）。可抛 SecurityException。 */
    fun set(name: String, sample: TrackSample)

    /** 启用/停用测试提供者 [name]。可抛 SecurityException。 */
    fun setEnabled(name: String, enabled: Boolean)

    /** 移除测试提供者 [name]（完整清理路径）。可抛 SecurityException。 */
    fun remove(name: String)
}
