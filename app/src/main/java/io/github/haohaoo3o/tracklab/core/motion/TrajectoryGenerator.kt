package io.github.haohaoo3o.tracklab.core.motion

import io.github.haohaoo3o.tracklab.core.geo.TrackModel
import io.github.haohaoo3o.tracklab.core.geo.Vec2
import io.github.haohaoo3o.tracklab.core.model.TrackSample

/**
 * 轨迹生成管线：回放 + 导出共用同一 `List<TrackSample>`，单向管线：
 *
 * 1. [MotionClock] 等时推进（Δt=0.5s）：ds = v·Δt；
 * 2. [PaceProfile] / [CadenceProfile] 取值（各自子流 OU/EMA）；
 * 3. [LapPerturber] 求偏移 Δ̂_k(s)（平滑横向偏移 + 弯道/过渡段差异）；
 * 4. 点 = C(s) + Δ̂·N(s)；
 * 5. → WGS-84；
 * 6. [BoundaryGuard] 硬夹（防御性；|Δ̂|<1.2<W ⇒ 真路线生成路径恒不触发钳制）；
 * 6b. [GnssObservationNoise] 输出覆盖（第 2 轮增补；仅 [generateObserved]）：
 *     观测 ENU = 真 ENU + n(t)，n 为 E/N 两路 OU+白噪声；不回灌 s 推进与配速字段；
 * 7. [TrackSample] 输出（恒等式 |pace − 1000/v| < 1e-9，无步幅字段）。
 *
 * 度量口径：距离/曲率界在最终序列度量；相邻样本距离界在生成（重采样）输出度量。
 *  适用域：既有真路线界（ step/curvature、 零越界）在 [generate] 输出下成立；
 * [generateObserved] 的观测序列适用  独立界（OBS_* 常量），真路线侧断言一字不动。
 * MetricResampler 仅预览/走廊显示，**不进本管线**。
 *
 * 输入校验（ 口径）：p_base ∈ [180, 660]、laps ∈ [1, 100]、seed 为任意 long（文本解析归 MapEditViewModel）。
 */
class TrajectoryGenerator {

    /**
     * 生成 [laps] 圈轨迹样本（起点 s=0、t=0；每步 Δt=[MotionContracts.DELTA_T_SEC]）。
     *
     * 真路线管线（ 第 1–7 步； 零越界保证的载体；回放/导出的默认事实源）。
     *
     * @param model 六点拟合后的跑道几何（C(s)/N(s)/wrapArc/toWgs84）
     * @param laps 圈数（1–100）
     * @param pBase 基准配速 p_base（s/km，[180, 660]）
     * @param seed 随机种子（真路线子流 seed(+0)/seed+1/seed+2/seed+3/seed+4/seed+7 + 观测子流 seed+5/+6；
     * pace 全局ν(+0)跑走档后由 run/walk OU(+3/+4)取代；观测 seed+5/+6 与跑走 +3/+4/+7 无冲突，见）
     * @throws IllegalArgumentException 参数越界（中文可读错误）
     */
    fun generate(model: TrackModel, laps: Int, pBase: Double, seed: Long): List<TrackSample> {
        require(laps in 1..MAX_LAPS) { "圈数 laps 必须为 1–$MAX_LAPS 的整数（laps=$laps）" }
        require(pBase >= MotionContracts.PACE_MIN_S_PER_KM && pBase <= MotionContracts.PACE_MAX_S_PER_KM) {
            "基准配速 p_base 必须在 [${MotionContracts.PACE_MIN_S_PER_KM}, ${MotionContracts.PACE_MAX_S_PER_KM}] s/km（p_base=$pBase）"
        }

        val clock = MotionClock()
        val totalDistanceM = laps * model.lengthM
        val paceProfile = PaceProfile(pBase, totalDistanceM, model.a, model.r, seed)
        val cadenceProfile = CadenceProfile(seed)
        val lapPerturber = LapPerturber(seed, model.lengthM, model.a, model.r)

        val out = ArrayList<TrackSample>()
        var stepIndex = 0L
        var runDistanceM = 0.0
        while (runDistanceM < totalDistanceM) {
            val tSec = clock.elapsedSec(stepIndex)
            val lapArcS = model.wrapArc(runDistanceM)
            val lapIndex = lapIndexOf(runDistanceM, model.lengthM, laps)

            // ② Pace / Cadence 取值（各自子流 OU/EMA）
            val paceSecPerKm = paceProfile.paceAt(runDistanceM, lapArcS, tSec)
            val speedMps = 1000.0 / paceSecPerKm
            val cadenceStep = cadenceProfile.next(speedMps, tSec)

            // ③ 逐圈偏移 Δ̂_k(s)（含弯道半径/过渡段差异 d_k·w(s)）
            val rawLateral = lapPerturber.offsetAt(lapIndex, lapArcS)
            // ④ 点 = C(s) + Δ̂·N(s)；⑥ BoundaryGuard 防御性硬夹（真路线恒不触发）
            val lateral = BoundaryGuard.clampLateral(rawLateral, model.r)
            val enu = model.point(lapArcS) + model.normal(lapArcS) * lateral
            // ⑤ → WGS-84；⑦ TrackSample 输出
            val wgs = model.toWgs84(enu)
            out.add(
                TrackSample(
                    latitudeDeg = wgs.latitudeDeg,
                    longitudeDeg = wgs.longitudeDeg,
                    elapsedMs = clock.elapsedMs(stepIndex),
                    speedMps = speedMps,
                    paceSecPerKm = paceSecPerKm,
                    cadenceSpm = cadenceStep.cadenceSpm
                )
            )

            // ① 等时推进 ds = v·Δt
            runDistanceM += clock.stepDistanceM(speedMps)
            stepIndex++
        }
        return out
    }

    /**
     * 生成 [laps] 圈**观测**轨迹样本（ + 6b； 输出覆盖层；生产默认入口）。
     *
     * - 生产默认链路（`MapEditViewModel.generateSamples`）调本入口（无开关、无关闭回退路径；
     *   回放与导出同经该单点，同源交付）。
     * - 真路线推进与 [generate] **逐样本一致**（同 seed 下真 ENU、s 推进、pace/speed/cadence/
     *   elapsedMs 全同；实现上复用同一推进环，仅输出坐标叠加 n(t)）。
     * - 观测 ENU = 真 ENU + (n_E, n_N)（[GnssObservationNoise]，E/N 两路 OU+白噪声，
     *   子流 seed+5/+6）；n **不回灌** s 推进、不进 pace/speed/cadence/elapsedMs 字段。
     * - 里程核算仍按 s 推进（与 s4 口径一致：有效里程按推进核算，观测膨胀另由 inflate 界约束）。
     * - 里程口径（C4）：生产回放累计 machine.advance 的步长源恒为样本规划 v·Δt
     *  （PlaybackForegroundService 按 sample.speedMps·Δt 推进，不读 synth speed）；
     *   TruthSession.distanceM（=Σsynth.speed·Δt，含噪位移/Δt）为审计侧影子量，
     *   与 machine.distanceM 的双轨差上界 = Σtol_obs（G8 auditAccumulators 的
     *   toleranceSum 即  序列界累计容差；观测下适用 tol_obs，见 ）。
     *
     * @param model 六点拟合后的跑道几何
     * @param laps 圈数（1–100）
     * @param pBase 基准配速 p_base（s/km，[180, 660]）
     * @param seed 随机种子（真路线子流 seed(+0)/seed+1/seed+2/seed+3/seed+4/seed+7 + 观测子流 seed+5/+6；
     * pace 全局ν(+0)跑走档后由 run/walk OU(+3/+4)取代；观测 seed+5/+6 与跑走 +3/+4/+7 无冲突，见）
     * @throws IllegalArgumentException 参数越界（中文可读错误）
     */
    fun generateObserved(model: TrackModel, laps: Int, pBase: Double, seed: Long): List<TrackSample> {
        require(laps in 1..MAX_LAPS) { "圈数 laps 必须为 1–$MAX_LAPS 的整数（laps=$laps）" }
        require(pBase >= MotionContracts.PACE_MIN_S_PER_KM && pBase <= MotionContracts.PACE_MAX_S_PER_KM) {
            "基准配速 p_base 必须在 [${MotionContracts.PACE_MIN_S_PER_KM}, ${MotionContracts.PACE_MAX_S_PER_KM}] s/km（p_base=$pBase）"
        }

        val clock = MotionClock()
        val totalDistanceM = laps * model.lengthM
        val paceProfile = PaceProfile(pBase, totalDistanceM, model.a, model.r, seed)
        val cadenceProfile = CadenceProfile(seed)
        val lapPerturber = LapPerturber(seed, model.lengthM, model.a, model.r)
        val obsNoise = GnssObservationNoise(seed)

        val out = ArrayList<TrackSample>()
        var stepIndex = 0L
        var runDistanceM = 0.0
        while (runDistanceM < totalDistanceM) {
            val tSec = clock.elapsedSec(stepIndex)
            val lapArcS = model.wrapArc(runDistanceM)
            val lapIndex = lapIndexOf(runDistanceM, model.lengthM, laps)

            // ② Pace / Cadence 取值（各自子流 OU/EMA；与 generate 同 seed 逐点一致）
            val paceSecPerKm = paceProfile.paceAt(runDistanceM, lapArcS, tSec)
            val speedMps = 1000.0 / paceSecPerKm
            val cadenceStep = cadenceProfile.next(speedMps, tSec)

            // ③–④–⑥ 真路线（与 generate 同构； 零越界载体）
            val rawLateral = lapPerturber.offsetAt(lapIndex, lapArcS)
            val lateral = BoundaryGuard.clampLateral(rawLateral, model.r)
            val trueEnu = model.point(lapArcS) + model.normal(lapArcS) * lateral
            // 6b 观测覆盖：观测 ENU = 真 ENU + n(t)（ENU 域相加；WGS 域只做投影）
            val obsEnu = Vec2(
                trueEnu.x + obsNoise.eastAt(tSec, stepIndex),
                trueEnu.y + obsNoise.northAt(tSec, stepIndex),
            )
            // ⑤ → WGS-84；⑦ TrackSample 输出（速度/配速/步频/时刻仍为规划事实，不含 n）
            val wgs = model.toWgs84(obsEnu)
            out.add(
                TrackSample(
                    latitudeDeg = wgs.latitudeDeg,
                    longitudeDeg = wgs.longitudeDeg,
                    elapsedMs = clock.elapsedMs(stepIndex),
                    speedMps = speedMps,
                    paceSecPerKm = paceSecPerKm,
                    cadenceSpm = cadenceStep.cadenceSpm
                )
            )

            // ① 等时推进 ds = v·Δt（不受 n 影响；:77 语义不动）
            runDistanceM += clock.stepDistanceM(speedMps)
            stepIndex++
        }
        return out
    }

    /** 圈序号 = ⌊run/L⌋（终点前不越界，钳到 laps−1）。 */
    private fun lapIndexOf(runDistanceM: Double, lengthM: Double, laps: Int): Int {
        val idx = Math.floor(runDistanceM / lengthM).toInt()
        return if (idx < 0) 0 else if (idx > laps - 1) laps - 1 else idx
    }

    companion object {

        /** 圈数上限（：laps 整数 1–100）。 */
        const val MAX_LAPS = 100
    }
}
