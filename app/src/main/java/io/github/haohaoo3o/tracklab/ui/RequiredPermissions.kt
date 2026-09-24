package io.github.haohaoo3o.tracklab.ui

/**
 * 开始测试回放的前置运行时权限清单（docs/CONTRACTS.md）——**纯函数**：
 * 入参 [for] 的 sdkInt 由调用方注入，**内部不读 `Build.VERSION.SDK_INT`**（JVM 下恒 0 会误导分支）。
 *
 * 分支（前置分支断言按 sdkInt 参数化）：
 * - 基线（全部 API）：ACCESS_FINE_LOCATION + ACCESS_COARSE_LOCATION（双出口定位基础）；
 * - **API 33+（Android 13）**：+ POST_NOTIFICATIONS（通知运行时申请）；
 * - **API 34+（Android 14）**：+ FOREGROUND_SERVICE_LOCATION（location 型 FGS 前置）。
 *
 * 说明：ACCESS_MOCK_LOCATION（开发者选项人为授权）与 SYSTEM_ALERT_WINDOW（悬浮窗手工授予）
 * 不走运行时申请，不在本清单——见 docs/permissions-android13-miui.md『人为前置』。
 * 常量为字符串字面量（不 import android.*，纯 JVM 可引用——与 ServiceContracts 同风格）。
 */
object RequiredPermissions {

    const val ACCESS_FINE_LOCATION = "android.permission.ACCESS_FINE_LOCATION"
    const val ACCESS_COARSE_LOCATION = "android.permission.ACCESS_COARSE_LOCATION"
    const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
    const val FOREGROUND_SERVICE_LOCATION = "android.permission.FOREGROUND_SERVICE_LOCATION"

    /** Android 13（API 33）：POST_NOTIFICATIONS 运行时申请起点。 */
    const val SDK_POST_NOTIFICATIONS = 33

    /** Android 14（API 34）：FOREGROUND_SERVICE_LOCATION 前置起点。 */
    const val SDK_FOREGROUND_SERVICE_LOCATION = 34

    /**
     * 按 SDK 级别返回前置权限清单（**纯函数**：同输入同输出；顺序固定）。
     * 函数名 `for` 与契约逐字一致（Kotlin 关键字需反引号调用）。
     */
    fun `for`(sdkInt: Int): List<String> {
        val permissions = mutableListOf(ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION)
        if (sdkInt >= SDK_POST_NOTIFICATIONS) permissions.add(POST_NOTIFICATIONS)
        if (sdkInt >= SDK_FOREGROUND_SERVICE_LOCATION) permissions.add(FOREGROUND_SERVICE_LOCATION)
        return permissions
    }
}
