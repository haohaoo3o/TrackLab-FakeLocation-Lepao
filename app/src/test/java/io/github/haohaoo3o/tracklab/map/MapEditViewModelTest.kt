package io.github.haohaoo3o.tracklab.map

import io.github.haohaoo3o.tracklab.core.geo.LatLon
import io.github.haohaoo3o.tracklab.core.geo.LocalTangentPlane
import io.github.haohaoo3o.tracklab.core.geo.TrackPresetLibrary
import io.github.haohaoo3o.tracklab.core.geo.Vec2
import io.github.haohaoo3o.tracklab.core.model.TrackSample
import io.github.haohaoo3o.tracklab.core.motion.BoundaryGuard
import io.github.haohaoo3o.tracklab.core.motion.MotionContracts
import io.github.haohaoo3o.tracklab.core.motion.TrajectoryGenerator
import io.github.haohaoo3o.tracklab.ui.map.MapEditViewModel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 断言契约（MapEditViewModel 构造器注入、输入校验 p_base/laps/seed——
 * interval 项不可配置，Δt 恒 0.5s）：六点固定顺序、预览重采样、
 * 边界走廊、导出保序口径、与服务快照同风格状态快照。
 * 纯 JVM 直测（无 Mockito/Robolectric，构造器注入 TrajectoryGenerator）。
 */
class MapEditViewModelTest {

    private fun newViewModel(): MapEditViewModel = MapEditViewModel(TrajectoryGenerator())

    // ---------------------------------------------------------------- 六点采集 / 撤销 / 重置

    @Test
    fun collectSixPointsInFixedOrder() {
        val vm = newViewModel()
        val pts = idealPoints()
        pts.forEachIndexed { i, p ->
            assertTrue("第 $i 点应被接受", vm.addPoint(p))
            assertEquals(i + 1, vm.state.points.size)
        }
        // 固定顺序 p0..p5 原样保序
        assertEquals(pts, vm.state.points)
    }

    @Test
    fun addPointRejectedAfterSix() {
        val vm = newViewModel()
        idealPoints().forEach { vm.addPoint(it) }
        assertFalse("第 7 点必须被拒绝", vm.addPoint(LatLon(0.0, 0.0)))
        assertEquals(6, vm.state.points.size)
    }

    @Test
    fun undoRemovesLastPointOnly() {
        val vm = newViewModel()
        val pts = idealPoints()
        pts.forEach { vm.addPoint(it) }
        assertTrue(vm.undoPoint())
        assertEquals(pts.take(5), vm.state.points)
    }

    @Test
    fun undoOnEmptyReturnsFalse() {
        val vm = newViewModel()
        assertFalse(vm.undoPoint())
        assertTrue(vm.state.points.isEmpty())
    }

    @Test
    fun resetClearsPointsKeepsParams() {
        val vm = newViewModel()
        idealPoints().forEach { vm.addPoint(it) }
        vm.applyInputs("400", "5", "7")
        vm.resetPoints()
        assertTrue(vm.state.points.isEmpty())
        assertEquals(400, vm.state.pBaseSecPerKm)
        assertEquals(5, vm.state.laps)
        assertEquals(7L, vm.state.seed)
    }

    // ---------------------------------------------------------------- 输入校验

    @Test
    fun parsePBaseBoundsAndFormats() {
        assertEquals(180, MapEditViewModel.parsePBase("180"))
        assertEquals(660, MapEditViewModel.parsePBase("660"))
        assertEquals(330, MapEditViewModel.parsePBase(" 330 "))
        assertNull(MapEditViewModel.parsePBase("179"))
        assertNull(MapEditViewModel.parsePBase("661"))
        assertNull(MapEditViewModel.parsePBase("330.5")) // 非整数
        assertNull(MapEditViewModel.parsePBase(""))
        assertNull(MapEditViewModel.parsePBase("abc"))
    }

    @Test
    fun parseLapsBoundsAndFormats() {
        assertEquals(1, MapEditViewModel.parseLaps("1"))
        assertEquals(100, MapEditViewModel.parseLaps("100"))
        assertNull(MapEditViewModel.parseLaps("0"))
        assertNull(MapEditViewModel.parseLaps("101"))
        assertNull(MapEditViewModel.parseLaps("3.0"))
        assertNull(MapEditViewModel.parseLaps(""))
    }

    @Test
    fun parseSeedAcceptsLongTextOnly() {
        assertEquals(42L, MapEditViewModel.parseSeed("42"))
        assertEquals(-1L, MapEditViewModel.parseSeed("-1"))
        assertEquals(Long.MAX_VALUE, MapEditViewModel.parseSeed("9223372036854775807"))
        assertNull(MapEditViewModel.parseSeed(""))
        assertNull(MapEditViewModel.parseSeed("abc"))
        assertNull(MapEditViewModel.parseSeed("1.0"))
        assertNull(MapEditViewModel.parseSeed("9223372036854775808")) // 溢出
    }

    @Test
    fun validateInputsReturnsFirstErrorInPinnedOrder() {
        val vm = newViewModel()
        assertEquals(MapEditViewModel.InputError.P_BASE, vm.validateInputs("x", "y", "z"))
        assertEquals(MapEditViewModel.InputError.LAPS, vm.validateInputs("330", "y", "z"))
        assertEquals(MapEditViewModel.InputError.SEED, vm.validateInputs("330", "3", "z"))
        assertEquals(MapEditViewModel.InputError.NONE, vm.validateInputs("330", "3", "42"))
    }

    @Test
    fun applyInputsCommitsOnlyWhenAllValid() {
        val vm = newViewModel()
        assertEquals(MapEditViewModel.InputError.P_BASE, vm.applyInputs("999", "3", "42"))
        // 部分非法 ⇒ 状态整体不变
        assertEquals(MapEditViewModel.DEFAULT_P_BASE_SEC_PER_KM, vm.state.pBaseSecPerKm)
        assertEquals(MapEditViewModel.DEFAULT_LAPS, vm.state.laps)
        assertEquals(MapEditViewModel.DEFAULT_SEED, vm.state.seed)

        assertEquals(MapEditViewModel.InputError.NONE, vm.applyInputs("400", "7", "-9"))
        assertEquals(400, vm.state.pBaseSecPerKm)
        assertEquals(7, vm.state.laps)
        assertEquals(-9L, vm.state.seed)
    }

    @Test
    fun paceAndCadenceRangesMatchMotionContract() {
        val vm = newViewModel()
        // 配速范围设置：3:00–11:00 min/km = 180–660 s/km（唯一夹紧区间）
        assertEquals(MotionContracts.PACE_MIN_S_PER_KM, vm.paceRangeSecPerKm.start, 0.0)
        assertEquals(MotionContracts.PACE_MAX_S_PER_KM, vm.paceRangeSecPerKm.endInclusive, 0.0)
        // 步频范围设置：150–200 spm（夹紧区间）
        assertEquals(MotionContracts.CADENCE_MIN_SPM, vm.cadenceRangeSpm.start, 0.0)
        assertEquals(MotionContracts.CADENCE_MAX_SPM, vm.cadenceRangeSpm.endInclusive, 0.0)
        // laps 整数 1–100
        assertEquals(1, vm.lapsRange.first)
        assertEquals(TrajectoryGenerator.MAX_LAPS, vm.lapsRange.last)
    }

    // ---------------------------------------------------------------- 拟合预览 / 边界

    @Test
    fun tryFitIncompleteBeforeSixPoints() {
        val vm = newViewModel()
        idealPoints().take(5).forEach { vm.addPoint(it) }
        assertTrue(vm.tryFit() is MapEditViewModel.FitPreview.Incomplete)
    }

    @Test
    fun tryFitReadyYieldsPreviewAndBoundaryGeometry() {
        val vm = newViewModel()
        val model = run {
            idealPoints().forEach { vm.addPoint(it) }
            (vm.tryFit() as MapEditViewModel.FitPreview.Ready).model
        }
        val fit = vm.tryFit() as MapEditViewModel.FitPreview.Ready

        // 中心线 = MetricResampler（Δ=1m，N+1 点，首末重合）
        val n = Math.round(model.lengthM / MotionContracts.RESAMPLE_DELTA_M).toInt()
        assertEquals(n + 1, fit.centerlineWgs84.size)
        assertEquals(fit.centerlineWgs84.size, fit.edgePlusWgs84.size)
        assertEquals(fit.centerlineWgs84.size, fit.edgeMinusWgs84.size)
        val firstE = model.toEnu(fit.centerlineWgs84.first())
        val lastE = model.toEnu(fit.centerlineWgs84.last())
        assertTrue("预览闭合（首末重合）", (firstE - lastE).norm() <= 1e-3)

        // 边界走廊：两侧边线距中心线 W = min(1.75, 0.3R)
        val w = BoundaryGuard.halfWidth(model.r)
        for (i in fit.centerlineWgs84.indices step 50) {
            val c = model.toEnu(fit.centerlineWgs84[i])
            val plus = model.toEnu(fit.edgePlusWgs84[i])
            val minus = model.toEnu(fit.edgeMinusWgs84[i])
            assertEquals("edge+ 距中心线 = W", w, (plus - c).norm(), 1e-3)
            assertEquals("edge− 距中心线 = W", w, (minus - c).norm(), 1e-3)
        }
    }

    @Test
    fun tryFitRejectedOnDegenerateOrder() {
        val vm = newViewModel()
        // 镜像乱序 ⇒ TrackFitter 手性检出（中文可读原因透传）
        val ltp = LocalTangentPlane(ORIGIN)
        idealPoints().map { ltp.toLla2(Vec2(ltp.toEnu2(it).x, -ltp.toEnu2(it).y)) }
            .forEach { vm.addPoint(it) }
        val fit = vm.tryFit()
        assertTrue(fit is MapEditViewModel.FitPreview.Rejected)
        assertTrue(
            "应透传中文原因：" + (fit as MapEditViewModel.FitPreview.Rejected).message,
            fit.message.contains("点序绕向"),
        )
    }

    // ---------------------------------------------------------------- 预制跑道（载入 / 微调 / 整体变换）

    @Test
    fun loadPresetReplacesPointsKeepsParamsAndFits() {
        val vm = newViewModel()
        vm.applyInputs("400", "7", "42")
        val preset = TrackPresetLibrary.standard400(ORIGIN)

        val fit = vm.loadPreset(preset)

        // 预制六点零残差 ⇒ Ready；p_base/laps/seed 保留
        assertTrue("预制应直接拟合成功", fit is MapEditViewModel.FitPreview.Ready)
        assertEquals(preset.sixPoints(), vm.state.points)
        assertEquals(400, vm.state.pBaseSecPerKm)
        assertEquals(7, vm.state.laps)
        assertEquals(42L, vm.state.seed)
        val model = (fit as MapEditViewModel.FitPreview.Ready).model
        assertEquals(preset.straightHalfM, model.a, 1e-6)
        assertEquals(preset.bendRadiusM, model.r, 1e-6)
        // 生成链路可用（回放/导出同源）
        assertNotNull(vm.generateSamples())
    }

    @Test
    fun replacePointNudgeStaysWithinTolerance() {
        val vm = newViewModel()
        val preset = TrackPresetLibrary.standard400(ORIGIN)
        vm.loadPreset(preset)

        // p0 沿法向拖离槽位 0.4m（< tol=2.0m）⇒ 仍 Ready；反向拖 4m（> tol）⇒ Rejected 且消息可读
        val p0 = vm.state.points[0]
        val ltp = LocalTangentPlane(preset.center)
        val n = preset.nVec()
        val nudged = ltp.toLla2(ltp.toEnu2(p0) + n * 0.4)
        assertTrue(vm.replacePoint(0, nudged))
        assertTrue("0.4m 微调应在容差内", vm.tryFit() is MapEditViewModel.FitPreview.Ready)

        val far = ltp.toLla2(ltp.toEnu2(p0) - n * 4.0)
        assertTrue(vm.replacePoint(0, far))
        val rejected = vm.tryFit()
        assertTrue("超出容差应被拒绝", rejected is MapEditViewModel.FitPreview.Rejected)
        assertTrue((rejected as MapEditViewModel.FitPreview.Rejected).message.isNotBlank())
    }

    @Test
    fun replacePointOutOfRangeReturnsFalse() {
        val vm = newViewModel()
        vm.loadPreset(TrackPresetLibrary.standard400(ORIGIN))
        assertFalse(vm.replacePoint(-1, LatLon(0.0, 0.0)))
        assertFalse(vm.replacePoint(6, LatLon(0.0, 0.0)))
        // 无点位时同样拒绝
        val empty = newViewModel()
        assertFalse(empty.replacePoint(0, LatLon(0.0, 0.0)))
    }

    @Test
    fun presetPointsSurviveSnapshotRestore() {
        val vm = newViewModel()
        vm.loadPreset(TrackPresetLibrary.standard400(ORIGIN))
        val encoded = vm.snapshot()
        val restored = newViewModel()
        restored.restoreSnapshot(encoded)
        assertEquals(vm.state.points, restored.state.points)
        assertTrue(restored.tryFit() is MapEditViewModel.FitPreview.Ready)
    }

    @Test
    fun panScaleRotateTransformWholeTrackAndStayFitted() {
        val vm = newViewModel()
        val preset = TrackPresetLibrary.standard400(ORIGIN)
        vm.loadPreset(preset)
        val ltp = LocalTangentPlane(preset.center)

        // 平移：中心东移 10m，几何不变
        assertTrue(vm.panTrack(10.0, 0.0))
        val pts = vm.state.points
        val centerAfterPan = LatLon(
            pts.map { it.latitudeDeg }.average(),
            pts.map { it.longitudeDeg }.average(),
        )
        val dPan = ltp.toEnu2(centerAfterPan)
        assertEquals(10.0, dPan.x, 1e-6)
        assertTrue(vm.tryFit() is MapEditViewModel.FitPreview.Ready)
        assertEquals(preset.straightHalfM, (vm.tryFit() as MapEditViewModel.FitPreview.Ready).model.a, 1e-6)

        // 缩放 1.1：a/R 同比例增长
        assertTrue(vm.scaleTrack(1.1))
        val m2 = (vm.tryFit() as MapEditViewModel.FitPreview.Ready).model
        assertEquals(preset.straightHalfM * 1.1, m2.a, 1e-6)
        assertEquals(preset.bendRadiusM * 1.1, m2.r, 1e-6)

        // 旋转 30°：方位角模 180 前进 30°，几何尺寸不变
        assertTrue(vm.rotateTrack(30.0))
        val m3 = (vm.tryFit() as MapEditViewModel.FitPreview.Ready).model
        assertEquals(m2.a, m3.a, 1e-9)
        assertEquals(m2.r, m3.r, 1e-9)
        var h2 = Math.toDegrees(kotlin.math.atan2(m2.u.x, m2.u.y))
        var h3 = Math.toDegrees(kotlin.math.atan2(m3.u.x, m3.u.y))
        h2 = ((h2 % 180.0) + 180.0) % 180.0
        h3 = ((h3 % 180.0) + 180.0) % 180.0
        assertEquals(30.0, (h3 - h2 + 180.0) % 180.0, 1e-6)
    }

    @Test
    fun transformWorksOnManualPointsToo() {
        // 手点六点同样可整体变换（变换以拟合结果反推参数）
        val vm = newViewModel()
        idealPoints().forEach { vm.addPoint(it) }
        val before = (vm.tryFit() as MapEditViewModel.FitPreview.Ready).model
        assertTrue(vm.panTrack(0.0, -8.0)) // 南移 8m
        val after = (vm.tryFit() as MapEditViewModel.FitPreview.Ready).model
        assertEquals(before.a, after.a, 1e-9)
        val ltp = LocalTangentPlane(before.origin)
        val moved = ltp.toEnu2(after.origin)
        assertEquals(-8.0, moved.y, 1e-6)
    }

    @Test
    fun transformRejectedWithoutFitOrBeyondBounds() {
        // 无六点 ⇒ false 且状态不变
        val empty = newViewModel()
        assertFalse(empty.panTrack(5.0, 5.0))
        assertFalse(empty.scaleTrack(1.1))
        assertFalse(empty.rotateTrack(5.0))

        // 缩到模型界外（a≤2m/R≤5m）⇒ false 且六点保持上次合法值
        val vm = newViewModel()
        vm.loadPreset(TrackPresetLibrary.standard400(ORIGIN))
        val before = vm.state.points
        assertFalse(vm.scaleTrack(0.01)) // a=0.425m 越界
        assertEquals(before, vm.state.points)
        assertTrue(vm.tryFit() is MapEditViewModel.FitPreview.Ready)
    }

    // ---------------------------------------------------------------- 生成 / 导出（导出保序口径）

    @Test
    fun generateSamplesReproducibleForSameSeedAndDifferentForOther() {
        val pts = idealPoints()
        val vmA = newViewModel().apply {
            pts.forEach { addPoint(it) }
            applyInputs("330", "1", "42")
        }
        val vmB = newViewModel().apply {
            pts.forEach { addPoint(it) }
            applyInputs("330", "1", "42")
        }
        val vmC = newViewModel().apply {
            pts.forEach { addPoint(it) }
            applyInputs("330", "1", "43")
        }
        val a = vmA.generateSamples()!!
        val b = vmB.generateSamples()!!
        val c = vmC.generateSamples()!!
        assertTrue(a.isNotEmpty())
        assertEquals("同 seed 逐点一致", a, b)
        assertNotEquals("不同 seed 有差异", a, c)
    }

    @Test
    fun generateSamplesNullUntilFitted() {
        val vm = newViewModel()
        assertNull(vm.generateSamples())
        assertNull(vm.buildGpx())
        assertNull(vm.buildGeoJson())
    }

    @Test
    fun gpxExportPreservesSampleOrder() {
        val vm = newViewModel()
        idealPoints().forEach { vm.addPoint(it) }
        vm.applyInputs("330", "1", "42")
        val samples: List<TrackSample> = vm.generateSamples()!!
        val gpx = vm.buildGpx()!!
        // 输出点序 = 传入 List<TrackSample> 顺序（此处 = generate 输出顺序）
        assertEquals(samples.size, Regex("<trkpt ").findAll(gpx).count())
        assertTrue(gpx.contains("<trkpt lat=\"${samples.first().latitudeDeg}\" lon=\"${samples.first().longitudeDeg}\">"))
        assertTrue(gpx.contains("<trkpt lat=\"${samples.last().latitudeDeg}\" lon=\"${samples.last().longitudeDeg}\">"))
    }

    @Test
    fun geoJsonExportPreservesSampleOrderAndTimes() {
        val vm = newViewModel()
        idealPoints().forEach { vm.addPoint(it) }
        vm.applyInputs("330", "1", "42")
        val samples = vm.generateSamples()!!
        val json = JSONObject(vm.buildGeoJson()!!)
        val feature = json.getJSONArray("features").getJSONObject(0)
        val coords = feature.getJSONObject("geometry").getJSONArray("coordinates")
        val times = feature.getJSONObject("properties").getJSONArray("timesMs")
        assertEquals(samples.size, coords.length())
        assertEquals(samples.size, times.length())
        for (i in samples.indices) {
            // GeoJSON 规范 [lon, lat]
            assertEquals("第 $i 点 lon 保序", samples[i].longitudeDeg, coords.getJSONArray(i).getDouble(0), 1e-12)
            assertEquals("第 $i 点 lat 保序", samples[i].latitudeDeg, coords.getJSONArray(i).getDouble(1), 1e-12)
            assertEquals(samples[i].elapsedMs, times.getLong(i))
        }
    }

    // ---------------------------------------------------------------- 状态保存（与服务快照同风格快照）

    @Test
    fun snapshotRestoreRoundTrip() {
        val vm = newViewModel()
        idealPoints().take(4).forEach { vm.addPoint(it) }
        vm.applyInputs("400", "7", "-9")
        val encoded = vm.snapshot()

        val restored = newViewModel()
        restored.restoreSnapshot(encoded)
        assertEquals(vm.state, restored.state)
    }

    @Test
    fun restoreRejectsCorruptSnapshots() {
        val vm = newViewModel()
        for (bad in listOf("", "v1", "v2|330|3|42|", "v1|330|3|42", "v1|x|3|42|", "v1|330|3|z|", "v1|330|3|42|abc")) {
            try {
                vm.restoreSnapshot(bad)
                org.junit.Assert.fail("应拒绝损坏快照：$bad")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("快照") || e.message!!.contains("解析"))
            }
        }
        // 状态保持默认
        assertTrue(vm.state.points.isEmpty())
        assertEquals(MapEditViewModel.DEFAULT_P_BASE_SEC_PER_KM, vm.state.pBaseSecPerKm)
    }

    // ---------------------------------------------------------------- helpers

    companion object {

        /** 拟合原点（与 TrackFitterTest 同口径：投影为仿射映射 ⇒ 六点均值重合到 fp 精度）。 */
        private val ORIGIN = LatLon(30.0000, 110.0000) // 合成境内测试坐标，不对应个人位置

        private const val STD_A = 42.5
        private const val STD_R = 36.5

        /** 槽位理想六点（残差槽位，固定顺序 p0..p5，WGS-84）：标准试样 a=42.5、R=36.5。*/
        private fun idealPoints(): List<LatLon> {
            val ltp = LocalTangentPlane(ORIGIN)
            val slots = listOf(
                Vec2(0.0, STD_R), Vec2(-STD_A, STD_R), Vec2(-STD_A, -STD_R),
                Vec2(0.0, -STD_R), Vec2(STD_A, -STD_R), Vec2(STD_A, STD_R),
            )
            return slots.map { ltp.toLla2(it) }
        }
    }
}
