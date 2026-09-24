package io.github.haohaoo3o.tracklab

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.Visibility
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.haohaoo3o.tracklab.service.ServiceContracts
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.InputStream

/**
 * 冒烟测试（断言见下方各用例；覆盖范围见 docs/CONTRACTS.md §10）。
 *
 * - 前置：launch 前经 targetContext 写 SharedPreferences("tracklab_state") 键 consent_agreed=true；
 * - ① 启动成功；② 隐私对话框不再显示（privacy_dialog_root）；③ test_mode_badge 可见；
 * - ④ btn_start/btn_pause/btn_stop isDisplayed+isEnabled+click 无异常；
 * - ⑤ NEEDS_KEY 提示（txt_key_missing）可见（无 Key）；
 * - ⑥ getString(R.string.notification_title) 含“测试”。
 * 排除：悬浮窗、在线底图、通知可见性（不在冒烟覆盖范围）。
 *
 * **启动通道（如实记录）**：经 UiAutomation shell `am start -W -n io.github.haohaoo3o.tracklab/.MainActivity`
 * ——与真机人工验收的 `adb shell am start` 同通道。改用 shell 通道的原因：测试真机为
 * MIUI（API 33），ActivityScenario/instrumentation 直启 Activity 被 MIUI
 * 启动管控拦截并**永久挂起**（logcat 实测：
 * `ActivityStarterImpl: MIUILOG- Permission Denied Activity : … cmp=io.github.haohaoo3o.tracklab/.MainActivity`）。
 * 断言①–⑥ 与 launch 前置均不变；『排除』项不变。
 */
@RunWith(AndroidJUnit4::class)
class SmokeTest {

    @Before
    fun presetConsent() {
        // 前置：targetContext 预置 consent_agreed=true（文件/键同 ServiceContracts）
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        target.getSharedPreferences(ServiceContracts.PREFS_UI, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(ServiceContracts.KEY_CONSENT, true)
            .commit()
    }

    @After
    fun bringBackToLauncher() {
        // 收尾（**不 force-stop**——instrumentation 与被测应用同进程，force-stop 会连带杀死
        // 测试 runner，JUnit 报『Process crashed』且丢弃断言结果（实测 logcat：
        // ActivityTaskManager: Force removing … MainActivity …: app died）。
        // 按 HOME 回桌面即可；随后 connectedDebugAndroidTest 的收尾钩子自动卸载本包。
        shell("input keyevent KEYCODE_HOME")
    }

    @Test
    fun smokeAssertions() {
        // ① 启动成功（shell am start -W 通道，见类 KDoc；Status: ok = 系统确认启动完成）
        val launch = shell("am start -W -n io.github.haohaoo3o.tracklab/.MainActivity")
        assertTrue("① 启动成功（am start -W 应返回 Status: ok）：$launch", launch.contains("Status: ok"))

        // ② 隐私对话框不再显示（consent 已同意 → GONE）
        onView(withId(R.id.privacy_dialog_root))
            .check(matches(withEffectiveVisibility(Visibility.GONE)))

        // ③ 常驻测试徽标可见
        onView(withId(R.id.test_mode_badge)).check(matches(isDisplayed()))

        // ④ 主控三键：isDisplayed + isEnabled + click 无异常
        for (id in listOf(R.id.btn_start, R.id.btn_pause, R.id.btn_stop)) {
            onView(withId(id)).check(matches(isDisplayed()))
            onView(withId(id)).check(matches(isEnabled()))
            onView(withId(id)).perform(click())
        }

        // ⑤ NEEDS_KEY 提示可见（测试构建无 AMAP Key）
        onView(withId(R.id.txt_key_missing)).check(matches(isDisplayed()))

        // ⑥ 合规文案含“测试”（locale 无关）
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val title = target.getString(R.string.notification_title)
        assertTrue("⑥ notification_title 须含“测试”字样：$title", title.contains("测试"))
    }

    /** shell 命令（UiAutomation，shell uid 通道）；阻塞读至命令完成并返回输出。
     *  PFD 经 AutoCloseInputStream 包装（ParcelFileDescriptor 无 getInputStream 公共 API；关闭流=关闭 PFD）。 */
    private fun shell(command: String): String {
        val pfd: ParcelFileDescriptor =
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        val input: InputStream = ParcelFileDescriptor.AutoCloseInputStream(pfd)
        val reader: BufferedReader = input.bufferedReader(Charsets.UTF_8)
        return reader.use { r -> r.readText() }
    }
}
