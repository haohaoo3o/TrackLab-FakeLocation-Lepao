package io.github.haohaoo3o.tracklab.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 断言契约见 docs/CONTRACTS.md §6（权限门状态机全迁移；由注入的授权集合+sdkInt 驱动（纯 JVM）；
 * 未授权点『开始』给引导 UI）。
 */
class PermissionGateTest {

    private fun grantedAll(sdkInt: Int): Set<String> = RequiredPermissions.`for`(sdkInt).toSet()

    // ---------------------------------------------------------------- 授权集合 + sdkInt 驱动（纯 JVM，无 Build.VERSION 依赖）

    @Test
    fun missingPermissionsDrivenByInjectedGrantedSet() {
        val gate = PermissionGate(initialGranted = emptySet(), sdkInt = 33)
        assertEquals(PermissionGate.State.MISSING_PERMISSIONS, gate.state)
        assertEquals(RequiredPermissions.`for`(33), gate.missingPermissions)

        val partial = PermissionGate(
            initialGranted = setOf(RequiredPermissions.ACCESS_FINE_LOCATION),
            sdkInt = 33,
        )
        assertEquals(
            listOf(RequiredPermissions.ACCESS_COARSE_LOCATION, RequiredPermissions.POST_NOTIFICATIONS),
            partial.missingPermissions,
        )
    }

    @Test
    fun sdkBranchesParameterized() {
        // 32：无 POST_NOTIFICATIONS
        val g32 = PermissionGate(initialGranted = grantedAll(32), sdkInt = 32)
        assertEquals(PermissionGate.State.MISSING_MOCK_SELECTION, g32.state) // 权限齐、未选模拟应用
        assertTrue(g32.missingPermissions.isEmpty())

        // 33：POST_NOTIFICATIONS 必须在缺失面出现
        val g33 = PermissionGate(initialGranted = grantedAll(33) - RequiredPermissions.POST_NOTIFICATIONS, sdkInt = 33)
        assertEquals(listOf(RequiredPermissions.POST_NOTIFICATIONS), g33.missingPermissions)

        // 34：FOREGROUND_SERVICE_LOCATION 必须在缺失面出现
        val g34 = PermissionGate(
            initialGranted = grantedAll(34) - RequiredPermissions.FOREGROUND_SERVICE_LOCATION,
            sdkInt = 34,
        )
        assertEquals(listOf(RequiredPermissions.FOREGROUND_SERVICE_LOCATION), g34.missingPermissions)
    }

    // ---------------------------------------------------------------- 状态机全迁移（3 态 × 各输入变化）

    @Test
    fun allTransitionsCovered() {
        // MISSING_PERMISSIONS → MISSING_MOCK_SELECTION（补齐权限）
        val t1 = PermissionGate(initialGranted = emptySet(), sdkInt = 33)
        assertEquals(PermissionGate.State.MISSING_PERMISSIONS, t1.state)
        assertEquals(PermissionGate.State.MISSING_MOCK_SELECTION, t1.onPermissionsChanged(grantedAll(33)))

        // MISSING_PERMISSIONS → READY（补齐权限 + 选中模拟应用）
        val t2 = PermissionGate(initialGranted = emptySet(), sdkInt = 33, initialMockSelected = true)
        assertEquals(PermissionGate.State.READY, t2.onPermissionsChanged(grantedAll(33)))

        // MISSING_MOCK_SELECTION → READY（选中模拟应用）
        val t3 = PermissionGate(initialGranted = grantedAll(33), sdkInt = 33)
        assertEquals(PermissionGate.State.MISSING_MOCK_SELECTION, t3.state)
        assertEquals(PermissionGate.State.READY, t3.onMockSelectionChanged(true))

        // MISSING_MOCK_SELECTION → MISSING_PERMISSIONS（撤销权限）
        val t4 = PermissionGate(initialGranted = grantedAll(33), sdkInt = 33, initialMockSelected = true)
        assertEquals(PermissionGate.State.READY, t4.state)
        assertEquals(PermissionGate.State.MISSING_PERMISSIONS, t4.onPermissionsChanged(emptySet()))

        // READY → MISSING_MOCK_SELECTION（取消选中模拟应用）
        val t5 = PermissionGate(initialGranted = grantedAll(33), sdkInt = 33, initialMockSelected = true)
        assertEquals(PermissionGate.State.READY, t5.state)
        assertEquals(PermissionGate.State.MISSING_MOCK_SELECTION, t5.onMockSelectionChanged(false))

        // READY → MISSING_PERMISSIONS（撤销权限，mock 已选中）
        val t6 = PermissionGate(initialGranted = grantedAll(33), sdkInt = 33, initialMockSelected = true)
        assertEquals(
            PermissionGate.State.MISSING_PERMISSIONS,
            t6.onPermissionsChanged(grantedAll(33) - RequiredPermissions.POST_NOTIFICATIONS),
        )

        // 自迁移（同态输入变化保持态）
        val t7 = PermissionGate(initialGranted = grantedAll(33), sdkInt = 33)
        assertEquals(PermissionGate.State.MISSING_MOCK_SELECTION, t7.onMockSelectionChanged(false))
        val t8 = PermissionGate(initialGranted = grantedAll(33), sdkInt = 33, initialMockSelected = true)
        assertEquals(PermissionGate.State.READY, t8.onMockSelectionChanged(true))
    }

    // ---------------------------------------------------------------- 引导 UI 文案键（permission_guide_message / mock_location_guide）

    @Test
    fun guidanceMessageKeys() {
        val missing = PermissionGate(initialGranted = emptySet(), sdkInt = 33)
        assertEquals("permission_guide_message", missing.guidanceMessageKey())

        val mock = PermissionGate(initialGranted = grantedAll(33), sdkInt = 33)
        assertEquals("mock_location_guide", mock.guidanceMessageKey())

        val ready = PermissionGate(initialGranted = grantedAll(33), sdkInt = 33, initialMockSelected = true)
        assertNull(ready.guidanceMessageKey())
    }

    @Test
    fun mockSelectionInjectedFromTestProviderProbe() {
        // 官方测试提供者试探结果注入（SecurityException → false；放行 → true）
        val gate = PermissionGate(initialGranted = grantedAll(34), sdkInt = 34)
        assertEquals(PermissionGate.State.MISSING_MOCK_SELECTION, gate.onMockSelectionChanged(false))
        assertEquals(PermissionGate.State.READY, gate.onMockSelectionChanged(true))
        assertTrue(gate.mockSelected)
    }
}
