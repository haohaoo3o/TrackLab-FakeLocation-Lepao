package io.github.haohaoo3o.tracklab.device

import io.github.haohaoo3o.tracklab.core.model.TrackSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * [LocationFrameSynthesizer] 的纯 JVM 行为护栏（计划 §4：有状态、可复现的位置帧合成）。
 *
 * 覆盖口径（对应类 KDoc 钉死四条）：
 * 1. 时间同锚：wall/elapsedRealtime/sample 三钟由同一会话锚导出，帧间 Δ 恒 500ms；
 * 2. 位移–speed–bearing–500ms 一致：speed·Δt = 位移（同窗），speed 与样本配速偏差在 §3.7 序列界内，
 *    bearing = 位移方向（东行→90°、北行→0°、小位移保持）；
 * 3. 精度/高程为低频相关过程：带内有界 + 500ms 步变化被钉死 + 高程无 8.5s 周期失真；
 * 4. 可复现：同 seed 同输入逐点复现，异 seed 结果不同；frameFor 按样本记忆（每样本恰好推进一次）。
 */
class LocationFrameSynthesizerTest {

    private val anchorWallClockMs = 1_759_000_000_000L
    private val anchorElapsedRealtimeNanos = 12_345_678_901_234_567L

    private val lat0 = 31.2
    private val lon0 = 121.4

    private fun metersToLonDeg(meters: Double): Double =
        meters / (111_320.0 * cos(Math.toRadians(lat0)))

    private fun sample(
        index: Int,
        speedMps: Double = 3.0,
        latDeg: Double = lat0,
        lonDeg: Double = lon0,
    ) = TrackSample(
        latitudeDeg = latDeg,
        longitudeDeg = lonDeg,
        elapsedMs = index * 500L,
        speedMps = speedMps,
        paceSecPerKm = 1000.0 / speedMps,
        cadenceSpm = 170.0,
    )

    /** 东行轨迹：每样本位移 = speed·Δt（500ms）。 */
    private fun eastSample(index: Int, speedMps: Double) = sample(
        index = index,
        speedMps = speedMps,
        lonDeg = lon0 + metersToLonDeg(speedMps * 0.5 * index),
    )

    /** 东行轨迹（逐步积分速度）：样本 i 的位移恰为 speeds[i−1]·Δt。 */
    private fun eastTrack(speeds: List<Double>): List<TrackSample> {
        var lon = lon0
        return speeds.mapIndexed { index, v ->
            if (index > 0) lon += metersToLonDeg(speeds[index - 1] * 0.5)
            sample(index, v, lonDeg = lon)
        }
    }

    /** 北行轨迹。 */
    private fun northSample(index: Int, speedMps: Double) = sample(
        index = index,
        speedMps = speedMps,
        latDeg = lat0 + speedMps * 0.5 * index / 111_320.0,
    )

    private fun runSession(seed: Long, samples: List<TrackSample>): List<LocationFrameSynthesizer.Frame> {
        LocationFrameSynthesizer.resetForTest()
        LocationFrameSynthesizer.beginSession(seed, anchorWallClockMs, anchorElapsedRealtimeNanos)
        val frames = samples.map { LocationFrameSynthesizer.frameFor(it) }
        LocationFrameSynthesizer.endSession()
        return frames
    }

    // ---------------------------------------------------------------- 1. 时间同锚

    @Test
    fun timeWallAndElapsedRealtimeShareTheSessionAnchor() {
        val frames = runSession(
            LocationFrameSynthesizer.DEFAULT_SEED,
            (0 until 20).map { eastSample(it, 3.0) },
        )
        for (frame in frames) {
            assertEquals(
                "wallClockMs 必须由会话锚导出",
                anchorWallClockMs + frame.sampleElapsedMs,
                frame.wallClockMs,
            )
            assertEquals(
                "elapsedRealtimeNanos 必须由同一会话锚导出",
                anchorElapsedRealtimeNanos + frame.sampleElapsedMs * 1_000_000L,
                frame.elapsedRealtimeNanos,
            )
            assertEquals(frame.sampleElapsedMs, frame.wallClockMs - anchorWallClockMs)
        }
    }

    @Test
    fun frameDeltasAreExactlyThe500msSampleStep() {
        val frames = runSession(
            LocationFrameSynthesizer.DEFAULT_SEED,
            (0 until 40).map { eastSample(it, 3.0) },
        )
        for (i in 1 until frames.size) {
            assertEquals(500L, frames[i].wallClockMs - frames[i - 1].wallClockMs)
            assertEquals(500_000_000L, frames[i].elapsedRealtimeNanos - frames[i - 1].elapsedRealtimeNanos)
            assertEquals(0.5, frames[i].sampleDeltaSec, 1e-12)
        }
    }

    // ---------------------------------------------------------------- 2. 位移–speed–bearing 一致

    @Test
    fun speedTimesDeltaEqualsDisplacementExactly() {
        val frames = runSession(
            LocationFrameSynthesizer.DEFAULT_SEED,
            (0 until 30).map { eastSample(it, 2.0 + 0.02 * (it % 5)) },
        )
        for (i in 1 until frames.size) {
            val frame = frames[i]
            assertTrue("speed·Δt 必须与位移同窗一致", abs(frame.speedMps * frame.sampleDeltaSec - frame.displacementM) <= 1e-6)
        }
    }

    @Test
    fun speedStaysWithinMotionPipelineToleranceOfSampleSpeed() {
        // §3.7 序列界：|d − v·Δt| ≤ 0.15·v·Δt + 0.05 ⇒ |speed − v| ≤ 0.15·v + 0.1。
        // 轨迹按「上一帧速度积分」生成（等价于运动管线的位差口径）。
        val speeds = (0 until 60).map { 3.0 + 0.5 * sin(it / 7.0) }
        val frames = runSession(LocationFrameSynthesizer.DEFAULT_SEED, eastTrack(speeds))
        for (i in 1 until frames.size) {
            val frame = frames[i]
            val v = speeds[i]
            assertTrue(
                "speed=${frame.speedMps} 偏离样本配速 v=$v 超界",
                abs(frame.speedMps - v) <= 0.15 * v + 0.1,
            )
        }
    }

    /** 两方位角的最小角距（度；处理跨 0°/360° 环绕）。 */
    private fun angularDistance(a: Double, b: Double): Double {
        val diff = LocationFidelity.normalizeBearingDeg(a - b)
        return if (diff > 180.0) 360.0 - diff else diff
    }

    @Test
    fun bearingFollowsDisplacementDirection() {
        val east = runSession(LocationFrameSynthesizer.DEFAULT_SEED, (0 until 40).map { eastSample(it, 3.0) })
        for (i in 5 until east.size) {
            assertTrue(
                "东行方位应≈90°（实际 ${east[i].bearingDeg}）",
                angularDistance(east[i].bearingDeg, 90.0) <= 1.0,
            )
        }
        val north = runSession(LocationFrameSynthesizer.DEFAULT_SEED, (0 until 40).map { northSample(it, 3.0) })
        for (i in 5 until north.size) {
            assertTrue(
                "北行方位应≈0°（实际 ${north[i].bearingDeg}）",
                angularDistance(north[i].bearingDeg, 0.0) <= 1.0,
            )
        }
    }

    @Test
    fun firstFrameUsesSampleSpeedAndZeroBearing() {
        val frames = runSession(LocationFrameSynthesizer.DEFAULT_SEED, listOf(eastSample(0, 3.6)))
        assertEquals(3.6, frames[0].speedMps, 1e-12)
        assertEquals(0.0, frames[0].bearingDeg, 0.0)
        assertEquals(0.0, frames[0].displacementM, 0.0)
    }

    @Test
    fun bearingHoldsWhenDisplacementBelowThreshold() {
        LocationFrameSynthesizer.resetForTest()
        LocationFrameSynthesizer.beginSession(
            LocationFrameSynthesizer.DEFAULT_SEED,
            anchorWallClockMs,
            anchorElapsedRealtimeNanos,
        )
        // 首帧 0°；第二帧位移 0.1m < 1.0m ⇒ 保持；第三帧 2m ⇒ 推进为东向 90°。
        val tiny = eastSample(1, 3.0).copy(longitudeDeg = lon0 + metersToLonDeg(0.1))
        val hold = LocationFrameSynthesizer.frameFor(tiny)
        assertEquals(0.0, hold.bearingDeg, 0.0)
        val jump = eastSample(2, 3.0).copy(longitudeDeg = lon0 + metersToLonDeg(2.1))
        val updated = LocationFrameSynthesizer.frameFor(jump)
        assertTrue(angularDistance(updated.bearingDeg, 90.0) <= 1.0)
        LocationFrameSynthesizer.endSession()
    }

    // ---------------------------------------------------------------- 3. 低频相关过程（精度/高程）

    @Test
    fun accuracyFieldsStayInTheirBands() {
        val frames = runSession(
            LocationFrameSynthesizer.DEFAULT_SEED,
            (0 until 2000).map { sample(it) },
        )
        for (frame in frames) {
            assertTrue("精度越界 ${frame.accuracyM}", frame.accuracyM in 3.0..9.0)
            assertTrue("垂直精度越界 ${frame.verticalAccuracyM}", frame.verticalAccuracyM in 1.3..3.7)
            assertTrue("速度精度越界 ${frame.speedAccuracyMps}", frame.speedAccuracyMps in 0.05..0.35)
            assertTrue("方位精度越界 ${frame.bearingAccuracyDeg}", frame.bearingAccuracyDeg in 2.0..22.0)
        }
    }

    @Test
    fun accuracyIsCorrelatedNotPerFrameUniformNoise() {
        // OU 结点间隔 2s、δ_max=0.35 ⇒ 任意 500ms 步 |Δaccuracy| ≤ 0.25·0.35 = 0.0875。
        val frames = runSession(
            LocationFrameSynthesizer.DEFAULT_SEED,
            (0 until 1000).map { sample(it) },
        )
        var range = 0.0
        for (i in 1 until frames.size) {
            val delta = abs(frames[i].accuracyM - frames[i - 1].accuracyM)
            assertTrue("精度 500ms 步变化超界：$delta", delta <= 0.09)
            range = maxOf(range, delta)
        }
        assertTrue("精度应真实波动（极差=$range）", range > 1e-4)
    }

    @Test
    fun altitudeStaysInPlaygroundBand() {
        val frames = runSession(
            LocationFrameSynthesizer.DEFAULT_SEED,
            (0 until 3000).map { sample(it) },
        )
        val lo = LocationFrameSynthesizer.BASE_ALTITUDE_M -
            LocationFrameSynthesizer.ALTITUDE_AMPLITUDE_M -
            LocationFrameSynthesizer.ALTITUDE_JITTER_HALF_SPAN_M
        val hi = LocationFrameSynthesizer.BASE_ALTITUDE_M +
            LocationFrameSynthesizer.ALTITUDE_AMPLITUDE_M +
            LocationFrameSynthesizer.ALTITUDE_JITTER_HALF_SPAN_M
        var above = 0
        var below = 0
        for (frame in frames) {
            assertTrue("高程越界 ${frame.altitudeM}", frame.altitudeM in lo..hi)
            if (frame.altitudeM > LocationFrameSynthesizer.BASE_ALTITUDE_M) above++
            if (frame.altitudeM < LocationFrameSynthesizer.BASE_ALTITUDE_M) below++
        }
        // 起伏必须是真起伏：长窗口内既有高于也有低于基准的帧。
        assertTrue("高程无起伏: above=$above below=$below", above > 300 && below > 300)
    }

    @Test
    fun altitudeDriftsSlowlyWithoutShortPeriodCycle() {
        // 旧失真：0.37rad/帧 ⇒ ≈8.5s 一个周期（120s 窗口内符号翻转 ≈28 次）。
        // 新口径：周期 420s ⇒ 120s 窗口内至多一次穿越（含小幅抖动，翻转 ≤ 4）。
        val frames = runSession(
            LocationFrameSynthesizer.DEFAULT_SEED,
            (0 until 2400).map { sample(it) },
        )
        var flips = 0
        var lastSign = 0
        for (frame in frames) {
            if (frame.sampleElapsedMs > 120_000L) break
            val sign = when {
                frame.altitudeM > LocationFrameSynthesizer.BASE_ALTITUDE_M -> 1
                frame.altitudeM < LocationFrameSynthesizer.BASE_ALTITUDE_M -> -1
                else -> 0
            }
            if (sign != 0 && lastSign != 0 && sign != lastSign) flips++
            if (sign != 0) lastSign = sign
        }
        assertTrue("高程出现短周期失真（120s 内符号翻转 $flips 次）", flips <= 4)
        // 5s 窗口内高程变化 ≤ 0.2m（慢正弦斜率 0.015 m/s + 抖动）。
        for (i in 10 until frames.size) {
            val delta = abs(frames[i].altitudeM - frames[i - 10].altitudeM)
            assertTrue("高程 5s 变化超界：$delta", delta <= 0.2)
        }
    }

    // ---------------------------------------------------------------- 5. accuracy 可选档位

    @Test
    fun accuracyLadderBandIsTierTimesRelativeHalfSpan() {
        assertEquals(9.0, LocationFrameSynthesizer.accuracyLadderBand(10.0).start, 0.0)
        assertEquals(11.0, LocationFrameSynthesizer.accuracyLadderBand(10.0).endInclusive, 0.0)
        assertEquals(36.0, LocationFrameSynthesizer.accuracyLadderBand(40.0).start, 0.0)
        assertEquals(44.0, LocationFrameSynthesizer.accuracyLadderBand(40.0).endInclusive, 0.0)
        assertEquals(54.0, LocationFrameSynthesizer.accuracyLadderBand(60.0).start, 0.0)
        assertEquals(66.0, LocationFrameSynthesizer.accuracyLadderBand(60.0).endInclusive, 0.0)
    }

    @Test
    fun accuracyLadderSessionStaysInTierBandWithBoundedSteps() {
        val frames = runSessionWithLadder(
            tier = 40.0,
            count = 2000,
        )
        val band = LocationFrameSynthesizer.accuracyLadderBand(40.0)
        var maxStepDelta = 0.0
        var range = 0.0
        for (frame in frames) {
            assertTrue("档 40 精度越带：${frame.accuracyM}", frame.accuracyM in band)
        }
        for (i in 1 until frames.size) {
            val delta = abs(frames[i].accuracyM - frames[i - 1].accuracyM)
            maxStepDelta = maxOf(maxStepDelta, delta)
            range = maxOf(range, delta)
        }
        // 半幅 4.0 ⇒ δ_max = 4.0×0.35/3 = 0.4667 ⇒ 500ms 步变化 ≤ 0.25×δ_max ≈ 0.1167。
        assertTrue("档 40 步变化超界：$maxStepDelta", maxStepDelta <= 0.25 * 4.0 * 0.35 / 3.0 + 1e-9)
        assertTrue("档 40 精度应有真实波动（极差=$range）", range > 1e-3)
    }

    @Test
    fun invalidLadderTierIsRejectedAtBeginSession() {
        LocationFrameSynthesizer.resetForTest()
        try {
            LocationFrameSynthesizer.beginSession(
                LocationFrameSynthesizer.DEFAULT_SEED,
                anchorWallClockMs,
                anchorElapsedRealtimeNanos,
                accuracyLadderM = 25.0,
            )
            fail("非法档位必须 fail-fast")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("档位"))
        }
        assertFalse(LocationFrameSynthesizer.sessionActive)
    }

    private fun runSessionWithLadder(tier: Double, count: Int): List<LocationFrameSynthesizer.Frame> {
        LocationFrameSynthesizer.resetForTest()
        LocationFrameSynthesizer.beginSession(
            LocationFrameSynthesizer.DEFAULT_SEED,
            anchorWallClockMs,
            anchorElapsedRealtimeNanos,
            accuracyLadderM = tier,
        )
        val frames = (0 until count).map { LocationFrameSynthesizer.frameFor(eastSample(it, 3.0)) }
        LocationFrameSynthesizer.endSession()
        return frames
    }

    // ---------------------------------------------------------------- 4. 可复现 + 记忆

    @Test
    fun sameSeedAndInputReproducesFramesPointwise() {
        val samples = (0 until 200).map { eastSample(it, 3.0) }
        val first = runSession(0xABCDEF, samples)
        val second = runSession(0xABCDEF, samples)
        assertEquals(first, second)
    }

    @Test
    fun differentSeedYieldsDifferentAccuracySeries() {
        val samples = (0 until 100).map { sample(it) }
        val a = runSession(0xABCDEF, samples)
        val b = runSession(0xABCDEF + 1, samples)
        assertNotEquals("不同种子必须产生不同精度序列", a, b)
    }

    @Test
    fun frameForIsMemoizedPerSample() {
        LocationFrameSynthesizer.resetForTest()
        LocationFrameSynthesizer.beginSession(
            LocationFrameSynthesizer.DEFAULT_SEED,
            anchorWallClockMs,
            anchorElapsedRealtimeNanos,
        )
        val s = eastSample(3, 3.0)
        val first = LocationFrameSynthesizer.frameFor(s)
        val again = LocationFrameSynthesizer.frameFor(s)
        assertSame("同一样本重复查询必须返回同一帧（每样本恰好推进一次）", first, again)
        LocationFrameSynthesizer.endSession()
    }

    @Test
    fun frameForRequiresSessionAndMonotonicSampleTime() {
        LocationFrameSynthesizer.resetForTest()
        try {
            LocationFrameSynthesizer.frameFor(sample(0))
            fail("无会话时 frameFor 必须快速失败")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("beginSession"))
        }
        LocationFrameSynthesizer.beginSession(
            LocationFrameSynthesizer.DEFAULT_SEED,
            anchorWallClockMs,
            anchorElapsedRealtimeNanos,
        )
        LocationFrameSynthesizer.frameFor(sample(5))
        try {
            LocationFrameSynthesizer.frameFor(sample(4))
            fail("会话内样本时间必须严格递增")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("递增"))
        }
        LocationFrameSynthesizer.endSession()
        assertFalse(LocationFrameSynthesizer.sessionActive)
    }
}
