package io.github.haohaoo3o.tracklab.service

import android.annotation.SuppressLint
import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.content.Intent
import io.github.haohaoo3o.tracklab.MainActivity
import io.github.haohaoo3o.tracklab.core.model.TrackSample

/**
 * [PlaybackEntry] 的前台服务实现（接替 NoOpPlaybackEntry，挂入
 * `di/AppContainer.playbackEntry`；docs/CONTRACTS.md §7）。
 *
 * 会话载体：样本列表可达数万点（Δt=0.5s 逐点），超过 Binder 上限；与服务同进程，
 * 经 [PlaybackSessionStore] 进程内转交（服务 onDestroy 清除——完整资源清理）。
 *
 * **Android 12+ 后台启动 FGS 限制（§7.3）**：从后台（如悬浮窗触发）`startForegroundService` 可能抛
 * [ForegroundServiceStartNotAllowedException]。**公开 API 无绕过手段（如实写明）**——回退拉起
 * [MainActivity]，由用户在前台显式开始；不做任何规避/隐藏。
 */
class ServicePlaybackEntry(private val context: Context) : PlaybackEntry {

    // NewApi 抑制记账：ForegroundServiceStartNotAllowedException 为
    // API 31+ 引入的异常类型——它只可能在 API 31+ 被抛出（低版本 startForegroundService 不抛），
    // 低版本上 catch 类型永不匹配（ART 惰性解析），运行时安全；§7.3 钉死『try/catch
    // ForegroundServiceStartNotAllowedException』的字面形态，故保留直接 catch 并行级抑制。
    @SuppressLint("NewApi")
    override fun startTestPlayback(samples: List<TrackSample>, laps: Int, pBaseSecPerKm: Int, seed: Long) {
        PlaybackSessionStore.set(PlaybackSession(samples, laps, pBaseSecPerKm, seed))
        val intent = Intent(context, PlaybackForegroundService::class.java)
            .setAction(PlaybackForegroundService.ACTION_START)
        try {
            context.startForegroundService(intent)
        } catch (e: ForegroundServiceStartNotAllowedException) {
            // §7.3 回退：拉起 MainActivity（前台显式开始），限制如实写明于 docs。
            val launch = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            context.startActivity(launch)
        }
    }
}
