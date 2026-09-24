package io.github.haohaoo3o.tracklab.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 断言契约见 docs/CONTRACTS.md §6（RequiredPermissions.for(sdkInt) 纯函数：
 * API 33+ 含 POST_NOTIFICATIONS、API 34+ 含 FOREGROUND_SERVICE_LOCATION 前置分支断言）。
 */
class RequiredPermissionsTest {

    @Test
    fun baselineHasLocationPairOnly() {
        for (sdk in listOf(26, 28, 31, 32)) {
            val permissions = RequiredPermissions.`for`(sdk)
            assertEquals("API $sdk 基线 = 定位两项", listOf(
                RequiredPermissions.ACCESS_FINE_LOCATION,
                RequiredPermissions.ACCESS_COARSE_LOCATION,
            ), permissions)
        }
    }

    @Test
    fun api33AddsPostNotifications() {
        for (sdk in listOf(33, 34, 35)) {
            val permissions = RequiredPermissions.`for`(sdk)
            assertTrue("API $sdk 必须含 POST_NOTIFICATIONS", RequiredPermissions.POST_NOTIFICATIONS in permissions)
        }
        assertFalse(
            "API 32 不得含 POST_NOTIFICATIONS",
            RequiredPermissions.POST_NOTIFICATIONS in RequiredPermissions.`for`(32),
        )
        // 顺序钉死：POST_NOTIFICATIONS 排在定位两项之后
        assertEquals(
            listOf(
                RequiredPermissions.ACCESS_FINE_LOCATION,
                RequiredPermissions.ACCESS_COARSE_LOCATION,
                RequiredPermissions.POST_NOTIFICATIONS,
            ),
            RequiredPermissions.`for`(33),
        )
    }

    @Test
    fun api34AddsForegroundServiceLocation() {
        for (sdk in listOf(34, 35)) {
            val permissions = RequiredPermissions.`for`(sdk)
            assertTrue("API $sdk 必须含 FOREGROUND_SERVICE_LOCATION", RequiredPermissions.FOREGROUND_SERVICE_LOCATION in permissions)
            assertEquals(4, permissions.size)
        }
        assertFalse(
            "API 33 不得含 FOREGROUND_SERVICE_LOCATION",
            RequiredPermissions.FOREGROUND_SERVICE_LOCATION in RequiredPermissions.`for`(33),
        )
    }

    @Test
    fun pureFunctionSameInputSameOutput() {
        assertEquals(RequiredPermissions.`for`(34), RequiredPermissions.`for`(34))
        assertEquals(RequiredPermissions.`for`(33), RequiredPermissions.`for`(33))
        // 全部条目为 android.permission.* 前缀字符串（不 import android.*）
        for (p in RequiredPermissions.`for`(35)) {
            assertTrue("权限常量应为 android.permission.*：$p", p.startsWith("android.permission."))
        }
    }
}
