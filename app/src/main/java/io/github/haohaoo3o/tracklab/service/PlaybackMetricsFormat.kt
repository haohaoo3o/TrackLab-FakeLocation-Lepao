package io.github.haohaoo3o.tracklab.service

import java.util.Locale

/**
 * 指标展示格式化（当前圈数/距离/配速/步频；HUD / 通知 / 主界面同源）——纯 Kotlin。
 * 距离 km 两位小数、配速 m:ss（s/km）、步频整数（spm）。数值进文案模板
 * `overlay_metrics_format`（双语）由 UI 侧填充。
 */
object PlaybackMetricsFormat {

    /** 距离（米 → km 字符串，两位小数，如 "1.23"）。 */
    fun distanceKm(distanceM: Double): String =
        String.format(Locale.US, "%.2f", distanceM / 1000.0)

    /** 配速（s/km → "m:ss"，如 330 → "5:30"）。 */
    fun pace(paceSecPerKm: Double): String {
        val total = paceSecPerKm.coerceAtLeast(0.0).toInt()
        return String.format(Locale.US, "%d:%02d", total / 60, total % 60)
    }

    /** 步频（spm → 整数字符串）。 */
    fun cadence(cadenceSpm: Double): String =
        String.format(Locale.US, "%.0f", cadenceSpm.coerceAtLeast(0.0))
}
