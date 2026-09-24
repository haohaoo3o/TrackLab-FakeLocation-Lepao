package io.github.haohaoo3o.tracklab.service

/**
 * 回放状态机（docs/CONTRACTS.md §7.1）——**纯 Kotlin，零 Android 依赖**（纯 JVM 可执行）。
 *
 * 状态：IDLE / PLAYING / PAUSED / STOPPED / COMPLETED；事件：START / PAUSE / RESUME / STOP / COMPLETE。
 * 合法迁移（其余全部为非法迁移——拒绝并保持原状态）：
 * - IDLE→PLAYING（START）
 * - PLAYING→PAUSED（PAUSE）
 * - PAUSED→PLAYING（RESUME）
 * - PLAYING|PAUSED→STOPPED（STOP）
 * - PLAYING→COMPLETED（COMPLETE，样本耗尽自动）
 *
 * 累计量 [sampleIndex] / [distanceM] / [elapsedMs] **仅 PLAYING 推进**（[advance]）。
 *
 * **线程安全**：服务侧主线程动作处理（迁移/快照）与 Default 回放线程
 * （advance/encode/读取）并发访问同一实例——全部读（四个取值器）写（[onEvent]/[advance]/
 * [encode]）经实例内部锁：[encode] 与 [advance] 互斥，快照**永不撕裂**（sampleIndex/distanceM/
 * elapsedMs 必出自同一次推进序列），跨线程可见性由锁的 happens-before 保证。
 *
 * 快照：编码 `"v1|<state>|<sampleIndex>|<distanceM>|<elapsedMs>"`（`|` 分隔，
 * distanceM 用 Double.toString、elapsedMs 用十进制 Long；解析失败抛 IllegalArgumentException）。
 * [fromSnapshot] 恢复累计值，状态**一律置 PAUSED（不自动续跑）**。
 */
enum class PlaybackState { IDLE, PLAYING, PAUSED, STOPPED, COMPLETED }

/** 回放事件（§7.1）。 */
enum class PlaybackEvent { START, PAUSE, RESUME, STOP, COMPLETE }

class PlaybackStateMachine private constructor(
    initialState: PlaybackState,
    initialSampleIndex: Int,
    initialDistanceM: Double,
    initialElapsedMs: Long,
) {

    /** 初始构造：IDLE、累计量全 0。 */
    constructor() : this(PlaybackState.IDLE, 0, 0.0, 0L)

    private var currentState: PlaybackState = initialState
    private var currentSampleIndex: Int = initialSampleIndex
    private var currentDistanceM: Double = initialDistanceM
    private var currentElapsedMs: Long = initialElapsedMs

    /** 当前状态（内部锁读，跨线程可见）。 */
    val state: PlaybackState get() = synchronized(this) { currentState }

    /** 已推进样本数（仅 PLAYING 推进；内部锁读，跨线程可见）。 */
    val sampleIndex: Int get() = synchronized(this) { currentSampleIndex }

    /** 已推进距离（米，仅 PLAYING 推进；内部锁读，跨线程可见）。 */
    val distanceM: Double get() = synchronized(this) { currentDistanceM }

    /** 已推进时长（毫秒，仅 PLAYING 推进；内部锁读，跨线程可见）。 */
    val elapsedMs: Long get() = synchronized(this) { currentElapsedMs }

    /**
     * 投递事件。合法迁移推进状态并返回 true；**非法迁移拒绝并保持原状态**，返回 false（§7.1）。
     * （内部锁：与 [advance]/[encode] 互斥，可跨线程调用。）
     */
    @Synchronized
    fun onEvent(event: PlaybackEvent): Boolean {
        val target = when (currentState to event) {
            PlaybackState.IDLE to PlaybackEvent.START -> PlaybackState.PLAYING
            PlaybackState.PLAYING to PlaybackEvent.PAUSE -> PlaybackState.PAUSED
            PlaybackState.PAUSED to PlaybackEvent.RESUME -> PlaybackState.PLAYING
            PlaybackState.PLAYING to PlaybackEvent.STOP -> PlaybackState.STOPPED
            PlaybackState.PAUSED to PlaybackEvent.STOP -> PlaybackState.STOPPED
            PlaybackState.PLAYING to PlaybackEvent.COMPLETE -> PlaybackState.COMPLETED
            else -> return false
        }
        currentState = target
        return true
    }

    /**
     * 推进一帧累计量（**仅 [PlaybackState.PLAYING]**；其余状态不推进、返回 false——『仅 PLAYING 推进』）。
     * （内部锁：三个累计量同锁整体写入，[encode] 不会读到撕裂中间态。）
     *
     * @param stepDistanceM 本步距离（米；生成管线口径 v·Δt，MotionClock.stepDistanceM）
     * @param stepElapsedMs 本步时长（毫秒；Δt=0.5s ⇒ 500，§3）
     */
    @Synchronized
    fun advance(stepDistanceM: Double, stepElapsedMs: Long): Boolean {
        if (currentState != PlaybackState.PLAYING) return false
        require(stepDistanceM >= 0.0) { "推进步长距离不得为负：$stepDistanceM" }
        require(stepElapsedMs >= 0L) { "推进步长时长不得为负：$stepElapsedMs" }
        currentSampleIndex += 1
        currentDistanceM += stepDistanceM
        currentElapsedMs += stepElapsedMs
        return true
    }

    /** 快照编码：`"v1|<state>|<sampleIndex>|<distanceM>|<elapsedMs>"`。
     *  （内部锁：与 [advance] 互斥——并发推进下编码快照三项累计量恒一致，绝不撕裂。） */
    @Synchronized
    fun encode(): String {
        val version = ServiceContracts.SNAPSHOT_FORMAT_VERSION
        return "$version|$currentState|$currentSampleIndex|$currentDistanceM|$currentElapsedMs"
    }

    companion object {

        /**
         * 从快照字符串重建（进程重建安全恢复）：**恢复累计值，状态一律置 PAUSED
         * （不自动续跑）**。
         *
         * @throws IllegalArgumentException 编码损坏 / 版本不符 / 数值非法（中文可读消息）
         */
        fun fromSnapshot(encoded: String): PlaybackStateMachine {
            val parts = encoded.split("|", limit = 5)
            require(parts.size == 5 && parts[0] == ServiceContracts.SNAPSHOT_FORMAT_VERSION) {
                "回放快照格式错误（期望 ${ServiceContracts.SNAPSHOT_FORMAT_VERSION}|… 共 5 段）：$encoded"
            }
            val snapState = runCatching { PlaybackState.valueOf(parts[1]) }.getOrNull()
            requireNotNull(snapState) { "回放快照解析失败：state=${parts[1]}" }
            val index = parts[2].toIntOrNull()
            requireNotNull(index) { "回放快照解析失败：sampleIndex=${parts[2]}" }
            require(index >= 0) { "回放快照解析失败：sampleIndex=$index（应 ≥ 0）" }
            val distance = parts[3].toDoubleOrNull()
            requireNotNull(distance) { "回放快照解析失败：distanceM=${parts[3]}" }
            require(distance.isFinite() && distance >= 0.0) { "回放快照解析失败：distanceM=$distance（应为有限非负）" }
            val elapsed = parts[4].toLongOrNull()
            requireNotNull(elapsed) { "回放快照解析失败：elapsedMs=${parts[4]}" }
            require(elapsed >= 0L) { "回放快照解析失败：elapsedMs=$elapsed（应 ≥ 0）" }
            // 重建语义：状态一律 PAUSED，绝不自动续跑。
            return PlaybackStateMachine(PlaybackState.PAUSED, index, distance, elapsed)
        }
    }
}
