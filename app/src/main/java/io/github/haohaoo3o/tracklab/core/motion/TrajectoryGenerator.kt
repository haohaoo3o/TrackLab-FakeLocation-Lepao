package io.github.haohaoo3o.tracklab.core.motion

import io.github.haohaoo3o.tracklab.core.geo.TrackModel
import io.github.haohaoo3o.tracklab.core.model.TrackSample

/**
 * 轨迹生成管线（docs/CONTRACTS.md）：回放 + 导出共用同一 `List<TrackSample>`，单向管线：
 *
 * 1. [MotionClock] 等时推进（Δt=0.5s）：ds = v·Δt；
 * 2. [PaceProfile] / [CadenceProfile] 取值（各自子流 OU/EMA）；
 * 3. [LapPerturber] 求偏移 Δ̂_k(s)（平滑横向偏移 + 弯道/过渡段差异）；
 * 4. 点 = C(s) + Δ̂·N(s)；
 * 5. → WGS-84；
 * 6. [BoundaryGuard] 硬夹（防御性；|Δ̂|<0.55<W ⇒ 生成路径恒不触发钳制）；
 * 7. [TrackSample] 输出（恒等式 |pace − 1000/v| < 1e-9，无步幅字段）。
 *
 * 度量口径：距离/曲率界在最终序列度量；相邻样本距离界在生成（重采样）输出度量。
 * MetricResampler 仅预览/走廊显示，**不进本管线**。
 *
 * 输入校验（口径）：p_base ∈ [180, 660]、laps ∈ [1, 100]、seed 为任意 long（文本解析归 MapEditViewModel）。
 */
class TrajectoryGenerator {

    /**
     * 生成 [laps] 圈轨迹样本（起点 s=0、t=0；每步 Δt=[MotionContracts.DELTA_T_SEC]）。
     *
     * @param model 六点拟合后的跑道几何（C(s)/N(s)/wrapArc/toWgs84）
     * @param laps 圈数（1–100）
     * @param pBase 基准配速 p_base（s/km，[180, 660]）
     * @param seed 随机种子（子流 seed/seed+1/seed+2）
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
            // ④ 点 = C(s) + Δ̂·N(s)；⑥ BoundaryGuard 防御性硬夹（生成路径恒不触发）
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

    /** 圈序号 = ⌊run/L⌋（终点前不越界，钳到 laps−1）。 */
    private fun lapIndexOf(runDistanceM: Double, lengthM: Double, laps: Int): Int {
        val idx = Math.floor(runDistanceM / lengthM).toInt()
        return if (idx < 0) 0 else if (idx > laps - 1) laps - 1 else idx
    }

    companion object {

        /** 圈数上限（laps 整数 1–100）。*/
        const val MAX_LAPS = 100
    }
}
