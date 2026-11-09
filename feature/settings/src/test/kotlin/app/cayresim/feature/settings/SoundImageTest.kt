package app.cayresim.feature.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import app.cayresim.core.boundary.AudioSourceKey
import app.cayresim.core.boundary.MicFailure
import app.cayresim.core.boundary.MotionKind
import app.cayresim.core.boundary.MotionSample
import app.cayresim.core.boundary.fake.FakeMicrophoneBoundary
import app.cayresim.core.boundary.fake.FakeMotionSensorBoundary
import app.cayresim.core.boundary.fake.stereoTones
import app.cayresim.core.control.SoundImageUseCase
import app.cayresim.core.designsystem.CayResimTheme
import app.cayresim.core.pure.SoundBands
import app.cayresim.feature.settings.control.SoundBandUi
import app.cayresim.feature.settings.control.SoundFailureUi
import app.cayresim.feature.settings.control.SoundHoldUi
import app.cayresim.feature.settings.control.SoundImageUiState
import app.cayresim.feature.settings.control.SoundImageViewModel
import app.cayresim.feature.settings.control.SoundSourceUi
import app.cayresim.feature.settings.ui.PanelAxis
import app.cayresim.feature.settings.ui.SoundImageContent
import app.cayresim.feature.settings.ui.levelText
import app.cayresim.feature.settings.ui.panelAxis
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
import kotlin.math.exp
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** S-018 K15 bis K17: Schallbild im ViewModel und auf dem Bildschirm, mit Fakes und festen Zahlen. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class SoundImageTest {
    @get:Rule val compose = createComposeRule()
    private val main = UnconfinedTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(main)
    @After fun tearDown() = Dispatchers.resetMain()

    private val chunk = SoundImageUseCase.SAMPLE_RATE / 10

    /** Mikrofon mit einer kuenstlichen Quelle 30 Grad zur Achse; alle 100 ms (virtuelle Zeit) ein Stueck. */
    private fun mic() = FakeMicrophoneBoundary().apply {
        val pcm = stereoTones(30.0)
        streamSound = { _, i -> pcm.copyOfRange((i % 30) * chunk * 2, (i % 30 + 1) * chunk * 2) }
    }

    /** Quer gehalten, Oberkante links. */
    private fun sensors() = FakeMotionSensorBoundary(samples = List(5) { MotionSample(MotionKind.ACCELERATION, 1_000_000_000L + it * 50_000_000L, 9.81f, 0f, 0f) })

    private fun vm(mic: FakeMicrophoneBoundary = mic(), s: FakeMotionSensorBoundary = sensors()) = SoundImageViewModel(SoundImageUseCase(mic, s, main))

    /** Kurve mit einem Gipfel bei [angleDeg], an den Stuetzstellen der Rechnung. */
    private fun curve(angleDeg: Double, share: Double, widthDeg: Double) = List(SoundBands.GRID) { i ->
        val d = (SoundBands.angleDegrees(SoundBands.gridCosine(i)) - angleDeg) / widthDeg
        (share * exp(-d * d)).toFloat()
    }

    /** Fester Zustand: Quelle 30 Grad rechts in vier Baendern, das hoechste Band ohne Richtung. */
    private fun fixed() = SoundImageUiState(
        running = true, hold = SoundHoldUi.TOP_LEFT, mainAngleDeg = 30.2, mainShare = 0.8,
        bands = listOf(
            SoundBandUi(200, 400, -45.0, 0.9, 22.0, curve(22.0, 0.9, 40.0)),
            SoundBandUi(400, 800, -42.0, 0.85, 30.0, curve(30.0, 0.85, 20.0)),
            SoundBandUi(800, 1600, -50.0, 0.7, 31.0, curve(31.0, 0.7, 10.0)),
            SoundBandUi(1600, 3200, -58.0, 0.5, 29.0, curve(29.0, 0.5, 6.0)),
            SoundBandUi(3200, 6400, -71.0, 0.12, null, curve(-60.0, 0.12, 5.0)),
        ),
    )

    @Test fun `S-018 ViewModel startet, stoppt und wechselt die Quelle`() {
        val mic = mic(); val s = sensors()
        val v = vm(mic, s)
        assertFalse(v.uiState.value.running)
        v.onPermissionResult(true)
        assertTrue(v.uiState.value.running); assertNull(v.uiState.value.hold)
        main.scheduler.advanceTimeBy(2_500); main.scheduler.runCurrent()
        val running = v.uiState.value
        assertEquals(SoundHoldUi.TOP_LEFT, running.hold); assertEquals(5, running.bands.size)
        assertEquals(30.0, assertNotNull(running.mainAngleDeg), 5.0)
        assertEquals(1, mic.open); assertEquals(1, s.active)
        assertEquals(AudioSourceKey.MIC, mic.streamRequests.single().source)
        // Wechsel der Quelle: neu gestartet, weiter nur eine Aufnahme offen
        v.onSourceSelected(SoundSourceUi.CAMCORDER)
        main.scheduler.advanceTimeBy(500); main.scheduler.runCurrent()
        assertTrue(v.uiState.value.running); assertEquals(SoundSourceUi.CAMCORDER, v.uiState.value.source)
        assertEquals(AudioSourceKey.CAMCORDER, mic.streamRequests.last().source); assertEquals(2, mic.streamRequests.size)
        assertEquals(1, mic.open); assertEquals(1, s.active)
        // gleiche Quelle noch einmal: kein Neustart
        v.onSourceSelected(SoundSourceUi.CAMCORDER)
        assertEquals(2, mic.streamRequests.size)
        // Anhalten gibt frei, die gewaehlte Quelle bleibt
        v.onStop(); main.scheduler.runCurrent()
        assertFalse(v.uiState.value.running); assertTrue(v.uiState.value.bands.isEmpty()); assertEquals(SoundSourceUi.CAMCORDER, v.uiState.value.source)
        assertEquals(0, mic.open, "Mikrofon frei"); assertEquals(0, s.active, "Sensor abgemeldet")
    }

    @Test fun `S-018 ViewModel meldet Fehler im Zustand`() {
        // Berechtigung abgelehnt
        val denied = vm()
        denied.onPermissionResult(false)
        assertTrue(denied.uiState.value.permissionDenied); assertFalse(denied.uiState.value.running)
        // Berechtigung fehlt trotz Zusage (entzogen)
        val revoked = vm(FakeMicrophoneBoundary(permission = false))
        revoked.onPermissionResult(true); main.scheduler.advanceUntilIdle()
        assertTrue(revoked.uiState.value.permissionDenied); assertNull(revoked.uiState.value.failure); assertFalse(revoked.uiState.value.running)
        // Aufnahme scheitert
        val broken = vm(FakeMicrophoneBoundary().apply { failures[AudioSourceKey.MIC] = MicFailure.INIT_FAILED })
        broken.onPermissionResult(true); main.scheduler.advanceUntilIdle()
        assertEquals(SoundFailureUi.RECORD_FAILED, broken.uiState.value.failure); assertFalse(broken.uiState.value.running)
        // nur ein Kanal
        val mono = vm(FakeMicrophoneBoundary().apply { channelsFor = { 1 } })
        mono.onPermissionResult(true); main.scheduler.advanceTimeBy(500); main.scheduler.runCurrent()
        assertEquals(SoundFailureUi.NO_STEREO, mono.uiState.value.failure)
        // nur die beiden Quellen, die am S24+ zwei Mikrofone liefern (S-008)
        assertEquals(listOf("MIC", "CAMCORDER"), SoundSourceUi.entries.map { it.name })
    }

    @Test fun `S-018 Streifen folgen der Haltung, nicht der Drehung der Oberflaeche`() {
        // Oberflaeche quer wie das Handy: rechts ist rechts
        assertEquals(PanelAxis.RIGHT, panelAxis(SoundHoldUi.TOP_LEFT, 90)); assertEquals(PanelAxis.RIGHT, panelAxis(SoundHoldUi.TOP_RIGHT, 270))
        // Oberflaeche steht kopf gegenueber dem Nutzer
        assertEquals(PanelAxis.LEFT, panelAxis(SoundHoldUi.TOP_RIGHT, 90)); assertEquals(PanelAxis.LEFT, panelAxis(SoundHoldUi.TOP_LEFT, 270))
        // Oberflaeche hochkant (automatisch drehen aus), Handy quer: rechts vom Nutzer ist die Unterkante
        assertEquals(PanelAxis.DOWN, panelAxis(SoundHoldUi.TOP_LEFT, 0)); assertEquals(PanelAxis.UP, panelAxis(SoundHoldUi.TOP_RIGHT, 0))
        assertEquals(PanelAxis.UP, panelAxis(SoundHoldUi.TOP_LEFT, 180)); assertEquals(PanelAxis.DOWN, panelAxis(SoundHoldUi.TOP_RIGHT, 180))
        // ohne bekannte Haltung wie Oberkante links
        assertEquals(PanelAxis.RIGHT, panelAxis(null, 90)); assertEquals(PanelAxis.RIGHT, panelAxis(SoundHoldUi.UNKNOWN, 90))
        assertEquals(PanelAxis.RIGHT, panelAxis(SoundHoldUi.NOT_LANDSCAPE, 450))
        assertEquals("-42", levelText(-42.4)); assertEquals("-99", levelText(Double.NEGATIVE_INFINITY)); assertEquals("-99", levelText(Double.NaN))
    }

    @Test @Config(sdk = [34], qualifiers = "w891dp-h411dp-land-xxhdpi")
    fun `S-018 Bildschirm zeigt Streifen und Zahlen`() {
        compose.setContent { CayResimTheme(dark = true) { SoundImageContent(fixed(), 90, {}, {}, {}, {}) } }
        compose.onNodeWithTag("soundimage_panel").assertIsDisplayed()
        compose.onNodeWithText("Hauptrichtung: 30° rechts").assertIsDisplayed()
        compose.onNodeWithText("400 bis 800 Hz: 30° rechts, Anteil 0,85, -42 dBFS").assertIsDisplayed()
        compose.onNodeWithText("3200 bis 6400 Hz: keine Richtung, Anteil 0,12, -71 dBFS").assertExists()
        compose.onNodeWithText("Anhalten").assertIsDisplayed()
        compose.onRoot().captureRoboImage("src/test/screenshots/soundimage_running.png")
    }

    @Test fun `S-018 Bildschirm hochkant zeigt die Streifen quer`() {
        // automatisch drehen aus: Oberflaeche hochkant, Handy quer mit der Oberkante links
        compose.setContent { CayResimTheme(dark = true) { SoundImageContent(fixed().copy(mainAngleDeg = -30.0), 0, {}, {}, {}, {}) } }
        compose.onNodeWithTag("soundimage_panel").assertIsDisplayed()
        compose.onNodeWithText("Hauptrichtung: 30° links").assertIsDisplayed()
        compose.onRoot().captureRoboImage("src/test/screenshots/soundimage_upright.png")
    }

    @Test fun `S-018 Zustaende ohne Streifen`() {
        var state by mutableStateOf(SoundImageUiState())
        val events = mutableListOf<String>()
        compose.setContent {
            CayResimTheme(dark = true) { SoundImageContent(state, 0, { events += "start" }, { events += "stop" }, { events += it.name }, { events += "back" }) }
        }
        // ohne Lauf: Anleitung, Startknopf, Wahl der Tonquelle
        compose.onNodeWithText("Halte das Handy quer, Bildschirm zu dir", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Schallbild starten").assertIsDisplayed()
        compose.onNodeWithTag("soundimage_source_MIC").assertIsSelected()
        compose.onNodeWithTag("soundimage_start").performClick()
        compose.onNodeWithTag("soundimage_source_CAMCORDER").performClick()
        compose.onNodeWithTag("back").performClick()
        assertEquals(listOf("start", "CAMCORDER", "back"), events)
        compose.onRoot().captureRoboImage("src/test/screenshots/soundimage_idle.png")
        // laeuft, noch keine Momentaufnahme
        state = SoundImageUiState(running = true)
        compose.onNodeWithText("Hört zu …").assertIsDisplayed()
        compose.onNodeWithTag("soundimage_start").performClick()
        assertEquals("stop", events.last())
        // nicht quer gehalten
        state = SoundImageUiState(running = true, hold = SoundHoldUi.NOT_LANDSCAPE)
        compose.onNodeWithText("Bitte quer halten, Bildschirm zu dir.").assertIsDisplayed()
        // quer, aber kein gerichteter Ton; Lage unbekannt
        state = fixed().copy(hold = SoundHoldUi.UNKNOWN, mainAngleDeg = null, bands = fixed().bands.map { it.copy(angleDeg = null) })
        compose.onNodeWithText("Hauptrichtung: kein gerichteter Ton").assertIsDisplayed()
        compose.onNodeWithTag("soundimage_hold").assertIsDisplayed()
        // genau vorne
        state = fixed().copy(mainAngleDeg = 0.3)
        compose.onNodeWithText("Hauptrichtung: Mitte").assertIsDisplayed()
        // Fehler
        state = SoundImageUiState(permissionDenied = true)
        compose.onNodeWithTag("soundimage_permission").assertIsDisplayed()
        state = SoundImageUiState(failure = SoundFailureUi.NO_STEREO, source = SoundSourceUi.CAMCORDER)
        compose.onNodeWithText("Diese Tonquelle liefert nur ein Mikrofon", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("soundimage_source_CAMCORDER").assertIsSelected()
        state = SoundImageUiState(failure = SoundFailureUi.RECORD_FAILED)
        compose.onNodeWithText("Die Aufnahme ist gescheitert", substring = true).assertIsDisplayed()
        state = SoundImageUiState(failed = true)
        compose.onNodeWithTag("soundimage_error").assertIsDisplayed()
    }
}
