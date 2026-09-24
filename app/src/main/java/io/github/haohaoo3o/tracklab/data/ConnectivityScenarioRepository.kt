package io.github.haohaoo3o.tracklab.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 连接状态场景库（docs/CONTRACTS.md §9）——**仅供本应用 UI/业务测试**选择连接场景：
 * Cellular / Wi-Fi / Offline 三态 + 默认态 DEFAULT。
 *
 * 边界：本类只影响**本应用内**的 UI/业务分支取值；**不改写系统连接状态、
 * 不伪装系统 Telephony/蜂窝状态、不影响其他应用**。蜂窝模拟仅限 DI 层（AppContainer 挂接）。
 * 默认态 DEFAULT = 不模拟（应用内逻辑回落到真实系统连接状态，本类不读不写系统 API）。
 */
class ConnectivityScenarioRepository {

    /** 可选场景：三态（CELLULAR/WIFI/OFFLINE）+ 默认态（DEFAULT）。 */
    enum class Scenario { DEFAULT, CELLULAR, WIFI, OFFLINE }

    private val mutableScenario = MutableStateFlow(Scenario.DEFAULT)

    /** 当前场景流（初始默认态）。 */
    val scenario: StateFlow<Scenario> = mutableScenario.asStateFlow()

    /** 当前场景值。 */
    fun current(): Scenario = mutableScenario.value

    /** 选择场景（三态或显式回到默认态）。 */
    fun select(scenario: Scenario) {
        mutableScenario.value = scenario
    }

    /** 回到默认态（不模拟）。 */
    fun reset() {
        mutableScenario.value = Scenario.DEFAULT
    }

    /** 是否处于模拟态（非 DEFAULT）。 */
    fun isSimulated(): Boolean = current() != Scenario.DEFAULT

    /**
     * 模拟离线与否：DEFAULT → null（未模拟，调用方走真实系统状态）；
     * OFFLINE → true；CELLULAR / WIFI → false。仅供本应用 UI/业务分支。
     */
    fun simulatedOffline(): Boolean? = when (current()) {
        Scenario.DEFAULT -> null
        Scenario.OFFLINE -> true
        Scenario.CELLULAR, Scenario.WIFI -> false
    }
}
