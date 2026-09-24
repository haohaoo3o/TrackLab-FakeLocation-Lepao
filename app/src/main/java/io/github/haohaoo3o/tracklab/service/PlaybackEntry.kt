package io.github.haohaoo3o.tracklab.service

import io.github.haohaoo3o.tracklab.core.model.TrackSample

/**
 * 测试回放入口端口（以 PlaybackForegroundService 实现替换，
 * 并挂入 `di/AppContainer.playbackEntry`，见 docs/CONTRACTS.md §7）。
 *
 * 显式测试用途边界：入口只驱动本应用内的测试轨迹回放与官方测试提供者输出；
 * **不隐藏 mock 标志、不控制其他应用、不做任何 Hook**。UI 与产物常驻『测试/模拟定位』标识。
 */
interface PlaybackEntry {

    /**
     * 开始测试回放。
     *
     * @param samples 轨迹样本（WGS-84，[TrajectoryGenerator][io.github.haohaoo3o.tracklab.core.motion.TrajectoryGenerator] 输出）
     * @param laps 圈数（1–100）
     * @param pBaseSecPerKm 基准配速 p_base（s/km，180–660）
     * @param seed 随机种子（子流 seed/seed+1/seed+2）
     */
    fun startTestPlayback(samples: List<TrackSample>, laps: Int, pBaseSecPerKm: Int, seed: Long)
}
