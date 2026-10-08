package io.github.haohaoo3o.tracklab.device

import io.github.haohaoo3o.tracklab.core.model.TrackSample
import java.util.Random
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 有状态、可复现的**位置帧合成器**（进程内唯一实例 = `object`）：官方 mock 出口
 * （[LocationManagerTestLocationSink]）的每帧全部物理量统一从本合成器取帧——
 * 同一次回放会话共享同一份物理状态（「同一会话、同一事实」）。
 *
 * 物理一致性口径（钉死，逐条可测）：
 * 1. **时间同锚**：会话开始时（[beginSession]）在**同一瞬间**取 `anchorWallClockMs` 与
 *    `anchorElapsedRealtimeNanos`；此后每帧
 *    `wallClockMs = anchorWall + sample.elapsedMs`、
 *    `elapsedRealtimeNanos = anchorNanos + sample.elapsedMs × 1e6`——墙钟/单调时钟/样本时间
 *    三个时钟由**同一会话锚点**导出，帧间 Δwall = ΔelapsedRealtime = Δsample（500ms）恒成立。
 * 2. **位移–speed–bearing–500ms 一致**：`speed = 位移 / Δt`（与真实 GNSS「同一窗口内
 *    位差与测速一致」同构）；`bearing = 位移方向`（位移 < [MIN_BEARING_UPDATE_M] 保持上一帧
 *    方位，抑制原地抖动；首帧 0°）。speed 与样本配速 v 的偏差被运动管线序列界钉死：
 *    `|speed − v| ≤ (STEP_DIST_TOL_REL·v·Δt + STEP_DIST_TOL_ABS_M)/Δt`
 *    （docs/CONTRACTS.md §3.7 序列界：|d − v·Δt| ≤ 0.15·v·Δt + 0.05m）。
 * 3. **精度/高程为低频相关过程**：accuracy / verticalAccuracy / speedAccuracy / bearingAccuracy /
 *    高程抖动全部走与 §3.3「OU 噪声」同款离散化（结点间隔 [PROCESS_NODE_INTERVAL_SEC]、结点间
 *    线性插值、逐结点增量限幅 δ_max），任意 500ms 步的最大变化 ≤ (0.5s/结点间隔)·δ_max——精度
 *    不再是逐帧均匀跳变；高程 = 基准 + 周期 [ALTITUDE_PERIOD_SEC]（≈7 分钟）的慢正弦 + 小幅
 *    OU 抖动。accuracy 带可按 [beginSession] 的可选档位（[ACCURACY_LADDER_TIERS_M]，10/40/60m
 *    档带，用于按不同精度档测试消费端行为）切换——仍是「自然但有界」的低频相关过程，
 *    只是带宽随档位缩放。
 * 4. **可复现**：随机源一律 `java.util.Random`（§3.2 时间与随机源同口径）；固定种子 + 同输入
 *    序列 → 逐帧逐点复现。[frameFor] 按样本记忆推进：同一样本重复查询返回**同一** Frame——
 *    每样本恰好推进一次。
 *
 * 零 Android 依赖；时间锚由 Android 侧注入（JVM 单测用任意锚点值）。
 */
object LocationFrameSynthesizer {

    /** 默认随机种子（沿用旧拟真序列的 0x5EED；同种子跨会话可复现）。 */
    const val DEFAULT_SEED: Long = 0x5EEDL

    /** OU 结点间隔（秒）：低频相关过程的时间粒度。 */
    const val PROCESS_NODE_INTERVAL_SEC: Double = 2.0

    /** OU 回归速率 θ（s⁻¹）：相关时间 τ = 1/θ ≈ 6.7s。 */
    const val PROCESS_THETA_PER_SEC: Double = 0.15

    /** 合成大地高基准（米）。 */
    const val BASE_ALTITUDE_M = 6.0

    /** 高程慢正弦幅度（米；操场尺度）。 */
    const val ALTITUDE_AMPLITUDE_M = 1.0

    /** 高程慢正弦周期（秒；≈7 分钟，操场尺度慢漂）。 */
    const val ALTITUDE_PERIOD_SEC = 420.0

    /** 高程 OU 抖动半幅（米）。 */
    const val ALTITUDE_JITTER_HALF_SPAN_M = 0.3

    /** 位移小于该距离（米）时保持上一帧方位角。 */
    const val MIN_BEARING_UPDATE_M = 1.0

    /** 水平精度中心与半幅（米）⇒ 收敛带 [3, 9]（未指定档位时的默认带）。 */
    const val ACCURACY_CENTER_M = 6.0
    const val ACCURACY_HALF_SPAN_M = 3.0

    /**
     * accuracy 可选档位（10 / 40 / 60 米）：用于按不同精度档测试消费端行为。
     * 档带 = 档位 ± 10%：
     * - 10m 档带 [9,11]（高精度）；
     * - 40m 档带 [36,44]（中等精度）；
     * - 60m 档带 [54,66]（低精度）。
     */
    const val ACCURACY_LADDER_TIER_LOW_M = 10.0
    const val ACCURACY_LADDER_TIER_MID_M = 40.0
    const val ACCURACY_LADDER_TIER_HIGH_M = 60.0

    /** 全部合法档位（[beginSession] 之外取值一律 fail-fast）。 */
    val ACCURACY_LADDER_TIERS_M: List<Double> = listOf(
        ACCURACY_LADDER_TIER_LOW_M,
        ACCURACY_LADDER_TIER_MID_M,
        ACCURACY_LADDER_TIER_HIGH_M,
    )

    /** 档带半幅 = 档位 × 该相对值（10→[9,11]、40→[36,44]、60→[54,66]）。 */
    const val ACCURACY_LADDER_RELATIVE_HALF_SPAN = 0.1

    /**
     * 档位带：`[tier·(1−rel), tier·(1+rel)]`。低频相关过程在带内波动（xMax=半幅），
     * 任意 500ms 步最大变化 ≤ (0.5s/结点间隔)·δ_max——「自然但有界」与默认带同一口径。
     */
    fun accuracyLadderBand(tierM: Double): ClosedFloatingPointRange<Double> {
        require(ACCURACY_LADDER_TIERS_M.contains(tierM)) {
            "accuracy 档位必须是 ${ACCURACY_LADDER_TIERS_M} 之一（tierM=$tierM）"
        }
        val half = tierM * ACCURACY_LADDER_RELATIVE_HALF_SPAN
        return (tierM - half)..(tierM + half)
    }

    /** 垂直精度中心与半幅（米）⇒ [1.3, 3.7]。 */
    const val VERTICAL_ACCURACY_CENTER_M = 2.5
    const val VERTICAL_ACCURACY_HALF_SPAN_M = 1.2

    /** 速度精度中心与半幅（m/s）⇒ [0.05, 0.35]。 */
    const val SPEED_ACCURACY_CENTER_MPS = 0.2
    const val SPEED_ACCURACY_HALF_SPAN_MPS = 0.15

    /** 方位精度中心与半幅（度）⇒ [2, 22]（真实 GNSS 方位精度量级）。 */
    const val BEARING_ACCURACY_CENTER_DEG = 12.0
    const val BEARING_ACCURACY_HALF_SPAN_DEG = 10.0

    /**
     * 一帧合成物理量。lat/lng/pace/cadence 等事实直接取自 [TrackSample]（调用方持有）；
     * 本类型只携带**合成量**与三钟时间戳，供官方 mock 出口写入使用。
     */
    data class Frame(
        val sampleElapsedMs: Long,
        /** = 会话锚墙钟 + sampleElapsedMs。 */
        val wallClockMs: Long,
        /** = 会话锚单调钟 + sampleElapsedMs × 1e6。 */
        val elapsedRealtimeNanos: Long,
        val latitudeDeg: Double,
        val longitudeDeg: Double,
        val altitudeM: Double,
        /** 位移/Δt（m/s，非负）。 */
        val speedMps: Double,
        /** 位移方向（度，[0,360)；位移过小保持上一帧；首帧 0）。 */
        val bearingDeg: Double,
        val accuracyM: Double,
        val verticalAccuracyM: Double,
        val speedAccuracyMps: Double,
        /** 方位精度（度，恒有值——minSdk 26 起 Location API 可用）。 */
        val bearingAccuracyDeg: Double,
        /** 与上一样本的平面位移（米；首帧 0）。 */
        val displacementM: Double,
        /** 与上一样本的样本时间差（秒；首帧 0）。 */
        val sampleDeltaSec: Double,
    )

    // ---------------------------------------------------------------- 会话状态

    private var anchorWallClockMs: Long = 0L
    private var anchorElapsedRealtimeNanos: Long = 0L
    private var processes: Processes? = null
    private var lastFrame: Frame? = null

    /** 是否已有活动会话（[beginSession] 与 [endSession] 之间为 true）。 */
    val sessionActive: Boolean
        get() = synchronized(this) { processes != null }

    /** 最近一帧（无会话或首帧前为 null；测试/展示用，不推进状态）。 */
    fun current(): Frame? = synchronized(this) { lastFrame }

    /**
     * 开始会话：取号后的锚点（wall 与 elapsedRealtime **同一瞬间**采样）+ 种子。
     * 重复调用 = 重开会话（状态全部复位）。
     *
     * @param accuracyLadderM accuracy 可选档位（`null` = 默认收敛带 [3,9]）。
     *   合法取值见 [ACCURACY_LADDER_TIERS_M]，其余值 fail-fast。
     */
    fun beginSession(
        seed: Long,
        anchorWallClockMs: Long,
        anchorElapsedRealtimeNanos: Long,
        accuracyLadderM: Double? = null,
    ) {
        if (accuracyLadderM != null) accuracyLadderBand(accuracyLadderM) // 非法档 fail-fast
        synchronized(this) {
            this.anchorWallClockMs = anchorWallClockMs
            this.anchorElapsedRealtimeNanos = anchorElapsedRealtimeNanos
            this.processes = Processes(seed, accuracyLadderM)
            this.lastFrame = null
        }
    }

    /**
     * 取 [sample] 的合成帧：会话内**每样本恰好推进一次**（按 `sample.elapsedMs` 记忆），
     * 同一样本的重复查询返回同一实例。样本时间在会话内必须严格递增。
     */
    fun frameFor(sample: TrackSample): Frame {
        synchronized(this) {
            val p = processes ?: throw IllegalStateException("位置帧合成器无活动会话（应先 beginSession）")
            val cached = lastFrame
            if (cached != null && cached.sampleElapsedMs == sample.elapsedMs) return cached
            if (cached != null) {
                check(sample.elapsedMs > cached.sampleElapsedMs) {
                    "会话内样本时间必须严格递增（上一帧 ${cached.sampleElapsedMs}ms，本帧 ${sample.elapsedMs}ms）"
                }
            }
            val frame = advance(p, sample, cached)
            lastFrame = frame
            return frame
        }
    }

    /** 结束会话（清锚点/相关过程/记忆帧；下次 [frameFor] 前必须重新 [beginSession]）。 */
    fun endSession() {
        synchronized(this) {
            processes = null
            lastFrame = null
        }
    }

    /** 单测/进程内复位（生产路径用 [endSession]）。 */
    fun resetForTest() = endSession()

    // ---------------------------------------------------------------- 逐帧推进（仅在 frameFor 锁内调用）

    private fun advance(p: Processes, sample: TrackSample, prev: Frame?): Frame {
        val wallClockMs = anchorWallClockMs + sample.elapsedMs
        val elapsedRealtimeNanos = anchorElapsedRealtimeNanos + sample.elapsedMs * 1_000_000L
        val tSec = sample.elapsedMs / 1000.0

        val displacementM = if (prev == null) {
            0.0
        } else {
            LocationFidelity.approxDistanceM(
                prev.latitudeDeg,
                prev.longitudeDeg,
                sample.latitudeDeg,
                sample.longitudeDeg,
            )
        }
        val sampleDeltaSec = if (prev == null) 0.0 else (sample.elapsedMs - prev.sampleElapsedMs) / 1000.0

        // speed = 位移/Δt：与位差严格同窗一致（§3.7 序列界保证它落在样本配速附近）。
        val speedMps = when {
            prev == null || sampleDeltaSec <= 0.0 -> sample.speedMps
            else -> displacementM / sampleDeltaSec
        }
        // bearing = 位移方向；位移过小保持上一帧（原地不抖动），首帧 0°=正北。
        val bearingDeg = when {
            prev == null -> 0.0
            sampleDeltaSec <= 0.0 || displacementM < MIN_BEARING_UPDATE_M -> prev.bearingDeg
            else -> LocationFidelity.normalizeBearingDeg(
                LocationFidelity.bearingDeg(
                    prev.latitudeDeg,
                    prev.longitudeDeg,
                    sample.latitudeDeg,
                    sample.longitudeDeg,
                ),
            )
        }

        // 低频相关过程：以样本时间为自变量（同 seed 任意查询次序逐点可复现）。
        val accuracyM = (p.accuracyCenterM + p.accuracy.value(tSec))
            .coerceIn(p.accuracyBand.start, p.accuracyBand.endInclusive)
        val verticalAccuracyM = (VERTICAL_ACCURACY_CENTER_M + p.verticalAccuracy.value(tSec))
            .coerceIn(
                VERTICAL_ACCURACY_CENTER_M - VERTICAL_ACCURACY_HALF_SPAN_M,
                VERTICAL_ACCURACY_CENTER_M + VERTICAL_ACCURACY_HALF_SPAN_M,
            )
        val speedAccuracyMps = (SPEED_ACCURACY_CENTER_MPS + p.speedAccuracy.value(tSec))
            .coerceIn(
                SPEED_ACCURACY_CENTER_MPS - SPEED_ACCURACY_HALF_SPAN_MPS,
                SPEED_ACCURACY_CENTER_MPS + SPEED_ACCURACY_HALF_SPAN_MPS,
            )
        val bearingAccuracyDeg = (BEARING_ACCURACY_CENTER_DEG + p.bearingAccuracy.value(tSec))
            .coerceIn(
                BEARING_ACCURACY_CENTER_DEG - BEARING_ACCURACY_HALF_SPAN_DEG,
                BEARING_ACCURACY_CENTER_DEG + BEARING_ACCURACY_HALF_SPAN_DEG,
            )
        val altitudeM = BASE_ALTITUDE_M +
            ALTITUDE_AMPLITUDE_M * sin(2.0 * PI * tSec / ALTITUDE_PERIOD_SEC + p.altitudePhaseRad) +
            p.altitudeJitter.value(tSec)

        return Frame(
            sampleElapsedMs = sample.elapsedMs,
            wallClockMs = wallClockMs,
            elapsedRealtimeNanos = elapsedRealtimeNanos,
            latitudeDeg = sample.latitudeDeg,
            longitudeDeg = sample.longitudeDeg,
            altitudeM = altitudeM,
            speedMps = speedMps,
            bearingDeg = bearingDeg,
            accuracyM = accuracyM,
            verticalAccuracyM = verticalAccuracyM,
            speedAccuracyMps = speedAccuracyMps,
            bearingAccuracyDeg = bearingAccuracyDeg,
            displacementM = displacementM,
            sampleDeltaSec = sampleDeltaSec,
        )
    }

    /**
     * 低频相关过程（§3.3 同款 OU 离散化，参数化版）：结点递推
     * `x_{k+1} = clamp(x_k + clamp(ρ·x_k + σ√(1−ρ²)·g − x_k, ±δ_max), ±x_max)`，
     * 结点间线性插值。状态半幅由 `x_max` 钉死，任意 500ms 步变化 ≤ (0.5/结点间隔)·δ_max。
     */
    private class CorrelatedProcess(
        private val random: Random,
        private val sigma: Double,
        private val deltaMax: Double,
        private val xMax: Double,
    ) {
        private val rho = Math.exp(-PROCESS_THETA_PER_SEC * PROCESS_NODE_INTERVAL_SEC)
        private val nodes = ArrayList<Double>()

        init {
            require(sigma >= 0.0 && deltaMax >= 0.0 && xMax > 0.0) {
                "相关过程参数退化（sigma=$sigma, deltaMax=$deltaMax, xMax=$xMax）"
            }
            nodes.add(clamp(sigma * random.nextGaussian()))
        }

        /** 时刻 [tSec]（秒，≥0）的取值；按需按结点序消耗 g（同 seed 逐点可复现）。 */
        fun value(tSec: Double): Double {
            require(tSec >= 0.0) { "相关过程时间必须非负（t=$tSec）" }
            val k = floor(tSec / PROCESS_NODE_INTERVAL_SEC).toInt()
            val frac = (tSec - k * PROCESS_NODE_INTERVAL_SEC) / PROCESS_NODE_INTERVAL_SEC
            ensure(k + 1)
            val xk = nodes[k]
            return xk + frac * (nodes[k + 1] - xk)
        }

        private fun ensure(index: Int) {
            while (nodes.size <= index) {
                val xk = nodes[nodes.size - 1]
                val raw = rho * xk + sigma * sqrt(1.0 - rho * rho) * random.nextGaussian()
                nodes.add(clamp(xk + (raw - xk).coerceIn(-deltaMax, deltaMax)))
            }
        }

        private fun clamp(x: Double): Double = x.coerceIn(-xMax, xMax)
    }

    /** 一次会话的全部相关过程（子流偏移 11..16，与 motion 管线的 seed+0/1/2 无关）。 */
    private class Processes(seed: Long, accuracyLadderM: Double?) {
        /** accuracy 带（默认收敛带 [3,9]；指定档位时 = 档位带）。 */
        val accuracyBand: ClosedFloatingPointRange<Double> = accuracyLadderM?.let {
            LocationFrameSynthesizer.accuracyLadderBand(it)
        } ?: (LocationFrameSynthesizer.ACCURACY_CENTER_M - LocationFrameSynthesizer.ACCURACY_HALF_SPAN_M..
            LocationFrameSynthesizer.ACCURACY_CENTER_M + LocationFrameSynthesizer.ACCURACY_HALF_SPAN_M)

        /** accuracy 带中心（默认 6.0；指定档位 = 档位值）。 */
        val accuracyCenterM: Double =
            (accuracyBand.start + accuracyBand.endInclusive) / 2.0

        val accuracy = CorrelatedProcess(
            Random(seed + 11),
            // σ 与 δ_max 随带宽等比缩放（默认带半幅 3.0 ⇒ σ=1.0、δ_max=0.35 的同口径）。
            sigma = (accuracyBand.endInclusive - accuracyBand.start) / 2.0 / 3.0,
            deltaMax = (accuracyBand.endInclusive - accuracyBand.start) / 2.0 * 0.35 / 3.0,
            xMax = (accuracyBand.endInclusive - accuracyBand.start) / 2.0,
        )
        val verticalAccuracy = CorrelatedProcess(
            Random(seed + 12),
            sigma = 0.4,
            deltaMax = 0.15,
            xMax = VERTICAL_ACCURACY_HALF_SPAN_M,
        )
        val speedAccuracy = CorrelatedProcess(
            Random(seed + 13),
            sigma = 0.05,
            deltaMax = 0.02,
            xMax = SPEED_ACCURACY_HALF_SPAN_MPS,
        )
        val bearingAccuracy = CorrelatedProcess(
            Random(seed + 14),
            sigma = 3.0,
            deltaMax = 1.0,
            xMax = BEARING_ACCURACY_HALF_SPAN_DEG,
        )
        val altitudeJitter = CorrelatedProcess(
            Random(seed + 15),
            sigma = 0.1,
            deltaMax = 0.05,
            xMax = ALTITUDE_JITTER_HALF_SPAN_M,
        )
        val altitudePhaseRad = Random(seed + 16).nextDouble() * 2.0 * PI
    }
}
