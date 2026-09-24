package io.github.haohaoo3o.tracklab.ui

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * 权限协调器（Android 侧适配，docs/CONTRACTS.md）：把系统授权状态采集成
 * [PermissionGate] 需要的注入集合，并发起运行时申请。
 *
 * 纯逻辑归 [PermissionGate]（可测接缝）；本类只做系统查询/发起申请的薄适配。
 * 注意：本类不读 `Build.VERSION.SDK_INT` 之外的版本分支（[currentSdkInt] 单点采集后注入 Gate）。
 */
class PermissionCoordinator(private val context: Context) {

    /** 当前设备 SDK 级别（唯一采集点，注入 [PermissionGate]）。 */
    fun currentSdkInt(): Int = Build.VERSION.SDK_INT

    /** 采集当前已授权集合（逐项 ContextCompat.checkSelfPermission）。 */
    fun grantedPermissions(): Set<String> =
        RequiredPermissions.`for`(currentSdkInt())
            .filter { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
            .toSet()

    /** 就地构造权限门（授权集合 + sdkInt 注入）。*/
    fun createGate(mockSelected: Boolean = false): PermissionGate = PermissionGate(
        initialGranted = grantedPermissions(),
        sdkInt = currentSdkInt(),
        initialMockSelected = mockSelected,
    )
}
