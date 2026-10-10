package app.cayresim.feature.settings

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import app.cayresim.core.boundary.CameraError
import app.cayresim.core.boundary.DeviceReport
import app.cayresim.core.boundary.HardwareLevel
import app.cayresim.core.boundary.PhotoMode
import app.cayresim.core.boundary.fake.FakeCameraBoundary
import app.cayresim.core.boundary.fake.FakeSelfTestJournalBoundary
import app.cayresim.core.control.SelfTestCheck
import app.cayresim.core.control.SelfTestUseCase
import app.cayresim.core.designsystem.CayResimTheme
import app.cayresim.feature.settings.control.CheckKind
import app.cayresim.feature.settings.control.CheckRow
import app.cayresim.feature.settings.control.SelfTestUiState
import app.cayresim.feature.settings.control.SelfTestViewModel
import app.cayresim.feature.settings.ui.SelfTestContent
import app.cayresim.feature.settings.ui.exposureText
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class SelfTestTest {
    @get:Rule val compose = createComposeRule()
    private var t = 0L
    private val clock = app.cayresim.core.pure.Clock { t += 7; t }

    /** S-006: der Selbsttest wartet bis 1,5 s auf den Stabilisator-Wert; Tests lassen die virtuelle Uhr weiterlaufen. */
    private val main = UnconfinedTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(main)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun viewmodel_guter_fall() {
        val vm = SelfTestViewModel(SelfTestUseCase(FakeCameraBoundary(setOf(PhotoMode.NIGHT)), clock, FakeSelfTestJournalBoundary()))
        vm.onStart()
        assertTrue(vm.uiState.value.allPassed)
        assertEquals(2, vm.uiState.value.rows.count { it.kind == CheckKind.MODE_CAPTURE })
    }

    @Test fun viewmodel_kamera_belegt() {
        val vm = SelfTestViewModel(SelfTestUseCase(FakeCameraBoundary().apply { startError = CameraError.IN_USE }, clock, FakeSelfTestJournalBoundary()))
        vm.onStart()
        assertTrue(vm.uiState.value.finished); assertFalse(vm.uiState.value.allPassed)
    }

    @Test fun viewmodel_jede_pruefung_hat_eine_anzeige() {
        SelfTestCheck.entries.forEach { CheckKind.valueOf(it.name) }
        assertEquals(SelfTestCheck.entries.size, CheckKind.entries.size)
    }

    /** Gemessene Werte des S24+ (Selbsttest vom Geraet). */
    private val s24 = DeviceReport(
        HardwareLevel.FULL, 85_000L..100_000_000L, 25..3200, 142_857_142L, 15..15,
        raw = true, burst = true, sensorWidth = 4080, sensorHeight = 3060, zsl = false,
        zoomMin = 0.6f, zoomMax = 10f, physicalCameras = 3, chip = "s5e9945", system = "Android 16", ois = true,
    )

    @Test fun viewmodel_reicht_geraetewerte_als_zahlen_weiter() {
        val vm = SelfTestViewModel(SelfTestUseCase(FakeCameraBoundary().apply { deviceReport = s24 }, clock, FakeSelfTestJournalBoundary()))
        vm.onStart(); main.scheduler.advanceUntilIdle()
        val d = vm.uiState.value.rows.single { it.kind == CheckKind.DEVICE }.device!!
        assertEquals("FULL", d.hardwareLevel)
        assertEquals(100_000_000L, d.exposureMaxNs); assertEquals(3200, d.isoMax); assertEquals(4080, d.sensorWidth)
    }

    @Test fun belichtung_lesbar() {
        assertEquals("1/10 s", exposureText(100_000_000L))
        assertEquals("1/7 s", exposureText(142_857_142L))
        assertEquals("1/11765 s", exposureText(85_000L))
        assertEquals("2.0 s", exposureText(2_000_000_000L).replace(',', '.'))
    }

    @Test fun bild_geraetewerte() {
        val vm = SelfTestViewModel(SelfTestUseCase(FakeCameraBoundary().apply { deviceReport = s24 }, clock, FakeSelfTestJournalBoundary()))
        vm.onStart(); main.scheduler.advanceUntilIdle()
        val state = vm.uiState.value.copy(rows = vm.uiState.value.rows.filter { it.kind == CheckKind.DEVICE })
        compose.setContent { CayResimTheme(dark = true) { SelfTestContent(state, {}, {}) } }
        compose.onNodeWithText("Belichtung: 1/11765 s bis 1/10 s", substring = true).assertExists()
        compose.onNodeWithText("ISO: 25 bis 3200", substring = true).assertExists()
        compose.onNodeWithText("RAW: ja, Serienbilder: ja", substring = true).assertExists()
        // S-003 K4, S-007: Fake meldet keine Aufnahme
        compose.onNodeWithText("Optischer Stabilisator: ja, aktiv: kein Aufnahmeergebnis", substring = true).assertExists()
        compose.onRoot().captureRoboImage("src/test/screenshots/selftest_device.png")
    }

    @Test fun `S-007 Stabilisator nicht gemeldet`() {
        val cam = FakeCameraBoundary().apply { deviceReport = s24; stabilize(app.cayresim.core.boundary.OisState.NOT_REPORTED) }
        val vm = SelfTestViewModel(SelfTestUseCase(cam, clock, FakeSelfTestJournalBoundary()))
        vm.onStart(); main.scheduler.advanceUntilIdle()
        val state = vm.uiState.value.copy(rows = vm.uiState.value.rows.filter { it.kind == CheckKind.DEVICE })
        compose.setContent { CayResimTheme(dark = true) { SelfTestContent(state, {}, {}) } }
        compose.onNodeWithText("Optischer Stabilisator: ja, aktiv: vom Gerät nicht gemeldet (angefordert: ein)", substring = true).assertExists()
    }

    @Test fun `S-007 ohne Stabilisator kein Wert fuer aktiv`() {
        val vm = SelfTestViewModel(SelfTestUseCase(FakeCameraBoundary().apply { deviceReport = s24.copy(ois = false) }, clock, FakeSelfTestJournalBoundary()))
        vm.onStart(); main.scheduler.advanceUntilIdle()
        val state = vm.uiState.value.copy(rows = vm.uiState.value.rows.filter { it.kind == CheckKind.DEVICE })
        compose.setContent { CayResimTheme(dark = true) { SelfTestContent(state, {}, {}) } }
        compose.onNodeWithText("Optischer Stabilisator: nein", substring = true).assertExists()
        compose.onAllNodesWithText("aktiv:", substring = true).assertCountEquals(0)
    }

    @Test fun bild_raw_serie() {
        val cam = FakeCameraBoundary()
        val vm = SelfTestViewModel(SelfTestUseCase(cam, clock, FakeSelfTestJournalBoundary(), app.cayresim.core.boundary.fake.FakeManualCameraBoundary(cam)))
        vm.onStart()
        val row = vm.uiState.value.rows.single { it.kind == CheckKind.RAW_SERIES }
        assertEquals(8, row.raw!!.frames); assertEquals("GRBG", row.raw!!.cfa)
        compose.setContent { CayResimTheme(dark = true) { SelfTestContent(vm.uiState.value.copy(rows = listOf(row)), {}, {}) } }
        compose.onNodeWithText("Bilder: 8 von 8, je 180 ms (höchstens 240 ms)", substring = true).assertExists()
        compose.onNodeWithText("Schwarz: 64/64/64/64, Weiß: 1023, Farbmuster: GRBG", substring = true).assertExists()
        compose.onNodeWithText("Signal über Schwarz: 3,2, Rauschen: 4,1 Stufen", substring = true).assertExists()
        compose.onNodeWithText("Werte genau 0: 12,0 %", substring = true).assertExists()
        compose.onNodeWithText("RAW-Bildstrom: 9,8 fps (Gerät: bis 30,0 fps)", substring = true).assertExists()
        compose.onNodeWithText("Schwarz laut Aufnahme: 64,0/64,0/64,0/64,0, Weiß: 1023", substring = true).assertExists()
    }

    @Test fun nachtweg_wird_mit_grund_angezeigt() {
        val cam = FakeCameraBoundary()
        val proc = app.cayresim.core.boundary.fake.FakeProcessingBoundary().apply { onRawSaved = { cam.adopt(it) } }
        val vm = SelfTestViewModel(SelfTestUseCase(cam, clock, FakeSelfTestJournalBoundary(), app.cayresim.core.boundary.fake.FakeManualCameraBoundary(cam),
            app.cayresim.core.boundary.fake.FakeFrameBoundary(cam), proc, app.cayresim.core.boundary.fake.FakeNightPathBoundary()))
        vm.onStart()
        val row = vm.uiState.value.rows.single { it.kind == CheckKind.NIGHT_PATH }
        assertEquals("RAW", row.nightPath)
        compose.setContent { CayResimTheme(dark = true) { SelfTestContent(vm.uiState.value.copy(rows = listOf(row)), {}, {}) } }
        compose.onNodeWithText("Gewählt: RAW, Grund: RAW-Probenacht gelungen", substring = true).assertExists()
    }

    @Test fun leerer_zustand_ist_nicht_gruen() = assertFalse(SelfTestUiState(finished = true).allPassed)

    private val rows = listOf(
        CheckRow(CheckKind.CAMERA_START, null, true, 420, ""),
        CheckRow(CheckKind.CAPABILITIES, null, true, 0, "NORMAL, NIGHT, HDR"),
        CheckRow(CheckKind.MODE_CAPTURE, "NORMAL", true, 310, ""),
        CheckRow(CheckKind.MODE_CAPTURE, "NIGHT", false, 2900, "STORAGE"),
        CheckRow(CheckKind.CLEANUP, null, true, 0, "1/1"),
    )

    @Test fun bild_ergebnis_rot() {
        compose.setContent { CayResimTheme(dark = true) { SelfTestContent(SelfTestUiState(finished = true, rows = rows), {}, {}) } }
        compose.onRoot().captureRoboImage("src/test/screenshots/selftest_failed.png")
    }

    @Test fun bild_ergebnis_gruen_hell() {
        val ok = rows.map { it.copy(passed = true, detail = "") }
        compose.setContent { CayResimTheme(dark = false) { SelfTestContent(SelfTestUiState(finished = true, rows = ok), {}, {}) } }
        compose.onRoot().captureRoboImage("src/test/screenshots/selftest_ok_light.png")
    }

    @Test fun knopf_gesperrt_waehrend_lauf() {
        compose.setContent { CayResimTheme { SelfTestContent(SelfTestUiState(running = true), {}, {}) } }
        compose.onNodeWithTag("selftest_start").assertIsNotEnabled()
    }

    @Test fun knopf_startet() {
        var started = 0
        compose.setContent { CayResimTheme { SelfTestContent(SelfTestUiState(), { started++ }, {}) } }
        compose.onNodeWithTag("selftest_start").performClick()
        assertEquals(1, started)
    }
}
