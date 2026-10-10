package app.cayresim.feature.settings

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import app.cayresim.core.boundary.MotionKind
import app.cayresim.core.boundary.MotionSample
import app.cayresim.core.boundary.fake.FakeAudioFileBoundary
import app.cayresim.core.boundary.fake.FakeMicrophoneBoundary
import app.cayresim.core.boundary.fake.FakeMotionSensorBoundary
import app.cayresim.core.control.SweepRunReport
import app.cayresim.core.control.SweepUseCase
import app.cayresim.core.designsystem.CayResimTheme
import app.cayresim.core.pure.SweepMath
import app.cayresim.core.pure.Vec3
import app.cayresim.feature.settings.control.MicTestUiState
import app.cayresim.feature.settings.control.SweepEstimateFailureUi
import app.cayresim.feature.settings.control.SweepResultUi
import app.cayresim.feature.settings.control.SweepStepUi
import app.cayresim.feature.settings.control.SweepUiState
import app.cayresim.feature.settings.control.SweepViewModel
import app.cayresim.feature.settings.ui.MicTestContent
import app.cayresim.feature.settings.ui.SweepContent
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** S-010 K8: Schwenk-Messung im ViewModel und auf dem Bildschirm, mit Fakes und festen Zahlen. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class SweepTest {
    @get:Rule val compose = createComposeRule()
    private val main = UnconfinedTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(main)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun sensors() = FakeMotionSensorBoundary(samples = List(100) { MotionSample(MotionKind.ROTATION, 1_000_000_000L + it * 10_000_000L, 1f, 0f, 0f, 0f) })

    private fun vm(mic: FakeMicrophoneBoundary = FakeMicrophoneBoundary(), s: FakeMotionSensorBoundary = sensors()) =
        SweepViewModel(SweepUseCase(mic, s, FakeAudioFileBoundary(), main))

    /** Fester Bericht: Quelle 12 Grad rechts, 4 Grad unten, 15,1 cm, Gleichlauf 6 ms. */
    private fun fixedResult(): SweepResultUi = SweepViewModel.toUi(
        SweepRunReport(
            analysis = SweepMath.SweepReport(
                syncSeconds = 0.006, sensorRateHz = 99.6, framesTotal = 440, framesUsed = 412,
                estimate = SweepMath.Estimate.Ok(Vec3(0.2, 0.97, -0.07), 0.151, -0.00004, 0.000031, 0.083, 412),
                relAzimuthDeg = 12.2, relElevationDeg = -4.3,
            ),
            failure = null, timeExact = true, fileUri = "content://x", folder = "Recordings/CayResim/Schwenk-20261010-153000",
        ),
    )

    @Test fun `S-010 Bericht wird abgebildet`() {
        val r = fixedResult()
        assertNull(r.failure); assertNull(r.estimateFailure)
        assertEquals(12.2, assertNotNull(r.azimuthDeg), 1e-9); assertEquals(-4.3, assertNotNull(r.elevationDeg), 1e-9)
        assertEquals(15.1, assertNotNull(r.spacingCm), 1e-9); assertEquals(0.031, assertNotNull(r.residualMs), 1e-9)
        assertEquals(6.0, assertNotNull(r.syncMs), 1e-9); assertEquals(412, r.framesUsed); assertEquals(0.083, r.coverage, 1e-9)
    }

    @Test fun `S-010 ViewModel ohne Signal meldet zu wenige Messungen`() {
        val v = vm()
        v.onPermissionResult(true)
        main.scheduler.advanceUntilIdle()
        val s = v.uiState.value
        assertFalse(s.running); assertEquals(SweepStepUi.DONE, s.step)
        val r = assertNotNull(s.result)
        assertNull(r.failure)
        assertEquals(SweepEstimateFailureUi.TOO_FEW_MEASUREMENTS, r.estimateFailure)
        assertNotNull(r.folder)
    }

    @Test fun `S-010 Verlassen gibt Mikrofon und Sensor frei`() {
        val mic = FakeMicrophoneBoundary(recordDelayMillis = 25_000)
        val s = sensors()
        val v = vm(mic, s)
        v.onStart()
        assertTrue(v.uiState.value.running); assertEquals(SweepStepUi.TAP, v.uiState.value.step)
        assertEquals(1, mic.open); assertEquals(1, s.active)
        v.onStop(); main.scheduler.runCurrent()
        assertFalse(v.uiState.value.running)
        assertEquals(0, mic.open, "Mikrofon frei"); assertEquals(0, s.active, "Sensor abgemeldet")
    }

    @Test fun `S-010 ohne Berechtigung keine Messung`() {
        val mic = FakeMicrophoneBoundary(permission = false)
        val s = sensors()
        val v = vm(mic, s)
        v.onPermissionResult(true)
        main.scheduler.advanceUntilIdle()
        assertTrue(v.uiState.value.permissionDenied); assertTrue(mic.requests.isEmpty()); assertEquals(0, s.registrations)
    }

    @Test fun schwenk_anleitung() {
        compose.setContent { CayResimTheme(dark = true) { SweepContent(SweepUiState(), {}, {}) } }
        compose.onNodeWithText("Zweimal auf die Rückseite tippen", substring = true).assertExists()
        compose.onRoot().captureRoboImage("src/test/screenshots/sweep_guide.png")
    }

    @Test fun schwenk_ansage() {
        val state = SweepUiState(running = true, step = SweepStepUi.SWEEP, seconds = 22)
        compose.setContent { CayResimTheme(dark = true) { SweepContent(state, {}, {}) } }
        compose.onNodeWithText("Langsam schwenken").assertExists()
        compose.onNodeWithText("Zeit in Sekunden: 22", substring = true).assertExists()
        compose.onRoot().captureRoboImage("src/test/screenshots/sweep_prompt.png")
    }

    @Test fun schwenk_ergebnis() {
        val state = SweepUiState(step = SweepStepUi.DONE, result = fixedResult())
        compose.setContent { CayResimTheme(dark = false) { SweepContent(state, {}, {}) } }
        compose.onNodeWithTag("sweep").performScrollToNode(hasTestTag("sweep_result"))
        compose.onNodeWithText("plus heißt rechts) +12", substring = true).assertExists()
        compose.onNodeWithText("Wirksamer Mikrofonabstand (cm): 15,1", substring = true).assertExists()
        compose.onRoot().captureRoboImage("src/test/screenshots/sweep_result.png")
    }

    @Test fun `S-010 einseitiger Schwenk wird erklaert`() {
        val state = SweepUiState(step = SweepStepUi.DONE, result = SweepResultUi(failure = null, estimateFailure = SweepEstimateFailureUi.ONE_SIDED, coverage = 0.01))
        compose.setContent { CayResimTheme { SweepContent(state, {}, {}) } }
        compose.onNodeWithTag("sweep").performScrollToNode(hasTestTag("sweep_result"))
        compose.onNodeWithText("zu einseitig geschwenkt", substring = true).assertExists()
    }

    @Test fun `S-010 Knopf im Mikrofon-Test oeffnet die Schwenk-Messung`() {
        val events = mutableListOf<String>()
        compose.setContent { CayResimTheme { MicTestContent(MicTestUiState(), {}, {}, onSweep = { events += "schwenk" }) } }
        compose.onNodeWithTag("mictest_sweep").performClick()
        assertEquals(listOf("schwenk"), events)
    }

    /** Hausregel: keine Gedankenstriche in den neuen Texten. */
    @Test fun texte_ohne_gedankenstriche() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        R.string::class.java.fields.filter { it.name.startsWith("sweep_") }.forEach { f ->
            val text = ctx.getString(f.getInt(null))
            assertFalse('–' in text || '—' in text, "Gedankenstrich in ${f.name}")
        }
    }
}
