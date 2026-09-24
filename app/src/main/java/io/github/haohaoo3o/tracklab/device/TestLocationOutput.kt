package io.github.haohaoo3o.tracklab.device

import io.github.haohaoo3o.tracklab.core.model.TrackSample

/**
 * 官方 Mock Location 输出驱动（docs/CONTRACTS.md §6）——纯 Kotlin，只经 [TestLocationSink]
 * 端口驱动（4 方法序列 add/set/setEnabled/remove），**无其他通道**（无隐藏 mock 路径）。
 *
 * 状态机：`IDLE →(start)→ ACTIVE`；`ACTIVE ⇄（setEnabled）`；`ACTIVE →(stop)→ IDLE`；
 * **任一端口调用抛 SecurityException → 显式引导态 [State.GUIDANCE]**（未在开发者选项中
 * 选择本应用为模拟位置信息应用时的官方行为）——引导文案 `mock_location_guide`，由 UI 显式展示，
 * 不做任何绕过。[reset] 仅本地复位（不再触碰端口）。
 *
 * **线程安全 + 暂停闸门**：全部公开方法经实例内部锁（服务侧 Default 回放线程
 * [emit] 与主线程 [setOutputEnabled]/[stop] 并发调用）。[emit] 仅在 **ACTIVE 且输出已启用**
 * （[outputEnabled]）时推帧——暂停（setEnabled(false)）返回后任何在飞/后续 emit 一律不推帧
 * （『暂停后仍多发一帧 mock 位置』的确定性封口）。
 *
 * 本类零 Android 依赖：单测注入 fake 端口纯 JVM 断言序列与引导态；
 * 生产侧注入 `LocationManagerTestLocationSink`。
 */
class TestLocationOutput(
    private val sink: TestLocationSink,
    private val providerName: String,
) {

    /** 驱动态。GUIDANCE = 显式引导态（SecurityException 后）。 */
    enum class State { IDLE, ACTIVE, GUIDANCE }

    private var currentState: State = State.IDLE
    private var currentOutputEnabled: Boolean = false

    /** 当前状态（内部锁读，跨线程可见）。 */
    val state: State get() = synchronized(this) { currentState }

    /** 输出是否已启用（setEnabled(name, true/false) 的当前值；内部锁读，跨线程可见）。 */
    val outputEnabled: Boolean get() = synchronized(this) { currentOutputEnabled }

    /**
     * 注册并启用测试提供者：端口序列 `add(name)` → `setEnabled(name, true)`。
     * 成功 → [State.ACTIVE]；SecurityException → [State.GUIDANCE]，返回 false。
     */
    @Synchronized
    fun start(): Boolean {
        if (currentState == State.ACTIVE) return true
        return try {
            sink.add(providerName)
            sink.setEnabled(providerName, true)
            currentOutputEnabled = true
            currentState = State.ACTIVE
            true
        } catch (e: SecurityException) {
            currentOutputEnabled = false
            currentState = State.GUIDANCE
            false
        }
    }

    /**
     * 推送一帧模拟位置（端口序列 `set(name, sample)`）。
     * 仅 [State.ACTIVE] **且输出已启用**（[outputEnabled]）推送——暂停（setEnabled false）后
     * 在飞/后续 emit 一律不推帧（回归：暂停后多发一帧 mock 位置）；
     * SecurityException → [State.GUIDANCE]，返回 false。
     */
    @Synchronized
    fun emit(sample: TrackSample): Boolean {
        if (currentState != State.ACTIVE || !currentOutputEnabled) return false
        return try {
            sink.set(providerName, sample)
            true
        } catch (e: SecurityException) {
            currentOutputEnabled = false
            currentState = State.GUIDANCE
            false
        }
    }

    /**
     * 启用/停用输出（端口序列 `setEnabled(name, enabled)`；暂停/继续的 mock 出口对应）。
     * 仅 [State.ACTIVE] 允许；SecurityException → [State.GUIDANCE]，返回 false。
     * （内部锁与 [emit] 互斥：本方法返回 false（停用）后，任何 emit 都不再推帧。）
     */
    @Synchronized
    fun setOutputEnabled(enabled: Boolean): Boolean {
        if (currentState != State.ACTIVE) return false
        return try {
            sink.setEnabled(providerName, enabled)
            currentOutputEnabled = enabled
            true
        } catch (e: SecurityException) {
            currentOutputEnabled = false
            currentState = State.GUIDANCE
            false
        }
    }

    /**
     * 停止并清理测试提供者（端口序列 `setEnabled(name, false)`（若仍启用）→ `remove(name)`）。
     * 成功 → [State.IDLE]；SecurityException → [State.GUIDANCE]（清理仍属显式路径），返回 false。
     * 完整资源清理由服务 onDestroy 调用。
     */
    @Synchronized
    fun stop(): Boolean {
        if (currentState == State.IDLE) return true
        return try {
            if (currentOutputEnabled) {
                sink.setEnabled(providerName, false)
                currentOutputEnabled = false
            }
            sink.remove(providerName)
            currentState = State.IDLE
            true
        } catch (e: SecurityException) {
            currentOutputEnabled = false
            currentState = State.GUIDANCE
            false
        }
    }

    /** 显式引导态复位（仅本地状态，不触碰端口）：GUIDANCE → IDLE。 */
    @Synchronized
    fun reset() {
        if (currentState == State.GUIDANCE) {
            currentState = State.IDLE
            currentOutputEnabled = false
        }
    }

    /** 是否处于显式引导态（UI 展示 `mock_location_guide` 的唯一判据）。 */
    fun needsGuidance(): Boolean = state == State.GUIDANCE
}
