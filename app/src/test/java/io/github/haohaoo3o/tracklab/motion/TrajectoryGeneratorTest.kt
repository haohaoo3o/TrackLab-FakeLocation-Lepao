package io.github.haohaoo3o.tracklab.motion

import io.github.haohaoo3o.tracklab.core.geo.LatLon
import io.github.haohaoo3o.tracklab.core.geo.TrackModel
import io.github.haohaoo3o.tracklab.core.geo.Vec2
import io.github.haohaoo3o.tracklab.core.model.TrackSample
import io.github.haohaoo3o.tracklab.core.motion.MotionContracts
import io.github.haohaoo3o.tracklab.core.motion.TrajectoryGenerator
import io.github.haohaoo3o.tracklab.data.export.GeoJsonExporter
import io.github.haohaoo3o.tracklab.data.export.GpxExporter
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max

/**
 * 断言契约（管线次序；java.util.Random(seed)/(seed+1)/(seed+2) 子流——
 * 同 seed 逐点一致、不同 seed 有差异；间距/曲率界度量口径）：本测试补『导出点序与
 * TrajectoryGenerator.generate 输出一致』集成断言（导出器已存在）。
 *
 * 覆盖（标准试样、laps=3、p_base=330、seed=42）：Δt=0.5s 钉死与恒等式 |pace−1000/v|<1e-9；
 * 范围（pace∈[180,660]、c∈[150,200]、v>0 禁负速度）；连续性（|Δpace|≤35.0、|Δc|≤4.0）；
 * 相邻样本距离 |d − v·Δt| ≤ 0.15·v·Δt+0.05（重采样输出度量）；曲率 |κ| ≤ 1.2/(R−0.55)+0.02（最终序列度量）；
 * 逐圈轨迹不严格重合（max_s|Δ̂_j−Δ̂_k| ≥ 0.005）且留在测试边界内（|t|≤W）；可复现性（同 seed 逐点一致、
 * 不同 seed 有差异）；导出一致性（GPX/GeoJSON 点序 = generate 输出序）。
 */
class TrajectoryGeneratorTest {

    private fun stdModel() = TrackModel(
        origin = LatLon(30.0000, 110.0000), // 合成境内测试坐标，不对应个人位置
        u = Vec2(1.0, 0.0),
        n = Vec2(0.0, 1.0),
        a = STD_A,
        r = STD_R
    )

    private fun generate(seed: Long = 42L, laps: Int = 3, pBase: Double = MotionContracts.FEATURE_PACE_BASE): List<TrackSample> =
        TrajectoryGenerator().generate(stdModel(), laps, pBase, seed)

    /** 推进环复算的累计跑距 s_k（s₀=0，s_{k+1}=s_k+v_k·Δt）。*/
    private fun replayRunDistances(samples: List<TrackSample>): DoubleArray {
        val s = DoubleArray(samples.size)
        var run = 0.0
        for (i in samples.indices) {
            s[i] = run
            run += samples[i].speedMps * MotionContracts.DELTA_T_SEC
        }
        return s
    }

    // ---------------------------------------------------------------- Δt 钉死 + 恒等式

    @Test
    fun samplesCarryPinnedDeltaTAndPaceSpeedIdentity() {
        val samples = generate()
        for ((i, s) in samples.withIndex()) {
            // MotionClock 等时推进：elapsedMs = k·500（Δt=0.5s 钉死，禁 1s）
            assertEquals("i=$i：elapsedMs=k·500", i * 500L, s.elapsedMs)
            // |pace − 1000/v| < 1e-9
            assertTrue("i=$i：|pace − 1000/v| < 1e-9",
                abs(s.paceSecPerKm - 1000.0 / s.speedMps) < 1e-9)
        }
    }

    // ---------------------------------------------------------------- 范围（3:00–11:00 min/km；150–200 spm）

    @Test
    fun paceAndCadenceStayInPinnedRangesNoNegativeSpeed() {
        for (seed in listOf(42L, 7L, 2026L)) {
            val samples = generate(seed = seed)
            for ((i, s) in samples.withIndex()) {
                assertTrue("seed=$seed i=$i：pace=${s.paceSecPerKm} ∈ [180, 660]（3:00–11:00 min/km）",
                    s.paceSecPerKm >= MotionContracts.PACE_MIN_S_PER_KM && s.paceSecPerKm <= MotionContracts.PACE_MAX_S_PER_KM)
                assertTrue("seed=$seed i=$i：c=${s.cadenceSpm} ∈ [150, 200]",
                    s.cadenceSpm >= MotionContracts.CADENCE_MIN_SPM && s.cadenceSpm <= MotionContracts.CADENCE_MAX_SPM)
                assertTrue("seed=$seed i=$i：v=${s.speedMps} > 0（禁负速度）", s.speedMps > 0.0)
            }
        }
    }

    // ---------------------------------------------------------------- 连续性（标准试样校准）

    @Test
    fun stepwiseContinuityWithinCalibratedBounds() {
        val samples = generate()
        for (i in 1 until samples.size) {
            val dp = abs(samples[i].paceSecPerKm - samples[i - 1].paceSecPerKm)
            assertTrue("i=$i：|Δpace|=$dp ≤ ${MotionContracts.PACE_STEP_MAX_DELTA}",
                dp <= MotionContracts.PACE_STEP_MAX_DELTA)
            val dc = abs(samples[i].cadenceSpm - samples[i - 1].cadenceSpm)
            assertTrue("i=$i：|Δc|=$dc ≤ ${MotionContracts.CADENCE_STEP_MAX_DELTA_SPM}",
                dc <= MotionContracts.CADENCE_STEP_MAX_DELTA_SPM)
        }
    }

    // ---------------------------------------------------------------- 相邻点距离（重采样输出度量）

    @Test
    fun adjacentPointDistanceMatchesSpeedTimesDeltaT() {
        val model = stdModel()
        val samples = generate()
        for (i in 0 until samples.size - 1) {
            val p0 = model.toEnu(LatLon(samples[i].latitudeDeg, samples[i].longitudeDeg))
            val p1 = model.toEnu(LatLon(samples[i + 1].latitudeDeg, samples[i + 1].longitudeDeg))
            val d = (p1 - p0).norm()
            val expect = samples[i].speedMps * MotionContracts.DELTA_T_SEC
            val tol = MotionContracts.STEP_DIST_TOL_REL * expect + MotionContracts.STEP_DIST_TOL_ABS_M
            assertTrue(
                "i=$i：|d − v·Δt|=${abs(d - expect)} ≤ 0.15·v·Δt+0.05=$tol（d=$d, v·Δt=$expect）",
                abs(d - expect) <= tol
            )
        }
    }

    // ---------------------------------------------------------------- 曲率（最终序列度量）

    @Test
    fun curvatureWithinPinnedBoundOnFinalSequence() {
        val model = stdModel()
        val samples = generate()
        val bound = 1.2 / (model.r - MotionContracts.SOFT_CLAMP_SCALE_M) + MotionContracts.CURVATURE_K_TOL_ABS
        var maxK = 0.0
        for (i in 1 until samples.size - 1) {
            val p0 = model.toEnu(LatLon(samples[i - 1].latitudeDeg, samples[i - 1].longitudeDeg))
            val p1 = model.toEnu(LatLon(samples[i].latitudeDeg, samples[i].longitudeDeg))
            val p2 = model.toEnu(LatLon(samples[i + 1].latitudeDeg, samples[i + 1].longitudeDeg))
            val k = mengerCurvature(p0, p1, p2)
            maxK = max(maxK, k)
            assertTrue("i=$i：|κ|=$k ≤ 1.2/(R−0.55)+0.02=$bound", k <= bound + 1e-12)
        }
        assertTrue("曲率非平凡（max|κ|=$maxK > 0）", maxK > 0.0)
        println("[CALIB] maxMengerCurvature=$maxK bound=$bound")
    }

    /** Menger 曲率 |κ| = 4·Area/(a·b·c)（三点圆；圆弧上精确 = 1/R）。 */
    private fun mengerCurvature(p0: Vec2, p1: Vec2, p2: Vec2): Double {
        val a = (p1 - p0).norm()
        val b = (p2 - p1).norm()
        val c = (p0 - p2).norm()
        val area2 = abs((p1 - p0).cross(p2 - p0))
        return 2.0 * area2 / (a * b * c)
    }

    // ---------------------------------------------------------------- 可复现性（子流 seed/seed+1/seed+2）

    @Test
    fun sameSeedReproducesPointwiseDifferentSeedDiffers() {
        val a = generate(seed = 42L)
        val b = generate(seed = 42L)
        val c = generate(seed = 43L)
        assertEquals("同 seed 样本数一致", a.size, b.size)
        for (i in a.indices) {
            assertEquals("i=$i 同 seed lat 逐点一致", a[i].latitudeDeg, b[i].latitudeDeg, 0.0)
            assertEquals("i=$i 同 seed lon 逐点一致", a[i].longitudeDeg, b[i].longitudeDeg, 0.0)
            assertEquals("i=$i 同 seed elapsedMs 一致", a[i].elapsedMs, b[i].elapsedMs)
            assertEquals("i=$i 同 seed v 逐点一致", a[i].speedMps, b[i].speedMps, 0.0)
            assertEquals("i=$i 同 seed pace 逐点一致", a[i].paceSecPerKm, b[i].paceSecPerKm, 0.0)
            assertEquals("i=$i 同 seed c 逐点一致", a[i].cadenceSpm, b[i].cadenceSpm, 0.0)
        }
        var diff = 0
        for (i in a.indices) {
            if (i < c.size && (abs(a[i].latitudeDeg - c[i].latitudeDeg) > 1e-12 ||
                    abs(a[i].paceSecPerKm - c[i].paceSecPerKm) > 1e-12 ||
                    abs(a[i].cadenceSpm - c[i].cadenceSpm) > 1e-12)
            ) diff++
        }
        assertTrue("不同 seed 应产生差异（diff=$diff）", diff > 0)
    }

    // ---------------------------------------------------------------- 逐圈差异 + 测试边界内

    @Test
    fun perLapPathsDoNotCoincideButStayInsideBoundary() {
        val model = stdModel()
        val samples = generate()
        val runs = replayRunDistances(samples)
        val w = io.github.haohaoo3o.tracklab.core.motion.BoundaryGuard.halfWidth(model.r)

        // 逐样本：横向残差留在走廊 |t| ≤ W（轨迹必须留在测试边界内）
        val arcs = DoubleArray(samples.size)
        val lapsOf = IntArray(samples.size)
        val offsets = DoubleArray(samples.size)
        for (i in samples.indices) {
            val arc = model.wrapArc(runs[i])
            val lap = Math.floor(runs[i] / model.lengthM).toInt().coerceAtMost(2)
            val enu = model.toEnu(LatLon(samples[i].latitudeDeg, samples[i].longitudeDeg))
            val t = (enu - model.point(arc)).dot(model.normal(arc))
            assertTrue("i=$i：|t|=${abs(t)} ≤ W=$w", abs(t) <= w + 1e-6)
            arcs[i] = arc
            lapsOf[i] = lap
            offsets[i] = t
        }

        // 逐圈轨迹不严格重合：任意两圈 max_s|t_j(s)−t_k(s)| ≥ 0.005（插值到公共网格，下界同口径）
        val grid = MotionContracts.LAP_DIFF_GRID_STEP_M
        val n = Math.ceil(model.lengthM / grid).toInt()
        for (lapJ in 0..2) {
            for (lapK in lapJ + 1..2) {
                var d = 0.0
                for (g in 0 until n) {
                    val s = g * grid
                    val tj = interpOffset(s, arcs, lapsOf, offsets, lapJ, model.lengthM)
                    val tk = interpOffset(s, arcs, lapsOf, offsets, lapK, model.lengthM)
                    d = max(d, abs(tj - tk))
                }
                assertTrue(
                    "圈 $lapJ vs $lapK：max_s|t_j−t_k|=$d ≥ ${MotionContracts.LAP_DIFF_MIN_M}（不得逐圈严格重合）",
                    d >= MotionContracts.LAP_DIFF_MIN_M
                )
                assertTrue("圈 $lapJ vs $lapK：差异上界 < 1.1", d < MotionContracts.LAP_DIFF_MAX_EXCLUSIVE_M)
            }
        }
    }

    /** 圈内横向偏移按 (s̄, t) 样本线性插值（首尾跨接闭合）。 */
    private fun interpOffset(
        s: Double,
        arcs: DoubleArray,
        laps: IntArray,
        offsets: DoubleArray,
        lap: Int,
        lengthM: Double,
    ): Double {
        val pts = ArrayList<Pair<Double, Double>>()
        for (i in arcs.indices) {
            if (laps[i] == lap) pts.add(arcs[i] to offsets[i])
        }
        if (pts.isEmpty()) return 0.0
        pts.sortBy { it.first }
        // 闭合扩展：头点 +L、尾点 −L
        pts.add(0, (pts.first().first - lengthM) to pts.first().second)
        pts.add((pts.last().first + lengthM) to pts.last().second)
        for (i in 0 until pts.size - 1) {
            val (s0, t0) = pts[i]
            val (s1, t1) = pts[i + 1]
            if (s >= s0 && s <= s1) {
                val f = if (s1 > s0) (s - s0) / (s1 - s0) else 0.0
                return t0 + f * (t1 - t0)
            }
        }
        return pts.last().second
    }

    // ---------------------------------------------------------------- 导出一致性集成断言

    @Test
    fun exportOrderMatchesGeneratorOutput() {
        val samples = generate()
        // GPX：trkpt 点序 = generate 输出序（lat/lon 逐点对位）
        val gpx = GpxExporter.export(samples, startEpochMs = 1_700_000_000_000L)
        val trkpt = Regex("<trkpt lat=\"([^\"]+)\" lon=\"([^\"]+)\"").findAll(gpx).toList()
        assertEquals("GPX trkpt 数 = 样本数", samples.size, trkpt.size)
        for (i in samples.indices) {
            assertEquals("GPX #$i lat 对位", samples[i].latitudeDeg, trkpt[i].groupValues[1].toDouble(), 0.0)
            assertEquals("GPX #$i lon 对位", samples[i].longitudeDeg, trkpt[i].groupValues[2].toDouble(), 0.0)
        }
        // time 点序 = elapsedMs 序
        val times = Regex("<time>([^<]+)</time>").findAll(gpx).map { it.groupValues[1] }.toList()
        assertEquals("GPX time 数 = 样本数", samples.size, times.size)

        // GeoJSON：coordinates[i]=[lon, lat] + properties.timesMs 保序
        val json = JSONObject(GeoJsonExporter.export(samples))
        val coords = json.getJSONArray("features").getJSONObject(0).getJSONObject("geometry").getJSONArray("coordinates")
        val timesMs = json.getJSONArray("features").getJSONObject(0).getJSONObject("properties").getJSONArray("timesMs")
        assertEquals("GeoJSON coordinates 数 = 样本数", samples.size, coords.length())
        assertEquals("GeoJSON timesMs 数 = 样本数", samples.size, timesMs.length())
        for (i in samples.indices) {
            val c = coords.getJSONArray(i)
            assertEquals("GeoJSON #$i lon 对位", samples[i].longitudeDeg, c.getDouble(0), 0.0)
            assertEquals("GeoJSON #$i lat 对位", samples[i].latitudeDeg, c.getDouble(1), 0.0)
            assertEquals("GeoJSON #$i elapsedMs 对位", samples[i].elapsedMs, timesMs.getLong(i))
        }
    }

    // ---------------------------------------------------------------- 输入校验（口径）

    @Test
    fun invalidInputsRejectedWithReadableErrors() {
        val model = stdModel()
        val gen = TrajectoryGenerator()
        for (laps in listOf(0, -1, 101)) {
            try {
                gen.generate(model, laps, 330.0, 42L)
                throw AssertionError("期望 IllegalArgumentException（laps=$laps）")
            } catch (e: IllegalArgumentException) {
                assertTrue("中文可读错误：" + e.message, e.message!!.contains("laps"))
            }
        }
        for (pBase in listOf(179.9, 660.1, 0.0)) {
            try {
                gen.generate(model, 3, pBase, 42L)
                throw AssertionError("期望 IllegalArgumentException（p_base=$pBase）")
            } catch (e: IllegalArgumentException) {
                assertTrue("中文可读错误：" + e.message, e.message!!.contains("p_base"))
            }
        }
    }

    companion object {
        private const val STD_A = 42.5
        private const val STD_R = 36.5
    }
}
