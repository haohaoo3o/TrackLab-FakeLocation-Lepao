package io.github.haohaoo3o.tracklab.map

import io.github.haohaoo3o.tracklab.ui.map.KeyProvider
import io.github.haohaoo3o.tracklab.ui.map.MetaDataKeyProvider
import io.github.haohaoo3o.tracklab.ui.privacy.AMapPrivacyPort
import io.github.haohaoo3o.tracklab.ui.privacy.ConsentStore
import io.github.haohaoo3o.tracklab.ui.privacy.PrivacyGateController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 断言契约（三态 NEEDS_CONSENT→NEEDS_KEY→READY；
 * updatePrivacyShow(true,true)+updatePrivacyAgree(true) 的合规时序；ConsentStore API 形状）
 * 合规调用必须位于用户同意之后、MapView 创建之前。
 * 纯 JVM 直测：ConsentStore / AMapPrivacyPort / KeyProvider 全部为假件。
 */
class PrivacyGateTest {

    /** ConsentStore API 形状假件（isAgreed/setAgreed 两方法）。*/
    private class FakeConsentStore(initial: Boolean = false) : ConsentStore {
        var agreedValue: Boolean = initial
        var writeCount = 0
        override fun isAgreed(): Boolean = agreedValue
        override fun setAgreed(agreed: Boolean) {
            agreedValue = agreed
            writeCount++
        }
    }

    /** 合规时序记录器：记录调用次序，供『同意之后、MapView 创建之前』断言。 */
    private class RecordingPrivacy : AMapPrivacyPort {
        val events = mutableListOf<String>()
        val confirmCount: Int get() = events.count { it == EVENT_CONFIRM }
        override fun confirmPrivacyShowAndAgree() {
            events += EVENT_CONFIRM
        }

        companion object {
            const val EVENT_CONFIRM = "confirm"
            const val EVENT_MAP_CREATED = "map_created"
        }
    }

    private fun keyProviderOf(key: String?): KeyProvider = MetaDataKeyProvider { key }

    private fun gate(
        consent: FakeConsentStore = FakeConsentStore(),
        key: String? = "test-key",
        privacy: RecordingPrivacy = RecordingPrivacy(),
    ): PrivacyGateController = PrivacyGateController(consent, keyProviderOf(key), privacy)

    // ---------------------------------------------------------------- 三态

    @Test
    fun freshInstallIsNeedsConsentAndNeverTouchesSdk() {
        val privacy = RecordingPrivacy()
        val g = gate(consent = FakeConsentStore(), privacy = privacy)
        assertEquals(PrivacyGateController.State.NEEDS_CONSENT, g.state())
        assertTrue(g.shouldShowConsentDialog())
        assertEquals(0, privacy.confirmCount)
    }

    @Test
    fun denyKeepsNeedsConsentWithoutPersistingAgree() {
        val consent = FakeConsentStore()
        val privacy = RecordingPrivacy()
        val g = gate(consent = consent, privacy = privacy)
        g.onDeny()
        assertEquals(PrivacyGateController.State.NEEDS_CONSENT, g.state())
        assertFalse(consent.isAgreed())
        assertEquals(0, consent.writeCount)
        assertEquals(0, privacy.confirmCount)
    }

    @Test
    fun agreeWithoutKeyGoesToNeedsKeyWithHint() {
        val consent = FakeConsentStore()
        val g = gate(consent = consent, key = null)
        g.onAgree()
        assertTrue("同意态须落盘", consent.isAgreed())
        assertEquals(PrivacyGateController.State.NEEDS_KEY, g.state())
        assertFalse(g.shouldShowConsentDialog())
        assertTrue("NEEDS_KEY 提示", g.shouldShowKeyMissingHint())
    }

    @Test
    fun agreeWithKeyGoesToReadyAndHidesHint() {
        val g = gate(key = "test-key")
        g.onAgree()
        assertEquals(PrivacyGateController.State.READY, g.state())
        assertFalse(g.shouldShowConsentDialog())
        assertFalse(g.shouldShowKeyMissingHint())
    }

    @Test
    fun blankKeyTreatedAsMissing() {
        for (blank in listOf("", "   ")) {
            val g = gate(consent = FakeConsentStore(initial = true), key = blank)
            assertEquals("空白 Key『$blank』应视为缺失", PrivacyGateController.State.NEEDS_KEY, g.state())
            assertTrue(g.shouldShowKeyMissingHint())
        }
    }

    @Test
    fun persistedConsentSkipsDialogOnRelaunch() {
        val g = gate(consent = FakeConsentStore(initial = true), key = "test-key")
        assertEquals(PrivacyGateController.State.READY, g.state())
        assertFalse(g.shouldShowConsentDialog())
    }

    // ---------------------------------------------------------------- 合规时序

    @Test
    fun beforeMapViewCreatedBlockedWithoutConsentAndWithoutSdkCalls() {
        val privacy = RecordingPrivacy()
        val g = gate(consent = FakeConsentStore(initial = false), key = "test-key", privacy = privacy)
        assertFalse(g.beforeMapViewCreated())
        assertEquals("未同意不得触碰 SDK", 0, privacy.confirmCount)
    }

    @Test
    fun beforeMapViewCreatedBlockedWithoutKeyAndWithoutSdkCalls() {
        val privacy = RecordingPrivacy()
        val g = gate(consent = FakeConsentStore(initial = true), key = null, privacy = privacy)
        assertFalse("NEEDS_KEY 不创建 MapView", g.beforeMapViewCreated())
        assertEquals(0, privacy.confirmCount)
    }

    @Test
    fun beforeMapViewCreatedConfirmsPrivacyAfterConsentAndBeforeMapCreation() {
        val privacy = RecordingPrivacy()
        val g = gate(consent = FakeConsentStore(initial = true), key = "test-key", privacy = privacy)

        val allowed = g.beforeMapViewCreated()
        // 方法返回时合规调用已完成 ⇒ 必然『同意之后（isAgreed=true 已写）』且早于 MapView 创建
        assertTrue(allowed)
        assertEquals(listOf(RecordingPrivacy.EVENT_CONFIRM), privacy.events)

        // 模拟随后创建 MapView：合规事件严格位于其之前（时序）
        privacy.events += RecordingPrivacy.EVENT_MAP_CREATED
        assertEquals(
            listOf(RecordingPrivacy.EVENT_CONFIRM, RecordingPrivacy.EVENT_MAP_CREATED),
            privacy.events,
        )
    }

    @Test
    fun stateAndDialogHelpersNeverTouchSdk() {
        val privacy = RecordingPrivacy()
        val g = gate(consent = FakeConsentStore(initial = true), key = "test-key", privacy = privacy)
        g.state()
        g.shouldShowConsentDialog()
        g.shouldShowKeyMissingHint()
        g.onAgree()
        g.onDeny()
        assertEquals("状态读取与对话框显隐均不得触碰 SDK", 0, privacy.confirmCount)
    }

    // ---------------------------------------------------------------- ConsentStore API 形状

    @Test
    fun consentStoreApiShapeIsAgreedSetAgreed() {
        val store: ConsentStore = FakeConsentStore()
        assertFalse(store.isAgreed())
        store.setAgreed(true)
        assertTrue(store.isAgreed())
        store.setAgreed(false)
        assertFalse(store.isAgreed())
    }
}
