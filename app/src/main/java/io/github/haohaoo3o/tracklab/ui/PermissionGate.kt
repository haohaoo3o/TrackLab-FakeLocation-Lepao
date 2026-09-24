package io.github.haohaoo3o.tracklab.ui

/**
 * 权限门状态机（docs/CONTRACTS.md）——由**注入的授权集合（Set<String>）+ sdkInt**
 * 驱动（纯 Kotlin，无 Mockito/Robolectric 可全迁移测试）。
 *
 * 三态与评估次序（缺权限优先）：
 * - [State.MISSING_PERMISSIONS]：`RequiredPermissions.for(sdkInt)` 中存在未授权项 →
 *   引导 UI `permission_guide_message`；
 * - [State.MISSING_MOCK_SELECTION]：运行时权限齐备但未在『开发者选项 → 选择模拟位置信息应用』
 *   选中本应用 → 引导 UI `mock_location_guide`；
 * - [State.READY]：两者齐备。
 *
 * 迁移输入：[onPermissionsChanged]（授权集合变化）与 [onMockSelectionChanged]（官方测试提供者
 * 试探结果注入——公开 API 无查询接口，由 `TestLocationSink` SecurityException 试探结果驱动）。
 * 未授权点『开始』给引导 UI（[guidanceMessageKey]），不做任何绕过。
 */
class PermissionGate(
    initialGranted: Set<String>,
    private val sdkInt: Int,
    initialMockSelected: Boolean = false,
) {

    /** 权限门三态。 */
    enum class State { MISSING_PERMISSIONS, MISSING_MOCK_SELECTION, READY }

    /** 当前授权集合（注入面）。 */
    var granted: Set<String> = initialGranted
        private set

    /** 是否已被选为模拟位置信息应用（注入面：官方测试提供者试探结果）。 */
    var mockSelected: Boolean = initialMockSelected
        private set

    /** 当前三态。 */
    var state: State = evaluate()
        private set

    /** 缺失的前置权限（`RequiredPermissions.for(sdkInt)` − 已授权集合；顺序同 [RequiredPermissions]）。 */
    val missingPermissions: List<String>
        get() = RequiredPermissions.`for`(sdkInt).filterNot { it in granted }

    /**
     * 授权集合变化（系统权限对话框回调）→ 重评状态，返回新状态（状态机迁移）。
     */
    fun onPermissionsChanged(granted: Set<String>): State {
        this.granted = granted
        state = evaluate()
        return state
    }

    /**
     * 模拟位置应用选择结果变化（官方测试提供者试探注入）→ 重评状态，返回新状态。
     */
    fun onMockSelectionChanged(selected: Boolean): State {
        mockSelected = selected
        state = evaluate()
        return state
    }

    /**
     * 引导文案键（引导 UI）：MISSING_PERMISSIONS → `permission_guide_message`、
     * MISSING_MOCK_SELECTION → `mock_location_guide`、READY → null（无引导）。
     */
    fun guidanceMessageKey(): String? = when (state) {
        State.MISSING_PERMISSIONS -> "permission_guide_message"
        State.MISSING_MOCK_SELECTION -> "mock_location_guide"
        State.READY -> null
    }

    private fun evaluate(): State = when {
        missingPermissions.isNotEmpty() -> State.MISSING_PERMISSIONS
        !mockSelected -> State.MISSING_MOCK_SELECTION
        else -> State.READY
    }
}
