package app.cayresim.feature.settings

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import app.cayresim.core.boundary.CameraError
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

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
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
