package io.github.haohaoo3o.tracklab.ui.privacy

import android.content.Context
import io.github.haohaoo3o.tracklab.service.ServiceContracts

/**
 * [ConsentStore] 的 SharedPreferences 实现（docs/CONTRACTS.md §5）。
 * 文件 [ServiceContracts.PREFS_UI]（"tracklab_state"）、键 [ServiceContracts.KEY_CONSENT]
 * （"consent_agreed"）、MODE_PRIVATE——与快照 "playback_state" 同风格。
 * SmokeTest 预置与断言使用同一文件+键。
 */
class SharedPreferencesConsentStore(context: Context) : ConsentStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(ServiceContracts.PREFS_UI, Context.MODE_PRIVATE)

    override fun isAgreed(): Boolean =
        prefs.getBoolean(ServiceContracts.KEY_CONSENT, false)

    override fun setAgreed(agreed: Boolean) {
        prefs.edit().putBoolean(ServiceContracts.KEY_CONSENT, agreed).apply()
    }
}
