package io.github.haohaoo3o.tracklab.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier

/**
 * 测试位置出口回归：官方测试提供者出口必须**同时覆盖 gps 与 network**——若只写 gps，
 * 监听 network 的应用在会话内仍会读到真实的 network last-known、对 network 的注册也收不到
 * mock 帧（A/B 实测证实）。
 *
 * 断言三层：
 * 1. 扇出计划纯函数（JVM 可测）：主提供者（gps）恒附带 network、逻辑名自身为 network 时去重；
 * 2. 反射边界：[LocationManagerTestLocationSink] 仍只实现 §6 的 4 方法端口（无新增公开出口）；
 * 3. 源码边界（静态源码断言，同其他护栏测试做法）：扇出真实接线、每样本恰好取帧一次、
 *    只走官方测试提供者 API（无反射/无隐藏通道/不触碰 mock 标志）、fail-closed
 *    （add 部分失败回收、remove 容错但 SecurityException 原样上抛）。
 */
class LocationManagerTestLocationSinkOutletTest {

    // ---------------------------------------------------------------- 扇出计划（纯函数）

    @Test
    fun outletCoversPrimaryAndNetworkProviders() {
        assertEquals(
            listOf("gps", "network"),
            LocationManagerTestLocationSink.physicalProviderNamesFor(
                LocationManagerTestLocationSink.defaultProviderName(),
            ),
        )
    }

    @Test
    fun outletDoesNotDuplicateNetworkWhenLogicalNameIsNetwork() {
        assertEquals(
            listOf("network"),
            LocationManagerTestLocationSink.physicalProviderNamesFor("network"),
        )
    }

    @Test
    fun outletFanOutIsDeduplicatedAndStableForAnyLogicalName() {
        val names = LocationManagerTestLocationSink.physicalProviderNamesFor("custom")
        assertEquals(listOf("custom", "network"), names)
        assertEquals("物理提供者名不得重复注册", names.toSet().size, names.size)
    }

    @Test
    fun defaultProviderNameStaysGps() {
        assertEquals("gps", LocationManagerTestLocationSink.defaultProviderName())
    }

    // ---------------------------------------------------------------- 反射边界（端口面不扩大）

    @Test
    fun sinkImplementsThePortAndAddsNoPublicMockChannel() {
        assertTrue(
            "Android 侧实现必须实现 §9 端口",
            TestLocationSink::class.java.isAssignableFrom(LocationManagerTestLocationSink::class.java),
        )
        val publicMethods = LocationManagerTestLocationSink::class.java.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) }
            .map { it.name }
            .toSet()
        assertEquals(
            "公开面必须恰为 4 方法端口（无隐藏 mock 通道，§9/§14）",
            setOf("add", "set", "setEnabled", "remove"),
            publicMethods,
        )
    }

    // ---------------------------------------------------------------- 源码边界（扇出真实接线 + fail-closed）

    @Test
    fun everyPortOperationFansOutToTheProviderPlan() {
        val src = sinkSource()
        for (op in listOf("add", "set", "setEnabled", "remove")) {
            val body = functionBody(src, "override fun $op(")
            assertTrue(
                "override fun $op 必须经 physicalProviderNamesFor 扇出（network 覆盖不得旁路）",
                body.contains("physicalProviderNamesFor(name)"),
            )
        }
    }

    @Test
    fun setFrameIsTakenExactlyOncePerSample() {
        val body = functionBody(sinkSource(), "override fun set(")
        assertEquals(
            "每样本恰好取帧一次（合成器按样本记忆；扇出各方写同一帧）",
            1,
            Regex("LocationFrameSynthesizer\\.frameFor\\(").findAll(body).count(),
        )
    }

    @Test
    fun onlyOfficialTestProviderApisAreUsed() {
        val src = sinkSource()
        for (api in listOf("addTestProvider(", "setTestProviderLocation(", "setTestProviderEnabled(", "removeTestProvider(")) {
            assertTrue("只允许官方测试提供者 API：缺 $api", src.contains(api))
        }
        for (hidden in listOf("java.lang.reflect", "XposedBridge", "setIsFromMockProvider", "getDeclaredMethod")) {
            assertTrue("出口不得出现隐藏通道/反射/mock 标志触碰：$hidden", !src.contains(hidden))
        }
    }

    @Test
    fun addCleansUpPartialRegistrationBeforeRethrowing() {
        val body = functionBody(sinkSource(), "override fun add(")
        assertTrue(
            "add 部分失败必须回收已注册项（不留无出口的测试提供者）",
            body.contains("removeTestProvider(provider)") && body.contains("throw t"),
        )
    }

    @Test
    fun removeToleratesPerProviderFailureButRethrowsSecurityException() {
        val body = functionBody(sinkSource(), "override fun remove(")
        assertTrue(
            "remove 对单提供者非权限异常容错（不得阻断其余提供者清理）",
            body.contains("catch (ignored: Throwable)"),
        )
        assertTrue(
            "remove 的 SecurityException 必须原样上抛（出口转显式引导态语义不变）",
            body.contains("firstSecurity?.let { throw it }"),
        )
    }

    /**
     * 交付层边界（2026-09-30 第 3 轮 amap-callback 判定的处置依据，钉成可执行护栏）：
     * 出口**只能**经框架测试提供者交付——wifi 扫描结果 / 基站 / 服务端不是任何 app 级 API 能代填的
     * 事实源（AMap 网络定位所需的 wifi 环境与服务端由物理环境决定），故 device 交付层不得出现
     * wifi/基站通道（无效仿冒，且与 CONTRACTS §14「不影响其他应用/系统状态」冲突）。
     * 任何未来若试图用 wifi 伪造去「修」amap 路径，会在此处变红 → 必须先立新证据与新契约。
     */
    @Test
    fun deliveryLayerHasNoWifiOrCellSpoofingChannel() {
        val dir = deviceSourceDir()
        val sources = dir.listFiles { f -> f.isFile && f.name.endsWith(".kt") } ?: emptyArray()
        assertTrue("device 目录应含交付层源码", sources.isNotEmpty())
        for (forbidden in listOf(
            "WifiManager", "getScanResults", "ScanResult",
            "getAllCellInfo", "getCellLocation", "TelephonyManager",
        )) {
            val hits = sources.filter { it.readText().contains(forbidden) }.map { it.name }
            assertTrue("device 交付层不得出现 wifi/基站通道 $forbidden（命中：$hits）", hits.isEmpty())
        }
    }

    // ---------------------------------------------------------------- helpers（PlaybackForegroundServiceStepMutexTest 先例）

    private fun deviceSourceDir(): File {
        var dir = File(System.getProperty("user.dir") ?: ".").absoluteFile
        for (step in 0 until 6) {
            val candidate = File(dir, "app/src/main/java/io/github/haohaoo3o/tracklab/device")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile ?: break
        }
        error("找不到 device 源码目录（user.dir=${System.getProperty("user.dir")}）")
    }

    private fun sinkSource(): String {
        var dir = File(System.getProperty("user.dir") ?: ".").absoluteFile
        for (step in 0 until 6) {
            val candidate = File(dir, "app/src/main/java")
            if (candidate.isDirectory) {
                return File(
                    dir,
                    "app/src/main/java/io/github/haohaoo3o/tracklab/device/LocationManagerTestLocationSink.kt",
                ).readText()
            }
            dir = dir.parentFile ?: break
        }
        error("找不到 app/src/main/java（user.dir=${System.getProperty("user.dir")}）")
    }

    /** 取 [header] 起到下一个成员声明（`\n    override fun` / `\n    private fun` / `\n    companion object`）之间的文本。 */
    private fun functionBody(src: String, header: String): String {
        val start = src.indexOf(header)
        assertTrue("$header 不存在", start >= 0)
        val markers = listOf("\n    override fun", "\n    private fun", "\n    companion object")
        val end = markers.mapNotNull { m ->
            val idx = src.indexOf(m, start + 1)
            if (idx >= 0) idx else null
        }.minOrNull() ?: src.length
        return src.substring(start, end)
    }
}
