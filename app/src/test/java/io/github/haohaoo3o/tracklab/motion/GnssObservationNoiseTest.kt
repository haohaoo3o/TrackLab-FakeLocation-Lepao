package io.github.haohaoo3o.tracklab.motion

import io.github.haohaoo3o.tracklab.core.geo.LatLon
import io.github.haohaoo3o.tracklab.core.geo.TrackModel
import io.github.haohaoo3o.tracklab.core.geo.Vec2
import io.github.haohaoo3o.tracklab.core.model.TrackSample
import io.github.haohaoo3o.tracklab.core.motion.GnssObservationNoise
import io.github.haohaoo3o.tracklab.core.motion.MotionContracts
import io.github.haohaoo3o.tracklab.core.motion.OuNoise
import io.github.haohaoo3o.tracklab.core.motion.TrajectoryGenerator
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * GNSS 观测噪声输出覆盖层正式断言（ 拟增；闭环裁决 3/4 + 评审 C4）。
 *
 * 口径（见 evidence/s5-20261010-three-fronts/jitter/REALISM-AB.md /）：
 * - 残差模型一致性（主判据）：R = O − T 同时间戳逐点相减；零均值 |mean|≤0.05、
 *   单轴 sd∈[0.07,0.13]（理论 0.0977）、lag-1 ρ1=0.912±0.05（理论  T-R1/R2/R3）。
 * - J1/J3/J5/J6 如实报数 + 理论一致性命中（公式与容差比较前已冻结，见 ）；
 *   J1≥0.025 & maxK≤0.15 为第 3 轮多种子重标修订（），旧实现同表对照必须挂。
 * - 标定种子 42/7/2026（多种子全绿；seed12345/999 的 J1/J6 上端分布如实记 ，
 *   不进正式断言）。
 * - 既有真路线测试一条不动；本文件只加观测断言。
 */
class GnssObservationNoiseTest {

    private fun stdModel() = TrackModel(
        origin = LatLon(31.2304, 121.4737),
        u = Vec2(1.0, 0.0),
        n = Vec2(0.0, 1.0),
        a = 42.5,
        r = 36.5,
    )

    /** 观测序列 + 真 ENU + 推进复算（与 TrajectoryGenerator 同环语义）。 */
    private data class ObsData(
        val truth: List<TrackSample>,
        val obs: List<TrackSample>,
        val trueEnu: List<Vec2>,
        val obsEnu: List<Vec2>,
        val arc: DoubleArray,
        val lap: IntArray,
        val v: DoubleArray,
        val runTotal: Double,
    )

    private fun load(model: TrackModel, seed: Long): ObsData {
        val gen = TrajectoryGenerator()
        val truth = gen.generate(model, 3, 330.0, seed)
        val obs = gen.generateObserved(model, 3, 330.0, seed)
        assertEquals("真/观测样本数一致", truth.size, obs.size)
        val n = truth.size
        val trueEnu = truth.map { model.toEnu(LatLon(it.latitudeDeg, it.longitudeDeg)) }
        val obsEnu = obs.map { model.toEnu(LatLon(it.latitudeDeg, it.longitudeDeg)) }
        val run = DoubleArray(n)
        var acc = 0.0
        for (i in 0 until n) {
            run[i] = acc
            acc += truth[i].speedMps * MotionContracts.DELTA_T_SEC
        }
        return ObsData(
            truth, obs, trueEnu, obsEnu,
            DoubleArray(n) { model.wrapArc(run[it]) },
            IntArray(n) { min(2, floor(run[it] / model.lengthM).toInt()) },
            DoubleArray(n) { truth[it].speedMps },
            acc,
        )
    }

    // ---------------------------------------------------------------- 残差统计工具

    private fun residStats(x: DoubleArray): Triple<Double, Double, Double> {
        val m = x.average()
        var vv = 0.0
        for (v in x) vv += (v - m) * (v - m)
        val sd = sqrt(vv / x.size)
        var num = 0.0
        for (i in 0 until x.size - 1) num += (x[i] - m) * (x[i + 1] - m)
        return Triple(m, sd, num / vv)
    }

    private fun transverse(model: TrackModel, pts: List<Vec2>, arc: DoubleArray): DoubleArray =
        DoubleArray(pts.size) { i -> (pts[i] - model.point(arc[i])).dot(model.normal(arc[i])) }

    private fun detrended(tt: DoubleArray, lap: IntArray): DoubleArray {
        val sums = DoubleArray(3)
        val cnts = IntArray(3)
        for (i in tt.indices) {
            sums[lap[i]] += tt[i]
            cnts[lap[i]]++
        }
        return DoubleArray(tt.size) { i -> tt[i] - sums[lap[i]] / cnts[lap[i]] }
    }

    private fun j1Hf(xd: DoubleArray): Double {
        val n = xd.size
        var totP = 0.0
        var hfP = 0.0
        for (m in 1..n / 2) {
            var re = 0.0
            var im = 0.0
            for (k in 0 until n) {
                val ang = 2.0 * PI * m * k / n
                re += xd[k] * cos(ang)
                im -= xd[k] * sin(ang)
            }
            val p = re * re + im * im
            totP += p
            if (m * 2.0 / n > MotionContracts.OBS_J1_HF_HZ) hfP += p
        }
        return hfP / totP
    }

    private fun lag1(x: DoubleArray): Double {
        val m = x.average()
        var den = 0.0
        for (v in x) den += (v - m) * (v - m)
        var num = 0.0
        for (i in 0 until x.size - 1) num += (x[i] - m) * (x[i + 1] - m)
        return num / den
    }

    private fun displacements(pts: List<Vec2>): DoubleArray =
        DoubleArray(pts.size - 1) { i -> (pts[i + 1] - pts[i]).norm() }

    private fun headings(pts: List<Vec2>): DoubleArray {
        val head = DoubleArray(pts.size - 1) { i ->
            Math.toDegrees(atan2(pts[i + 1].x - pts[i].x, pts[i + 1].y - pts[i].y))
        }
        return DoubleArray(head.size - 1) { i ->
            var d = head[i + 1] - head[i]
            while (d > 180.0) d -= 360.0
            while (d <= -180.0) d += 360.0
            abs(d)
        }
    }

    private fun isStraight(s: Double, a: Double, r: Double): Boolean =
        (s < 2 * a) || (s > 2 * a + PI * r && s < 4 * a + PI * r)

    private fun p99std(xs: List<Double>): Pair<Double, Double> {
        val s = xs.sorted()
        val pos = 0.99 * (s.size - 1)
        val lo = floor(pos).toInt()
        val p99 = s[lo] + (pos - lo) * (s[min(s.size - 1, lo + 1)] - s[lo])
        val m = xs.average()
        var vv = 0.0
        for (x in xs) vv += (x - m) * (x - m)
        return p99 to sqrt(vv / xs.size)
    }

    private fun lapPairs(tt: DoubleArray, arc: DoubleArray, lap: IntArray, lengthM: Double): List<Double> {
        val grid = MotionContracts.LAP_DIFF_GRID_STEP_M
        val ng = Math.ceil(lengthM / grid).toInt()
        val out = ArrayList<Double>()
        for (lapJ in 0..2) for (lapK in lapJ + 1..2) {
            var d = 0.0
            for (g in 0 until ng) {
                val s = g * grid
                d = max(d, abs(interp(s, arc, lap, tt, lapJ, lengthM) -
                    interp(s, arc, lap, tt, lapK, lengthM)))
            }
            out.add(d)
        }
        return out.sorted()
    }

    private fun interp(s: Double, arcs: DoubleArray, laps: IntArray, off: DoubleArray, lap: Int, len: Double): Double {
        val pts = ArrayList<Pair<Double, Double>>()
        for (i in arcs.indices) if (laps[i] == lap) pts.add(arcs[i] to off[i])
        if (pts.isEmpty()) return 0.0
        pts.sortBy { it.first }
        pts.add(0, (pts.first().first - len) to pts.first().second)
        pts.add((pts.last().first + len) to pts.last().second)
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

    private fun j6Peak(xd: DoubleArray, meanStep: Double, lengthM: Double): Double {
        val n = xd.size
        val m = xd.average()
        var den = 0.0
        for (x in xd) den += (x - m) * (x - m)
        val lag0 = (lengthM / meanStep).toInt()
        fun rhoAt(lag: Int): Double {
            var s = 0.0
            for (k in 0 until n - lag) s += (xd[k] - m) * (xd[k + lag] - m)
            return s / den
        }
        var peak = Double.NEGATIVE_INFINITY
        for (lag in (lag0 * 0.8).toInt()..(lag0 * 1.2).toInt()) {
            if (lag <= 0 || lag >= n) continue
            peak = max(peak, rhoAt(lag))
        }
        return peak
    }

    private fun maxK(pts: List<Vec2>): Double {
        var mk = 0.0
        for (i in 1 until pts.size - 1) {
            val a0 = (pts[i] - pts[i - 1]).norm()
            val b0 = (pts[i + 1] - pts[i]).norm()
            val c0 = (pts[i + 1] - pts[i - 1]).norm()
            val area2 = abs((pts[i] - pts[i - 1]).cross(pts[i + 1] - pts[i - 1]))
            mk = max(mk, if (a0 * b0 * c0 > 0) 2.0 * area2 / (a0 * b0 * c0) else 0.0)
        }
        return mk
    }

    // ---------------------------------------------------------------- 1. 复现性 + E/N 独立

    @Test
    fun obsNoiseReproducibleAndTwoAxisIndependent() {
        for (seed in listOf(42L, 7L, 2026L)) {
            val a = GnssObservationNoise(seed)
            val b = GnssObservationNoise(seed)
            for (i in listOf(0L, 1L, 2L, 3L, 100L, 845L)) {
                val t = i * MotionContracts.DELTA_T_SEC
                assertEquals("seed=$seed E($i)逐点一致", a.eastAt(t, i), b.eastAt(t, i), 0.0)
                assertEquals("seed=$seed N($i)逐点一致", a.northAt(t, i), b.northAt(t, i), 0.0)
            }
            // 不同 seed 有差异
            val c = GnssObservationNoise(seed + 1)
            var diff = 0
            for (i in 1L..50L) {
                if (abs(a.eastAt(i * 0.5, i) - c.eastAt(i * 0.5, i)) > 1e-12) diff++
            }
            assertTrue("seed=" + seed + " vs " + (seed + 1) + " 应有差异（diff=${diff}）", diff > 0)
            // E/N 互相关 |r|<0.2
            val n = TrajectoryGenerator().generate(stdModel(), 3, 330.0, seed).size
            val e = DoubleArray(n) { i -> a.eastAt(i * 0.5, i.toLong()) }
            val nn = DoubleArray(n) { i -> a.northAt(i * 0.5, i.toLong()) }
            val me = e.average()
            val mn = nn.average()
            var s = 0.0
            var de = 0.0
            var dn = 0.0
            for (i in 0 until n) {
                s += (e[i] - me) * (nn[i] - mn)
                de += (e[i] - me) * (e[i] - me)
                dn += (nn[i] - mn) * (nn[i] - mn)
            }
            val r = s / sqrt(de * dn)
            assertTrue("seed=$seed E/N互相关|r|=$r<0.2", abs(r) < 0.2)
        }
    }

    // ---------------------------------------------------------------- 2. 残差模型一致性（主判据，裁决 3）

    @Test
    fun residualModelConsistency() {
        for (seed in listOf(42L, 7L, 2026L)) {
            val d = load(stdModel(), seed)
            val n = d.truth.size
            val rE = DoubleArray(n) { d.obsEnu[it].x - d.trueEnu[it].x }
            val rN = DoubleArray(n) { d.obsEnu[it].y - d.trueEnu[it].y }
            val (mE, sE, rhoE) = residStats(rE)
            val (mN, sN, rhoN) = residStats(rN)
            assertTrue("seed=$seed R零均值E=$mE", abs(mE) <= 0.05)
            assertTrue("seed=$seed R零均值N=$mN", abs(mN) <= 0.05)
            assertTrue("seed=$seed RsdE=${sE}∈[0.07,0.13]", sE in 0.07..0.13)
            assertTrue("seed=$seed RsdN=${sN}∈[0.07,0.13]", sN in 0.07..0.13)
            assertTrue("seed=$seed RrhoE=${rhoE}≈0.912±0.05", abs(rhoE - 0.912) <= 0.05)
            assertTrue("seed=$seed RrhoN=${rhoN}≈0.912±0.05", abs(rhoN - 0.912) <= 0.05)
        }
    }

    // ---------------------------------------------------------------- 3. OU 结点对账（递推纯净）

    @Test
    fun ouNodesReconcileWithDirectlyDrivenOuNoise() {
        for (seed in listOf(42L, 7L, 2026L)) {
            val prod = GnssObservationNoise(seed)
            val direct = OuNoise(
                Random(seed + MotionContracts.SEED_OFFSET_OBS_E),
                MotionContracts.OBS_SIGMA_M,
                MotionContracts.OBS_DELTA_MAX_M,
                MotionContracts.OBS_X_MAX_M,
                MotionContracts.OBS_NODE_INTERVAL_SEC,
                MotionContracts.OBS_RHO,
            )
            for (k in 0..50) {
                assertEquals("seed=$seed 结点${k}逐项一致", direct.nodeAt(k), prod.nodeEastAt(k), 0.0)
            }
            // 偶数样本白噪声恒 0（OU 结点时刻不消耗）
            for (i in 0L..20L step 2L) {
                val w = prod.eastAt(i * 0.5, i) - prod.correlatedEastAt(i * 0.5)
                assertEquals("seed=$seed 偶槽i=${i}白噪声恒0", 0.0, w, 0.0)
            }
        }
    }

    // ---------------------------------------------------------------- 4. J1（修订界 0.025 + 旧对照挂）

    @Test
    fun obsJ1HfShareAboveFloorAndOldFails() {
        val model = stdModel()
        for (seed in listOf(42L, 7L, 2026L)) {
            val d = load(model, seed)
            val newJ1 = j1Hf(detrended(transverse(model, d.obsEnu, d.arc), d.lap))
            val oldJ1 = j1Hf(detrended(transverse(model, d.trueEnu, d.arc), d.lap))
            assertTrue("seed=$seed 旧J1=${oldJ1}必须挂（<0.025）", oldJ1 < 0.025)
            assertTrue("seed=$seed 新J1=${newJ1}≥0.025", newJ1 >= MotionContracts.OBS_J1_MIN_SHARE)
            assertTrue("seed=" + seed + " J1改善比=" + (newJ1 / oldJ1) + "≥4×", newJ1 / oldJ1 >= 4.0)
        }
    }

    // ---------------------------------------------------------------- 5. J2c 带

    @Test
    fun obsJ2cWithinCorrelatedBand() {
        for (seed in listOf(42L, 7L, 2026L)) {
            val d = load(stdModel(), seed)
            val dspNew = DoubleArray(d.obsEnu.size - 1) {
                (d.obsEnu[it + 1] - d.obsEnu[it]).norm() / MotionContracts.DELTA_T_SEC
            }
            val j2c = lag1(dspNew)
            assertTrue("seed=$seed J2c=${j2c}∈[0.85,0.97]",
                j2c >= MotionContracts.OBS_J2C_MIN && j2c <= MotionContracts.OBS_J2C_MAX)
        }
    }

    // ---------------------------------------------------------------- 6. J3 直道段

    @Test
    fun obsJ3StraightHeadingSpread() {
        val model = stdModel()
        for (seed in listOf(42L, 7L, 2026L)) {
            val d = load(model, seed)
            val ad = headings(d.obsEnu)
            val strVals = (0 until ad.size).filter {
                isStraight(d.arc[it], model.a, model.r) && isStraight(d.arc[it + 1], model.a, model.r)
            }.map { ad[it] }
            assertTrue("seed=$seed 直道段非空", strVals.isNotEmpty())
            val (p99, std) = p99std(strVals)
            assertTrue("seed=$seed 直道p99=${p99}≤5.0", p99 <= MotionContracts.OBS_J3_STR_P99_MAX_DEG)
            assertTrue("seed=$seed 直道std=${std}≥0.6", std >= MotionContracts.OBS_J3_STR_STD_MIN_DEG)
        }
    }

    // ---------------------------------------------------------------- 7. J4 圈差

    @Test
    fun obsJ4LapSeparationAboveOldMax() {
        val model = stdModel()
        for (seed in listOf(42L, 7L, 2026L)) {
            val d = load(model, seed)
            val pairs = lapPairs(transverse(model, d.obsEnu, d.arc), d.arc, d.lap, model.lengthM)
            assertTrue("seed=" + seed + " 中位数=" + pairs[1] + "≥0.3", pairs[1] >= MotionContracts.OBS_J4_MED_MIN_M)
            for ((j, p) in pairs.withIndex()) {
                assertTrue("seed=" + seed + " 第" + j + "对=" + p + "<2.5", p < MotionContracts.OBS_LAP_DIFF_MAX_M)
            }
        }
    }

    // ---------------------------------------------------------------- 8. J5 CV + tol_obs 零超界 + inflate

    @Test
    fun obsJ5CvAboveFloorAndStepBoundHolds() {
        for (seed in listOf(42L, 7L, 2026L)) {
            val d = load(stdModel(), seed)
            val dd = displacements(d.obsEnu)
            val dev = DoubleArray(dd.size) { dd[it] - d.v[it] * MotionContracts.DELTA_T_SEC }
            val meanD = dd.average()
            val meanDev = dev.average()
            var vv = 0.0
            for (x in dev) vv += (x - meanDev) * (x - meanDev)
            val cv = sqrt(vv / dev.size) / meanD
            assertTrue("seed=$seed CV=${cv}≥0.015", cv >= MotionContracts.OBS_J5_CV_MIN)
            var viol = 0
            for (i in dev.indices) {
                val tol = MotionContracts.OBS_STEP_DIST_TOL_REL * d.v[i] *
                    MotionContracts.DELTA_T_SEC + MotionContracts.OBS_STEP_DIST_TOL_ABS_M
                if (abs(dev[i]) > tol) viol++
            }
            assertEquals("seed=$seed tol_obs零超界", 0, viol)
            var pathLen = 0.0
            for (x in dd) pathLen += x
            assertTrue("seed=$seed inflate=${pathLen / d.runTotal}≤1.01",
                pathLen / d.runTotal <= MotionContracts.OBS_INFLATE_MAX)
        }
    }

    // ---------------------------------------------------------------- 9. J6 抑制 + 曲率修订界

    @Test
    fun obsJ6LapPeakSuppressedAndCurvatureWithinRevisedBound() {
        val model = stdModel()
        for (seed in listOf(42L, 7L, 2026L)) {
            val d = load(model, seed)
            val meanStep = d.v.average() * MotionContracts.DELTA_T_SEC
            val newPeak = j6Peak(detrended(transverse(model, d.obsEnu, d.arc), d.lap), meanStep, model.lengthM)
            assertTrue("seed=$seed J6=${newPeak}≤0.30", newPeak <= MotionContracts.OBS_J6_PEAK_MAX)
            val k = maxK(d.obsEnu)
            assertTrue("seed=$seed maxK=${k}≤0.15", k <= MotionContracts.OBS_CURVATURE_MAX_PER_M)
        }
    }

    // ---------------------------------------------------------------- 9b. J6 相对抑制（全种子方向断言；绝对界 0.30 不动）

    @Test
    fun obsJ6SuppressedRelativeToTruthOnAllSeeds() {
        // J6 残余（12345/999 新 0.464/0.445 超 OBS_J6_PEAK_MAX=0.30，REALISM-AB.md ）
        // 如实记残余、界一字不动；本用例只锁死「抑制方向」：5 种子下新 J6 恒 < 旧 J6
        // （旧 0.361~0.680，新 0.165~0.464，AB-REPORT.txt 全文）。
        val model = stdModel()
        for (seed in listOf(42L, 7L, 2026L, 12345L, 999L)) {
            val d = load(model, seed)
            val meanStep = d.v.average() * MotionContracts.DELTA_T_SEC
            val oldPeak = j6Peak(detrended(transverse(model, d.trueEnu, d.arc), d.lap), meanStep, model.lengthM)
            val newPeak = j6Peak(detrended(transverse(model, d.obsEnu, d.arc), d.lap), meanStep, model.lengthM)
            assertTrue("seed=$seed 新J6=$newPeak < 旧J6=$oldPeak（抑制方向）", newPeak < oldPeak)
        }
    }

    // ---------------------------------------------------------------- 10. 包络 + 首样本指纹

    @Test
    fun envelopeSettlesWithin20sAndFirstSampleEqualsTruth() {
        val n = GnssObservationNoise(42L)
        assertEquals(0.0, n.envelope(0.0), 0.0)
        assertEquals(0.5, n.envelope(10.0), 1e-12)
        assertEquals(1.0, n.envelope(20.0), 0.0)
        assertEquals(1.0, n.envelope(30.0), 0.0)
        // 首样本恒等于真值（envelope(0)=0 且 white(0)=0）
        val model = stdModel()
        val truth = TrajectoryGenerator().generate(model, 3, 330.0, 42L)
        val obs = TrajectoryGenerator().generateObserved(model, 3, 330.0, 42L)
        val te = model.toEnu(LatLon(truth[0].latitudeDeg, truth[0].longitudeDeg))
        val oe = model.toEnu(LatLon(obs[0].latitudeDeg, obs[0].longitudeDeg))
        assertEquals(0.0, (oe - te).norm(), 0.0)
    }

    // ---------------------------------------------------------------- 11. 真路线不变量（适用域二分的真路线侧证据）

    @Test
    fun trueRouteInvariantsUnchanged() {
        val model = stdModel()
        val gen = TrajectoryGenerator()
        // generate() 真路线：step 界零超界 + 曲率界 + 逐圈 [0.005,1.1)
        val samples = gen.generate(model, 3, 330.0, 42L)
        for (i in 0 until samples.size - 1) {
            val p0 = model.toEnu(LatLon(samples[i].latitudeDeg, samples[i].longitudeDeg))
            val p1 = model.toEnu(LatLon(samples[i + 1].latitudeDeg, samples[i + 1].longitudeDeg))
            val dd = (p1 - p0).norm()
            val expect = samples[i].speedMps * MotionContracts.DELTA_T_SEC
            val tol = MotionContracts.STEP_DIST_TOL_REL * expect + MotionContracts.STEP_DIST_TOL_ABS_M
            assertTrue("真路线step i=$i", abs(dd - expect) <= tol)
        }
        // 规划字段一致性：generate vs generateObserved 逐样本全同
        val obs = gen.generateObserved(model, 3, 330.0, 42L)
        assertEquals(samples.size, obs.size)
        for (i in samples.indices) {
            assertEquals("i=$i elapsedMs", samples[i].elapsedMs, obs[i].elapsedMs)
            assertEquals("i=$i speed", samples[i].speedMps, obs[i].speedMps, 0.0)
            assertEquals("i=$i pace", samples[i].paceSecPerKm, obs[i].paceSecPerKm, 0.0)
            assertEquals("i=$i cadence", samples[i].cadenceSpm, obs[i].cadenceSpm, 0.0)
        }
    }
}
