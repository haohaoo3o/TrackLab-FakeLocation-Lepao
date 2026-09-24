package io.github.haohaoo3o.tracklab

import android.os.ParcelFileDescriptor
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.haohaoo3o.tracklab.ui.overlay.FloatingOverlayController
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * 回归测试（真机崩溃路径）：悬浮窗 HUD 的创建/更新/移除从**后台线程**发起必须安全。
 *
 * 修复前（未捕获崩溃，HUD 功能不可用）：`scope.launch`（Dispatchers.Default）内
 * `WindowManager.addView` → ViewRootImpl 挂主线程 Handler → `RuntimeException: Can't create
 * handler inside thread that has not called Looper.prepare()`（非 BadTokenException/IllegalState-
 * Exception，show() 不接）直接崩进程；即使创建成功，后台 setText→requestLayout 亦抛
 * CalledFromWrongThreadException。修复口径：FloatingOverlayController 全部 View/WindowManager
 * 突变经 MainThreadMarshal 封送主线程。
 *
 * 前置：shell 通道（UiAutomation，同 SmokeTest 口径）`appops set … SYSTEM_ALERT_WINDOW allow`
 * 授予悬浮窗权限——未授予则 show() 提前返回 false，addView 缺陷不被覆盖，故此处**硬断言已授予**。
 */
@RunWith(AndroidJUnit4::class)
class FloatingOverlayThreadingTest {

    private lateinit var controller: FloatingOverlayController

    @Before
    fun grantOverlayPermissionAndCreateController() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        shell("appops set ${target.packageName} SYSTEM_ALERT_WINDOW allow")
        val deadline = System.currentTimeMillis() + 5_000
        while (!Settings.canDrawOverlays(target) && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue(
            "须授予 SYSTEM_ALERT_WINDOW（appops set ${target.packageName} SYSTEM_ALERT_WINDOW allow）" +
                "——未授予时 show() 提前返回，无法覆盖 addView 线程缺陷",
            Settings.canDrawOverlays(target),
        )
        controller = FloatingOverlayController(
            target,
            object : FloatingOverlayController.Callbacks {
                override fun onPauseResume() = Unit
                override fun onStop() = Unit
            },
        )
    }

    @After
    fun cleanUp() {
        runCatching { controller.hide() }
    }

    @Test
    fun createUpdateAndHideFromBackgroundThreadMustNotCrash() {
        val failure = AtomicReference<Throwable?>(null)
        val shown = AtomicBoolean(false)
        val showingAfterUpdate = AtomicBoolean(false)

        // 后台线程（等价回放协程所处的 Dispatchers.Default 工作线程）创建 + 更新 HUD。
        val bg = thread(name = "bg-playback") {
            try {
                shown.set(controller.show())
                controller.update("1/2 1.23km 5:30 170spm", false)
                controller.updateMetrics("2/2 2.34km 5:20 172spm")
                controller.setPaused(true)
                showingAfterUpdate.set(controller.isShowing)
            } catch (t: Throwable) {
                failure.set(t)
            }
        }
        bg.join(10_000)
        assertFalse("后台线程疑似死锁（show 同步封送未返回）", bg.isAlive)
        assertNull("后台线程创建/更新 HUD 不得抛异常（修复前：RuntimeException(Looper.prepare)/CalledFromWrongThreadException）", failure.get())
        assertTrue("已授悬浮窗权限时 show() 从后台线程须成功", shown.get())
        assertTrue(showingAfterUpdate.get())

        // 主线程更新与后台更新交错（修复前：两类线程互撞必抛 CalledFromWrongThreadException）。
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            try {
                controller.update("3/4 3.45km 5:10 174spm", true)
                controller.updateMetrics("3/4 3.45km 5:10 174spm")
                controller.setPaused(false)
            } catch (t: Throwable) {
                failure.set(t)
            }
        }
        assertNull("主线程更新 HUD 不得抛异常", failure.get())

        // 后台线程 hide（同步封送）——返回即已移除。
        val cleaner = thread(name = "bg-cleanup") {
            try {
                controller.hide()
            } catch (t: Throwable) {
                failure.set(t)
            }
        }
        cleaner.join(10_000)
        assertFalse("后台线程疑似死锁（hide 同步封送未返回）", cleaner.isAlive)
        assertNull("后台线程 hide 不得抛异常", failure.get())
        assertFalse("hide 返回后 HUD 必须已移除", controller.isShowing)
    }

    /** shell 命令（UiAutomation，shell uid 通道；口径同 SmokeTest）。 */
    private fun shell(command: String): String {
        val pfd: ParcelFileDescriptor =
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        val input: InputStream = ParcelFileDescriptor.AutoCloseInputStream(pfd)
        val reader: BufferedReader = input.bufferedReader(Charsets.UTF_8)
        return reader.use { r -> r.readText() }
    }
}
