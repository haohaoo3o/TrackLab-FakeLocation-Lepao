package io.github.haohaoo3o.tracklab.ui.privacy

import io.github.haohaoo3o.tracklab.ui.map.KeyProvider

/**
 * 高德合规时序端口（docs/CONTRACTS.md）。生产实现 `MapsInitializerPrivacyGate`
 * 调用 `MapsInitializer.updatePrivacyShow(context, true, true)` + `updatePrivacyAgree(context, true)`；
 * 测试用假件记录调用（本接口与 [PrivacyGateController] 零 Android 依赖，纯 JVM 可执行）。
 */
fun interface AMapPrivacyPort {

    /** 合规时序：声明已展示隐私政策（true, true）且用户已同意（true）。 */
    fun confirmPrivacyShowAndAgree()
}

/**
 * 隐私三态门（docs/CONTRACTS.md §5）：
 * `NEEDS_CONSENT →（同意→ 合规时序）→ NEEDS_KEY → READY`。
 *
 * - `NEEDS_CONSENT`：未同意隐私政策——显示隐私对话框（`privacy_dialog_root`），**不触碰地图 SDK**；
 * - `NEEDS_KEY`：已同意但无注入 Key——显示 `error_amap_key_missing` 配置提示，不创建 MapView
 *   （未配置 Key 时给明确诊断而不是崩溃）；
 * - `READY`：已同意且有 Key——允许创建 MapView。
 *
 * **合规时序钉死**：`updatePrivacyShow/Agree` 在用户同意之后、MapView 创建之前调用——
 * 唯一触点是 [beforeMapViewCreated] 返回 true 的那次调用（先合规后放行，方法返回时合规已完成；
 * [state]/[onAgree]/[onDeny] 均不触碰 SDK）。
 */
class PrivacyGateController(
    private val consentStore: ConsentStore,
    private val keyProvider: KeyProvider,
    private val amapPrivacy: AMapPrivacyPort,
) {

    enum class State { NEEDS_CONSENT, NEEDS_KEY, READY }

    /** 当前三态（纯派生，不触碰 SDK）。 */
    fun state(): State = when {
        !consentStore.isAgreed() -> State.NEEDS_CONSENT
        !keyProvider.hasKey() -> State.NEEDS_KEY
        else -> State.READY
    }

    /** 用户点击『同意并继续』：仅落盘同意态；合规时序延后到 MapView 创建前（[beforeMapViewCreated]）。 */
    fun onAgree() {
        consentStore.setAgreed(true)
    }

    /** 用户点击『不同意』：保持 NEEDS_CONSENT（不写同意态、不触碰 SDK）。 */
    fun onDeny() {
        // 有意留空：状态保持 NEEDS_CONSENT。
    }

    /** 是否显示隐私对话框区块（`privacy_dialog_root`）。 */
    fun shouldShowConsentDialog(): Boolean = state() == State.NEEDS_CONSENT

    /** 是否显示 NEEDS_KEY 配置提示（`txt_key_missing`——PrivacyGateController 驱动显隐）。*/
    fun shouldShowKeyMissingHint(): Boolean = !keyProvider.hasKey()

    /**
     * MapView 创建前的唯一闸门（合规时序落点）：
     * - 未同意 → false，且【不】触碰 SDK；
     * - 已同意但无 Key（NEEDS_KEY）→ false，不创建 MapView（明确诊断由 `txt_key_missing` 承担）；
     * - READY → 先 [AMapPrivacyPort.confirmPrivacyShowAndAgree]（同意之后、MapView 创建之前），再返回 true。
     */
    fun beforeMapViewCreated(): Boolean {
        if (!consentStore.isAgreed()) return false
        if (state() != State.READY) return false
        amapPrivacy.confirmPrivacyShowAndAgree()
        return true
    }
}
