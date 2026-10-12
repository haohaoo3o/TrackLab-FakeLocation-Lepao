package io.github.haohaoo3o.tracklab.motion

import io.github.haohaoo3o.tracklab.core.geo.LatLon
import io.github.haohaoo3o.tracklab.core.geo.TrackModel
import io.github.haohaoo3o.tracklab.core.geo.Vec2
import io.github.haohaoo3o.tracklab.core.motion.MotionContracts
import io.github.haohaoo3o.tracklab.core.motion.PaceProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.max

/**
 * 断言契约见 docs/CONTRACTS.md（数值界全表配速行、特征断言窗口定义——终审 blocker 2
 * 修正：窗口+算术平均；|Δpace|≤45.0 跑走周期最坏情况重推导；p_base 区间 [180,660]）与。填充人：motion 阶段。
 *
 * 覆盖：范围（pace∈[180,660]，唯一夹紧点）、连续性（|Δpace|≤45.0/500ms 步）、可复现性（同 seed 逐点一致、
 * 不同 seed 有差异、与 公式独立复算逐点一致）、特征统计（ 窗口+算术平均 A1/A3 有效、
 * A2 停步窗口豁免（跑走调制已知行为，见）、钉死 seed=42）、起步/停步/弯道/跑走因子形状、
 * p_base 端点硬夹削平（已知行为）、跑走快慢分段（跑段更快、走段更慢）。
 */
class PaceProfileTest {

    // ---------------------------------------------------------------- 特征断言（窗口+算术平均，seed=42 钉死）

    @Test
    fun featureWindowsA1A2A3WithPinnedSeed42() {
        val run = featureRun(seed = 42L)
        val model = stdModel()
        val a = STD_A
        val r = STD_R
        val l = model.lengthM
        val hw = minOf(
            MotionContracts.BEND_WINDOW_HW_BASE_M,
            MotionContracts.BEND_WINDOW_HW_REL_A * a,
            MotionContracts.BEND_WINDOW_HW_REL_ARC * Math.PI * r
        )
        val total = MotionContracts.FEATURE_LAPS * l

        val base = ArrayList<Double>()
        val bend = ArrayList<Double>()
        val start = ArrayList<Double>()
        val end = ArrayList<Double>()
        for ((s, pace) in run) {
            val lap = Math.floor(s / l).toInt()
            val arc = s - lap * l
            if (lap == 1 && ((arc >= hw && arc <= 2 * a - hw) ||
                    (arc >= 2 * a + Math.PI * r + hw && arc <= 4 * a + Math.PI * r - hw))
            ) base.add(pace)
            if (lap == 1 && ((arc >= 2 * a && arc <= 2 * a + Math.PI * r) ||
                    (arc >= 4 * a + Math.PI * r && arc <= l))
            ) bend.add(pace)
            if (s >= 0.0 && s <= MotionContracts.F_START_ZONE_M) start.add(pace)
            if (s >= total - MotionContracts.F_END_ZONE_M && s <= total) end.add(pace)
        }

        val meanBase = mean(base)
        val meanBend = mean(bend)
        val meanStart = mean(start)
        val meanEnd = mean(end)
        println(
            "[CALIB] nStart=${start.size} nEnd=${end.size} nBase=${base.size} nBend=${bend.size} " +
                "means start=$meanStart end=$meanEnd base=$meanBase bend=$meanBend"
        )

        // 附录 A seed=42 实测回归护栏（跑走周期档：窗口样本数 153/110/96/198；
        // means 404.6181/371.4791/368.2381/466.0848）
        assertEquals("W_start 样本数（附录 A）", 153, start.size)
        assertEquals("W_end 样本数（附录 A）", 110, end.size)
        assertEquals("W_base 样本数（附录 A）", 96, base.size)
        assertEquals("W_bend 样本数（附录 A）", 198, bend.size)
        assertEquals("mean(W_start)（附录 A 印刷精度）", 404.6181, meanStart, 1e-4)
        assertEquals("mean(W_end)（附录 A 印刷精度）", 371.4791, meanEnd, 1e-4)
        assertEquals("mean(W_base)（附录 A 印刷精度）", 368.2381, meanBase, 1e-4)
        assertEquals("mean(W_bend)（附录 A 印刷精度）", 466.0848, meanBend, 1e-4)

        // A1/A2/A3（算术平均；跑走调制下 A2 停步窗口豁免——走段相位落入 W_base
        // 抬高基准（seed=42 A2=1.0088<1.05），豁免登记见，仅回归值锁定）
        val a1 = meanStart / meanBase
        val a2 = meanEnd / meanBase
        val a3 = meanBend / meanBase
        println("[CALIB] A1=$a1 A2=$a2 A3=$a3")
        assertTrue("A1 mean(W_start) ≥ mean(W_base)×1.05（实际 $a1）", a1 >= 1.05)
        assertEquals("A2 回归值（ 豁免项，仅锁定：实际 $a2）", 1.0088, a2, 1e-4)
        assertTrue("A3 mean(W_bend) ≥ mean(W_base)×1.03（实际 $a3）", a3 >= 1.03)

        // 逐步 |Δpace|（ 断言全集；附录 A 实测 max=31.0871）
        var maxDelta = 0.0
        for (i in 1 until run.size) {
            maxDelta = max(maxDelta, abs(run[i].second - run[i - 1].second))
        }
        println("[CALIB] maxStepPaceDelta=$maxDelta")
        assertTrue(
            "|Δpace|max=$maxDelta ≤ ${MotionContracts.PACE_STEP_MAX_DELTA}",
            maxDelta <= MotionContracts.PACE_STEP_MAX_DELTA
        )
        assertEquals("|Δpace|max（附录 A 实测 31.0871，印刷精度）", 31.0871, maxDelta, 1e-4)
    }

    // ---------------------------------------------------------------- 数值界（范围/连续性）

    @Test
    fun paceStaysInPinnedRangeWithSingleClampPoint() {
        val run = featureRun(seed = 42L)
        for ((s, pace) in run) {
            assertTrue(
                "s=$s：pace=$pace ∈ [180, 660]（唯一夹紧点）",
                pace >= MotionContracts.PACE_MIN_S_PER_KM && pace <= MotionContracts.PACE_MAX_S_PER_KM
            )
        }
    }

    @Test
    fun stepwisePaceContinuityWithinCalibratedBound() {
        // 连续性断言以标准试样校准（已知行为）
        for (seed in listOf(42L, 7L, 2026L)) {
            val run = featureRun(seed = seed)
            for (i in 1 until run.size) {
                val delta = abs(run[i].second - run[i - 1].second)
                assertTrue(
                    "seed=$seed 步 ${i - 1}→$i：|Δpace|=$delta ≤ ${MotionContracts.PACE_STEP_MAX_DELTA}",
                    delta <= MotionContracts.PACE_STEP_MAX_DELTA
                )
            }
        }
    }

    // ---------------------------------------------------------------- 可复现性（同 seed 一致 / 不同 seed 有差异 / 公式独立复算）

    @Test
    fun reproducibleWithSameSeedAndFormulaOracle() {
        val seed = 42L
        val p1 = PaceProfile(MotionContracts.FEATURE_PACE_BASE, TOTAL, STD_A, STD_R, seed)
        val p2 = PaceProfile(MotionContracts.FEATURE_PACE_BASE, TOTAL, STD_A, STD_R, seed)
        val oracle = OracleChain(seed)
        val model = stdModel()
        var differFromOracle = 0
        for (k in 0 until 2000) {
            val t = k * MotionContracts.DELTA_T_SEC
            val s = k * 1.37
            val arc = model.wrapArc(s)
            val v1 = p1.paceAt(s, arc, t)
            val v2 = p2.paceAt(s, arc, t)
            assertEquals("k=$k 同 seed 逐点一致", v1, v2, 0.0)
            val vo = oracle.paceAt(s, arc, t)
            if (abs(v1 - vo) > 1e-9) differFromOracle++
            assertEquals("k=$k 与公式独立复算一致", vo, v1, 1e-9)
        }
        assertEquals("全部点与公式复算一致", 0, differFromOracle)
    }

    @Test
    fun differentSeedsProduceDifferentPaceSeries() {
        val a = PaceProfile(MotionContracts.FEATURE_PACE_BASE, TOTAL, STD_A, STD_R, 42L)
        val b = PaceProfile(MotionContracts.FEATURE_PACE_BASE, TOTAL, STD_A, STD_R, 43L)
        val model = stdModel()
        var diff = 0
        for (k in 0 until 400) {
            val t = k * MotionContracts.DELTA_T_SEC
            val s = k * 1.37
            if (abs(a.paceAt(s, model.wrapArc(s), t) - b.paceAt(s, model.wrapArc(s), t)) > 1e-12) diff++
        }
        assertTrue("不同 seed 应产生差异（diff=$diff）", diff > 0)
    }

    // ---------------------------------------------------------------- 因子形状（起步/停步/弯道适度降速/跑走周期）

    @Test
    fun startEndBendFactorsMatchPinnedShapes() {
        val p = PaceProfile(MotionContracts.FEATURE_PACE_BASE, TOTAL, STD_A, STD_R, 42L)
        // f_start：前 200m smoothstep 1.15→1.00（max 斜率 1.5×0.15/200）
        assertEquals(1.15, p.fStart(0.0), 1e-12)
        assertEquals(1.00, p.fStart(MotionContracts.F_START_ZONE_M), 1e-12)
        assertEquals(1.00, p.fStart(MotionContracts.F_START_ZONE_M + 500), 1e-12)
        assertEquals(1.15 - 0.15 * 0.5, p.fStart(100.0), 1e-12) // smoothstep 中点 0.5
        // f_end：后 150m smoothstep 1.00→1.35
        assertEquals(1.00, p.fEnd(0.0), 1e-12)
        assertEquals(1.00, p.fEnd(TOTAL - MotionContracts.F_END_ZONE_M), 1e-12)
        assertEquals(1.00 + 0.35 * 0.5, p.fEnd(TOTAL - 75.0), 1e-12)
        assertEquals(1.35, p.fEnd(TOTAL), 1e-12)
        // f_bend = 1 + 0.05·w(s)：弯道上 w=1 ⇒ 1.05（适度降速）；直道中段 w=0 ⇒ 1.00
        val model = stdModel()
        assertEquals(1.05, p.fBend(model.leftBendEndM - 1e-9), 1e-9) // 左弯内
        assertEquals(1.05, p.fBend(model.bottomStraightEndM + 1e-9), 1e-9) // 右弯内
        assertEquals(1.00, p.fBend(STD_A), 1e-12) // 顶直道中点（距两弯 hw 以上）
        // f_rw 跑走因子：圈界恒为跑段（m=0 ⇒ 0.9）；走中心处恒为走段（m=1 ⇒ 1.6）；
        // 过渡带宽 RUN_WALK_BLEND_M=15m（MotionContracts 唯一来源）
        val l = model.lengthM
        for (lap in 0..2) {
            assertEquals("lap=$lap 圈界恒为跑段", 0.0, p.rwMix(0.0, lap), 0.0)
            assertEquals("lap=$lap f_rw 圈界 = 0.9", MotionContracts.F_RW_RUN, p.fRunWalk(0.0, lap), 1e-12)
            val c = l * (PaceProfile.RW_CENTER_MIN_FRAC +
                PaceProfile.RW_CENTER_SPAN_FRAC * p.phaseAt(lap))
            assertEquals("lap=$lap 走中心恒为走段", 1.0, p.rwMix(c, lap), 1e-12)
            assertEquals("lap=$lap f_rw 走中心 = 1.6", MotionContracts.F_RW_WALK, p.fRunWalk(c, lap), 1e-12)
            assertTrue("lap=$lap 走中心 ∈ [0.3L, 0.7L]", c >= 0.3 * l && c <= 0.7 * l)
        }
    }

    @Test
    fun runWalkSegmentsShowRunFasterWalkSlower() {
        // 跑段更快、走段更慢（seed=42 钉死；均值关系由特征推进环实测，印刷精度 1e-4）
        val model = stdModel()
        val total = MotionContracts.FEATURE_LAPS * model.lengthM
        val probe = PaceProfile(MotionContracts.FEATURE_PACE_BASE, total, STD_A, STD_R, 42L)
        val run = featureRun(seed = 42L)
        val runSeg = ArrayList<Double>()
        val walkSeg = ArrayList<Double>()
        for ((s, pace) in run) {
            val lap = Math.floor(s / model.lengthM).toInt()
            val arc = s - lap * model.lengthM
            if (probe.rwMix(arc, maxOf(0, lap)) < 0.5) runSeg.add(pace) else walkSeg.add(pace)
        }
        assertTrue("跑段非空", runSeg.isNotEmpty())
        assertTrue("走段非空", walkSeg.isNotEmpty())
        val meanRun = mean(runSeg)
        val meanWalk = mean(walkSeg)
        println("[CALIB] meanRun=$meanRun meanWalk=$meanWalk ratio=${meanWalk / meanRun}")
        assertTrue("走段更慢（meanWalk=$meanWalk > meanRun=$meanRun）", meanWalk > meanRun)
        assertEquals("meanRun（附录 A 印刷精度）", 329.5201, meanRun, 1e-4)
        assertEquals("meanWalk（附录 A 印刷精度）", 542.6356, meanWalk, 1e-4)
    }

    @Test
    fun phaseDiffersAcrossLapsWithPinnedSeed42() {
        // 相位每圈由 seed 派生随机偏移，保证圈间不同（seed=42 钉死；消耗次序：圈号顺序每圈 1 个 nextDouble）
        val p = PaceProfile(MotionContracts.FEATURE_PACE_BASE, TOTAL, STD_A, STD_R, 42L)
        val p0 = p.phaseAt(0)
        val p1 = p.phaseAt(1)
        val p2 = p.phaseAt(2)
        for ((i, ph) in listOf(p0, p1, p2).withIndex()) {
            assertTrue("lap=$i 相位 ∈ [0,1)", ph >= 0.0 && ph < 1.0)
        }
        assertTrue("圈间相位不同（p0=$p0 p1=$p1 p2=$p2）", p0 != p1 && p1 != p2 && p0 != p2)
        // 同 seed 复现、不同 seed 有差异
        val q = PaceProfile(MotionContracts.FEATURE_PACE_BASE, TOTAL, STD_A, STD_R, 42L)
        assertEquals("同 seed 相位逐点一致", p0, q.phaseAt(0), 0.0)
        val r = PaceProfile(MotionContracts.FEATURE_PACE_BASE, TOTAL, STD_A, STD_R, 43L)
        assertTrue("不同 seed 相位有差异", r.phaseAt(0) != p0 || r.phaseAt(1) != p1)
    }

    @Test
    fun dualOuStreamsConsumeIndependently() {
        // 快慢各自独立 OU（seed+3/seed+4）：与 同口径独立复算逐点一致，且两路互不相同
        val p = PaceProfile(MotionContracts.FEATURE_PACE_BASE, TOTAL, STD_A, STD_R, 42L)
        val oRun = OracleOu(42L + MotionContracts.SEED_OFFSET_PACE_RUN)
        val oWalk = OracleOu(42L + MotionContracts.SEED_OFFSET_PACE_WALK)
        var diff = 0
        for (k in 0 until 500) {
            val t = k * MotionContracts.DELTA_T_SEC
            assertEquals("k=$k ν_run 独立复算", oRun.value(t), p.noiseRunAt(t), 1e-12)
            assertEquals("k=$k ν_walk 独立复算", oWalk.value(t), p.noiseWalkAt(t), 1e-12)
            if (abs(p.noiseRunAt(t) - p.noiseWalkAt(t)) > 1e-12) diff++
        }
        assertTrue("快慢 OU 相互独立（diff=$diff）", diff > 0)
    }

    @Test
    fun pBaseEndpointsFlattenFeaturesKnownBehavior() {
        // 已知行为：p_base 取 180/660 端点时特征被硬夹削平（特征断言因此固定 p_base=330）。
        // p_base=660：起步段自然值 660·1.15·(1+ν) ≥ 721 > 660、停步段 660·1.35·(1+ν) ≥ 850 > 660
        // ⇒ 起步/停步特征被天花板削平（输出恒恰为 660）。
        val slow = PaceProfile(MotionContracts.PACE_MAX_S_PER_KM, TOTAL, STD_A, STD_R, 42L)
        for (t in listOf(0.0, 5.0, 50.0, 100.0)) {
            assertEquals("p_base=660：起步特征被削平（t=$t）",
                MotionContracts.PACE_MAX_S_PER_KM, slow.paceAt(0.0, STD_A, t), 0.0)
            assertEquals("p_base=660：停步特征被削平（t=$t）",
                MotionContracts.PACE_MAX_S_PER_KM, slow.paceAt(TOTAL, STD_A, t), 0.0)
        }
        // p_base=180：中性段 180·(1+ν) 的噪声下摆被地板削平（输出恒恰 ≥ 180，存在恰为 180 的削平点）
        val fast = PaceProfile(MotionContracts.PACE_MIN_S_PER_KM, TOTAL, STD_A, STD_R, 42L)
        var floorPinned = 0
        for (k in 0 until 2000) {
            val pace = fast.paceAt(300.0, STD_A, k * MotionContracts.DELTA_T_SEC)
            assertTrue("p_base=180：输出恒 ≥ 180（唯一夹紧点）", pace >= MotionContracts.PACE_MIN_S_PER_KM)
            if (pace == MotionContracts.PACE_MIN_S_PER_KM) floorPinned++
        }
        assertTrue("p_base=180：噪声谷被地板削平（floorPinned=$floorPinned > 0）", floorPinned > 0)
    }

    @Test
    fun invalidPaceBaseRejected() {
        for (bad in listOf(179.9, 660.1, 0.0, -330.0)) {
            try {
                PaceProfile(bad, TOTAL, STD_A, STD_R, 42L)
                throw AssertionError("期望 IllegalArgumentException（p_base=$bad）")
            } catch (e: IllegalArgumentException) {
                assertTrue("中文可读错误：" + e.message, e.message!!.contains("p_base"))
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    /**
     *  样本序列（测试本地推进环，不依赖 TrajectoryGenerator）：laps=3、p_base=330、标准试样、
     * s₀=0、t₀=0；每步 Δt=0.5s：ν 依 OU 递推、pace 依配速模型、v=1000/pace、s_{k+1}=s_k+v_k·Δt；
     * 记录 (s_k, pace_k) 直到 s ≥ 3L。
     */
    private fun featureRun(seed: Long): List<Pair<Double, Double>> {
        val model = stdModel()
        val total = MotionContracts.FEATURE_LAPS * model.lengthM
        val profile = PaceProfile(MotionContracts.FEATURE_PACE_BASE, total, STD_A, STD_R, seed)
        val out = ArrayList<Pair<Double, Double>>()
        var s = 0.0
        var k = 0L
        while (true) {
            val t = k * MotionContracts.DELTA_T_SEC
            val pace = profile.paceAt(s, model.wrapArc(s), t)
            out.add(s to pace)
            s += (1000.0 / pace) * MotionContracts.DELTA_T_SEC
            k++
            if (s >= total) break
        }
        return out
    }

    /** 独立复算链（字面实现契约公式与消耗次序；java.util.Random；跑走周期档）。 */
    private class OracleChain(seed: Long) {
        private val rndRun = Random(seed + MotionContracts.SEED_OFFSET_PACE_RUN)
        private val rndWalk = Random(seed + MotionContracts.SEED_OFFSET_PACE_WALK)
        private val xsRun = ArrayList<Double>()
        private val xsWalk = ArrayList<Double>()
        private val phases = ArrayList<Double>()
        private val phaseRnd = Random(seed + MotionContracts.SEED_OFFSET_RW_PHASE)
        private val rho = MotionContracts.OU_RHO
        private val lengthM = 4.0 * STD_A + 2.0 * Math.PI * STD_R

        init {
            xsRun.add(clamp(MotionContracts.PACE_NOISE_SIGMA * rndRun.nextGaussian(), MotionContracts.PACE_NOISE_X_MAX))
            xsWalk.add(clamp(MotionContracts.PACE_NOISE_SIGMA * rndWalk.nextGaussian(), MotionContracts.PACE_NOISE_X_MAX))
        }

        fun paceAt(runDistanceM: Double, lapArcS: Double, tSec: Double): Double {
            val lap = Math.floor(runDistanceM / lengthM).toInt().coerceAtLeast(0)
            val m = rwMix(lapArcS, lap)
            val fRw = MotionContracts.F_RW_RUN +
                (MotionContracts.F_RW_WALK - MotionContracts.F_RW_RUN) * m
            val nuEff = (1.0 - m) * nu(xsRun, rndRun, tSec) + m * nu(xsWalk, rndWalk, tSec)
            val x0 = (runDistanceM / MotionContracts.F_START_ZONE_M).coerceIn(0.0, 1.0)
            val fStart = MotionContracts.F_START_FROM +
                (MotionContracts.F_START_TO - MotionContracts.F_START_FROM) * (x0 * x0 * (3 - 2 * x0))
            val x1 = ((runDistanceM - (TOTAL - MotionContracts.F_END_ZONE_M)) /
                MotionContracts.F_END_ZONE_M).coerceIn(0.0, 1.0)
            val fEnd = MotionContracts.F_END_FROM +
                (MotionContracts.F_END_TO - MotionContracts.F_END_FROM) * (x1 * x1 * (3 - 2 * x1))
            val fBend = 1.0 + MotionContracts.F_BEND_AMPLITUDE * w(lapArcS)
            val pInt = MotionContracts.FEATURE_PACE_BASE * fStart * fBend * fEnd * fRw * (1.0 + nuEff)
            return pInt.coerceIn(MotionContracts.PACE_MIN_S_PER_KM, MotionContracts.PACE_MAX_S_PER_KM)
        }

        private fun phaseAt(lap: Int): Double {
            while (phases.size <= lap) phases.add(phaseRnd.nextDouble())
            return phases[lap]
        }

        private fun rwMix(arc: Double, lap: Int): Double {
            val c = lengthM * (PaceProfile.RW_CENTER_MIN_FRAC +
                PaceProfile.RW_CENTER_SPAN_FRAC * phaseAt(lap))
            val walkHalf = PaceProfile.RW_WALK_FRAC * lengthM / 2.0
            val b = MotionContracts.RUN_WALK_BLEND_M / 2.0
            val d = abs(arc - c)
            fun ss(x: Double): Double {
                val t = x.coerceIn(0.0, 1.0)
                return t * t * (3.0 - 2.0 * t)
            }
            return when {
                d <= walkHalf - b -> 1.0
                d >= walkHalf + b -> 0.0
                d < walkHalf -> 1.0 - ss((d - (walkHalf - b)) / (2.0 * b))
                else -> ss(((walkHalf + b) - d) / (2.0 * b))
            }
        }

        private fun nu(xs: ArrayList<Double>, rnd: Random, tSec: Double): Double {
            val k = Math.floor(tSec / MotionContracts.OU_NODE_INTERVAL_SEC).toInt()
            while (xs.size <= k + 1) {
                val xk = xs[xs.size - 1]
                val raw = rho * xk + MotionContracts.PACE_NOISE_SIGMA *
                    Math.sqrt(1.0 - rho * rho) * rnd.nextGaussian()
                val step = (raw - xk).coerceIn(
                    -MotionContracts.PACE_NOISE_DELTA_MAX, MotionContracts.PACE_NOISE_DELTA_MAX
                )
                xs.add(clamp(xk + step, MotionContracts.PACE_NOISE_X_MAX))
            }
            val frac = (tSec - k * MotionContracts.OU_NODE_INTERVAL_SEC) / MotionContracts.OU_NODE_INTERVAL_SEC
            return xs[k] + frac * (xs[k + 1] - xs[k])
        }

        private fun w(arc: Double): Double {
            val s = wrap(arc)
            val hw = minOf(
                MotionContracts.BEND_WINDOW_HW_BASE_M,
                MotionContracts.BEND_WINDOW_HW_REL_A * STD_A,
                MotionContracts.BEND_WINDOW_HW_REL_ARC * Math.PI * STD_R
            )
            val leftLo = 2.0 * STD_A
            val leftHi = 2.0 * STD_A + Math.PI * STD_R
            val rightLo = 4.0 * STD_A + Math.PI * STD_R

            fun dist(x: Double, y: Double): Double {
                val d = abs(x - y)
                return minOf(d, lengthM - d)
            }

            fun dInterval(lo: Double, hi: Double): Double =
                if (s >= lo && s <= hi) 0.0 else minOf(dist(s, lo), dist(s, hi))

            val dArc = minOf(dInterval(leftLo, leftHi), dInterval(rightLo, lengthM))
            val x = minOf(1.0, dArc / hw)
            return 0.5 * (1.0 + Math.cos(Math.PI * x))
        }

        private fun wrap(s: Double): Double {
            val m = s % lengthM
            return if (m < 0.0) m + lengthM else m
        }

        private fun clamp(v: Double, m: Double) = v.coerceIn(-m, m)
    }

    /** OU 单路独立复算（seed+3/seed+4 各一路；与 PaceProfile 内 OuNoise 逐项一致）。 */
    private class OracleOu(seed: Long) {
        private val rnd = Random(seed)
        private val xs = ArrayList<Double>()
        private val rho = MotionContracts.OU_RHO

        init {
            xs.add(clamp(MotionContracts.PACE_NOISE_SIGMA * rnd.nextGaussian(), MotionContracts.PACE_NOISE_X_MAX))
        }

        fun value(tSec: Double): Double {
            val k = Math.floor(tSec / MotionContracts.OU_NODE_INTERVAL_SEC).toInt()
            while (xs.size <= k + 1) {
                val xk = xs[xs.size - 1]
                val raw = rho * xk + MotionContracts.PACE_NOISE_SIGMA *
                    Math.sqrt(1.0 - rho * rho) * rnd.nextGaussian()
                val step = (raw - xk).coerceIn(
                    -MotionContracts.PACE_NOISE_DELTA_MAX, MotionContracts.PACE_NOISE_DELTA_MAX
                )
                xs.add(clamp(xk + step, MotionContracts.PACE_NOISE_X_MAX))
            }
            val frac = (tSec - k * MotionContracts.OU_NODE_INTERVAL_SEC) / MotionContracts.OU_NODE_INTERVAL_SEC
            return xs[k] + frac * (xs[k + 1] - xs[k])
        }

        private fun clamp(v: Double, m: Double) = v.coerceIn(-m, m)
    }

    private fun mean(v: List<Double>): Double = v.sum() / v.size

    private fun stdModel() = TrackModel(
        origin = LatLon(30.0000, 110.0000), // 合成境内测试坐标，不对应个人位置
        u = Vec2(1.0, 0.0),
        n = Vec2(0.0, 1.0),
        a = STD_A,
        r = STD_R
    )

    companion object {
        private const val STD_A = 42.5
        private const val STD_R = 36.5
        private val TOTAL = MotionContracts.FEATURE_LAPS * (4.0 * STD_A + 2.0 * Math.PI * STD_R)
    }
}
