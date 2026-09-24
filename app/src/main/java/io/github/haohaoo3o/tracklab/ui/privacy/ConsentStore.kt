package io.github.haohaoo3o.tracklab.ui.privacy

/**
 * 隐私同意存档端口（docs/CONTRACTS.md §5）。
 *
 * 存储契约（API 形状与命名固定）：
 * - 文件：SharedPreferences "tracklab_state"（MODE_PRIVATE）
 * - 键：布尔 "consent_agreed"
 * - API：[isAgreed] / [setAgreed]（就两个方法，PrivacyGateController 依赖此形状）
 *
 * SmokeTest 经 targetContext 写同文件同键预置同意态，并断言隐私对话框不再显示。
 */
interface ConsentStore {

    /** 是否已同意隐私政策（默认 false）。 */
    fun isAgreed(): Boolean

    /** 写入同意态（用户点击『同意并继续』后调用；此后合规时序才允许 updatePrivacyShow/Agree）。 */
    fun setAgreed(agreed: Boolean)
}
