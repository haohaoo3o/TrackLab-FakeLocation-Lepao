package io.github.haohaoo3o.tracklab

import android.app.Application
import io.github.haohaoo3o.tracklab.di.AppContainer

/**
 * 进程入口（Manifest 显式声明 `android:name=".TrackLabApp"`）。
 *
 * 高德合规时序（docs/CONTRACTS.md）：`MapsInitializer.updatePrivacyShow(context, true, true)`
 * 与 `updatePrivacyAgree(context, true)` 必须在用户同意隐私政策之后、MapView 创建之前调用，
 * 属地图模块职责，本类不得提前调用。
 */
class TrackLabApp : Application() {

    /** 组合根（手工 DI）。排除项与蜂窝模拟仅限 DI 层（docs/CONTRACTS.md §9）。*/
    lateinit var appContainer: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        appContainer = AppContainer(this)
    }
}
