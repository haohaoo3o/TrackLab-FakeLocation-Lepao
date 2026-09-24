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
 * 断言契约（数值界全表配速行、特征断言窗口定义——
 * 窗口+算术平均；|Δpace|≤35.0 依 OU 递推界推导；p_base 区间 [180,660]）。
 *
 * 覆盖：范围（pace∈[180,660]，唯一夹紧点）、连续性（|Δpace|≤35.0/500ms 步）、可复现性（同 seed 逐点一致、
 * 不同 seed 有差异、与公式独立复算逐点一致）、特征统计（窗口+算术平均 A1/A2/A3，
 * 钉死 seed=42）、起步/停步/弯道因子形状、p_base 端点硬夹削平（已知行为）。
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

        // 附录 A seed=42 实测回归护栏（窗口样本数 146/119/92/159；means 364.9458/405.1165/333.9270/347.8182）
        assertEquals("W_start 样本数（附录 A）", 146, start.size)
        assertEquals("W_end 样本数（附录 A）", 119, end.size)
        assertEquals("W_base 样本数（附录 A）", 92, base.size)
        assertEquals("W_bend 样本数（附录 A）", 159, bend.size)
        assertEquals("mean(W_start)（附录 A 印刷精度）", 364.9458, meanStart, 1e-4)
        assertEquals("mean(W_end)（附录 A 印刷精度）", 405.1165, meanEnd, 1e-4)
        assertEquals("mean(W_base)（附录 A 印刷精度）", 333.9270, meanBase, 1e-4)
        assertEquals("mean(W_bend)（附录 A 印刷精度）", 347.8182, meanBend, 1e-4)

        // A1/A2/A3（算术平均）
        val a1 = meanStart / meanBase
        val a2 = meanEnd / meanBase
        val a3 = meanBend / meanBase
        println("[CALIB] A1=$a1 A2=$a2 A3=$a3")
        assertTrue("A1 mean(W_start) ≥ mean(W_base)×1.05（实际 $a1）", a1 >= 1.05)
        assertTrue("A2 mean(W_end) ≥ mean(W_base)×1.05（实际 $a2）", a2 >= 1.05)
        assertTrue("A3 mean(W_bend) ≥ mean(W_base)×1.03（实际 $a3）", a3 >= 1.03)

        // 逐步 |Δpace|（断言全集；实测 max=9.4196）
        var maxDelta = 0.0
        for (i in 1 until run.size) {
            maxDelta = max(maxDelta, abs(run[i].second - run[i - 1].second))
        }
        println("[CALIB] maxStepPaceDelta=$maxDelta")
        assertTrue(
            "|Δpace|max=$maxDelta ≤ ${MotionContracts.PACE_STEP_MAX_DELTA}",
            maxDelta <= MotionContracts.PACE_STEP_MAX_DELTA
        )
        assertEquals("|Δpace|max（附录 A 实测 9.4196，印刷精度）", 9.4196, maxDelta, 1e-4)
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

    // ---------------------------------------------------------------- 因子形状（起步/停步/弯道适度降速）

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

    /** 公式独立复算链（字面实现契约公式与消耗次序；java.util.Random）。*/
    private class OracleChain(seed: Long) {
        private val rnd = Random(seed + MotionContracts.SEED_OFFSET_PACE)
        private val xs = ArrayList<Double>()
        private val rho = MotionContracts.OU_RHO
        private val lengthM = 4.0 * STD_A + 2.0 * Math.PI * STD_R

        init {
            xs.add(clamp(MotionContracts.PACE_NOISE_SIGMA * rnd.nextGaussian(), MotionContracts.PACE_NOISE_X_MAX))
        }

        fun paceAt(runDistanceM: Double, lapArcS: Double, tSec: Double): Double {
            val nu = nu(tSec)
            val x0 = (runDistanceM / MotionContracts.F_START_ZONE_M).coerceIn(0.0, 1.0)
            val fStart = MotionContracts.F_START_FROM +
                (MotionContracts.F_START_TO - MotionContracts.F_START_FROM) * (x0 * x0 * (3 - 2 * x0))
            val x1 = ((runDistanceM - (TOTAL - MotionContracts.F_END_ZONE_M)) /
                MotionContracts.F_END_ZONE_M).coerceIn(0.0, 1.0)
            val fEnd = MotionContracts.F_END_FROM +
                (MotionContracts.F_END_TO - MotionContracts.F_END_FROM) * (x1 * x1 * (3 - 2 * x1))
            val fBend = 1.0 + MotionContracts.F_BEND_AMPLITUDE * w(lapArcS)
            val pInt = MotionContracts.FEATURE_PACE_BASE * fStart * fBend * fEnd * (1.0 + nu)
            return pInt.coerceIn(MotionContracts.PACE_MIN_S_PER_KM, MotionContracts.PACE_MAX_S_PER_KM)
        }

        private fun nu(tSec: Double): Double {
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
