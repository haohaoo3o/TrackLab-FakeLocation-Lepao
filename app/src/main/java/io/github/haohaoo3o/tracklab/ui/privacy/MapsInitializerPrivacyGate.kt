package io.github.haohaoo3o.tracklab.ui.privacy

import android.content.Context
import com.amap.api.maps.MapsInitializer

/**
 * [AMapPrivacyPort] 的高德 SDK 实现（docs/CONTRACTS.md 合规时序，lbs.amap.com dev-attention 已核验）：
 * 用户同意隐私政策**之后**、MapView 创建**之前**调用
 * `MapsInitializer.updatePrivacyShow(context, true, true)` + `updatePrivacyAgree(context, true)`。
 * 调用由 [PrivacyGateController.beforeMapViewCreated] 唯一驱动（幂等 setter，可重复调用）。
 */
class MapsInitializerPrivacyGate(
    private val context: Context,
) : AMapPrivacyPort {

    override fun confirmPrivacyShowAndAgree() {
        MapsInitializer.updatePrivacyShow(context, true, true)
        MapsInitializer.updatePrivacyAgree(context, true)
    }
}
