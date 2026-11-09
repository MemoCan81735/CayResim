package app.cayresim.feature.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import app.cayresim.core.boundary.AudioSourceKey
import app.cayresim.core.boundary.MicFailure
import app.cayresim.core.boundary.MotionKind
import app.cayresim.core.boundary.MotionSample
import app.cayresim.core.boundary.CameraStatus
import app.cayresim.core.boundary.fake.FakeCameraBoundary
import app.cayresim.core.boundary.fake.FakeMicrophoneBoundary
import app.cayresim.core.boundary.fake.FakeMotionSensorBoundary
import app.cayresim.core.boundary.fake.landscapeMotion
import app.cayresim.core.boundary.fake.stereoTones
import app.cayresim.core.control.SoundCameraUseCase
import app.cayresim.core.control.SoundImageUseCase
import app.cayresim.core.designsystem.CayResimTheme
import app.cayresim.core.pure.SoundBands
import app.cayresim.feature.settings.control.SoundBandUi
import app.cayresim.feature.settings.control.SoundCameraFailureUi
import app.cayresim.feature.settings.control.SoundCheckPhaseUi
import app.cayresim.feature.settings.control.SoundCheckProgressUi
import app.cayresim.feature.settings.control.SoundCheckResultUi
import app.cayresim.feature.settings.control.SoundCheckVerdictUi
import app.cayresim.feature.settings.control.SoundFailureUi
import app.cayresim.feature.settings.control.SoundHoldUi
import app.cayresim.feature.settings.control.SoundImageUiState
import app.cayresim.feature.settings.control.SoundImageViewModel
import app.cayresim.feature.settings.control.SoundMotionUi
import app.cayresim.feature.settings.control.SoundSourceUi
import app.cayresim.feature.settings.control.SoundVerdictUi
import app.cayresim.feature.settings.ui.PanelAxis
import app.cayresim.feature.settings.ui.SoundImageContent
import app.cayresim.feature.settings.ui.dotOnScreen
import app.cayresim.feature.settings.ui.levelText
import app.cayresim.feature.settings.ui.panelAxis
import app.cayresim.feature.settings.ui.signedDeg
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

    private fun vm(mic: FakeMicrophoneBoundary = mic(), s: FakeMotionSensorBoundary = sensors(), camera: FakeCameraBoundary = FakeCameraBoundary()): SoundImageViewModel {
        val sound = SoundImageUseCase(mic, s, { main.scheduler.currentTime }, main)
        return SoundImageViewModel(sound, SoundCameraUseCase(sound, camera, main))
    }

    /** Kurve mit einem Gipfel bei [angleDeg], an den Stuetzstellen der Rechnung. */
    private fun curve(angleDeg: Double, share: Double, widthDeg: Double) = List(SoundBands.GRID) { i ->
        val d = (SoundBands.angleDegrees(SoundBands.gridCosine(i)) - angleDeg) / widthDeg
        (share * exp(-d * d)).toFloat()
    }

    /** Fester Zustand: Quelle 30 Grad rechts in vier Baendern, das hoechste Band ohne Richtung. */
    private fun fixed() = SoundImageUiState(
        running = true, hold = SoundHoldUi.TOP_LEFT, mainAngleDeg = 30.2, mainShare = 0.8, framesPerSecond = 10, computeMillis = 0.4, lostChunks = 0,
        // S-021: Handy leicht schraeg, Punkt oben am Rand, obere Haelfte des Rings besucht
        motion = SoundMotionUi(rollDeg = 2.4, pitchDeg = -5.2, yawDeg = 0.3, moving = false, dotX = 0.02, dotY = 1.0, coverageMask = 0b0000_0011_1111),
        worldAngleDeg = 30.5,
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
        // Messwerte kommen im Zustand an: 10 Bilder je Sekunde, nichts verloren
        assertEquals(10, running.framesPerSecond); assertEquals(0L, running.lostChunks)
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
        compose.onNodeWithTag("soundimage").performScrollToNode(hasTestTag("soundimage_stats"))
        compose.onNodeWithText("Messung: Bilder je Sekunde 10, Rechenzeit je Bild (ms) 0,4, verlorene Tonstücke 0").assertIsDisplayed()
        compose.onNodeWithTag("soundimage").performScrollToNode(hasTestTag("back"))
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
        compose.onNodeWithText("Halte das Handy quer und aufrecht, Bildschirm zu dir", substring = true).assertIsDisplayed()
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
        compose.onNodeWithText("Bitte quer und aufrecht halten, Bildschirm zu dir (nicht flach hinlegen).").assertIsDisplayed()
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

    /** S-021: festes Ergebnis der Messung, wie es das S24+ liefern koennte; Videoton schaltet mit Kamera auf ein Mikrofon. */
    private fun checked() = SoundCheckResultUi(
        phases = listOf(
            SoundCheckPhaseUi(SoundSourceUi.MIC, false, true, 31.0, -41.2, -43.0, true, 0, 0.4, 2.0, -5.0, null, 0),
            SoundCheckPhaseUi(SoundSourceUi.MIC, true, true, 29.0, -41.9, -42.6, true, 3, 0.8, 2.0, -5.0, 30, 0),
            SoundCheckPhaseUi(SoundSourceUi.MIC, false, true, 30.0, -41.0, -43.3, true, 0, 0.6, 2.0, -4.0, null, 2),
            SoundCheckPhaseUi(SoundSourceUi.CAMCORDER, false, true, 30.0, -44.0, -45.0, true, 0, 0.5, 2.0, -4.0, null, 0),
            SoundCheckPhaseUi(SoundSourceUi.CAMCORDER, true, true, null, -44.0, -44.0, false, 0, 0.5, 2.0, -4.0, 29, 0),
            SoundCheckPhaseUi(SoundSourceUi.CAMCORDER, false, false),
        ),
        verdicts = listOf(SoundCheckVerdictUi(SoundSourceUi.MIC, SoundVerdictUi.SAME), SoundCheckVerdictUi(SoundSourceUi.CAMCORDER, SoundVerdictUi.ONE_CHANNEL)),
        cameraFailure = null, viewWideDeg = 75.8, viewHighDeg = 60.5, motionAvailable = true,
    )

    @Test fun `S-021 ViewModel misst mit Kamera und meldet die Lage`() {
        val mic = mic(); val camera = FakeCameraBoundary()
        val s = FakeMotionSensorBoundary(samples = (0..3).flatMap { landscapeMotion(it * 10_000_000L, rollRightUpDeg = 4.0) })
        val v = vm(mic, s, camera)
        v.onPermissionResult(true)
        main.scheduler.advanceTimeBy(2_500); main.scheduler.runCurrent()
        // Lage und Richtung im Raum kommen im Zustand an
        val live = v.uiState.value
        val m = assertNotNull(live.motion)
        assertEquals(4.0, assertNotNull(m.rollDeg), 0.5); assertEquals(0.0, assertNotNull(m.yawDeg), 0.5); assertFalse(m.moving)
        assertEquals(30.0, assertNotNull(live.worldAngleDeg), 5.0)
        // Messung: ersetzt den laufenden Lauf, weiter nur eine Aufnahme offen
        v.onCameraPermissionResult(true)
        main.scheduler.advanceTimeBy(500); main.scheduler.runCurrent()
        val started = v.uiState.value
        assertFalse(started.running); assertEquals(SoundCheckProgressUi(0, 6, SoundSourceUi.MIC, false), started.checkProgress)
        assertEquals(1, mic.open); assertEquals(1, mic.maxOpen)
        // die Tonquelle laesst sich waehrend der Messung nicht wechseln, ein zweiter Start aendert nichts
        v.onSourceSelected(SoundSourceUi.CAMCORDER); v.onCameraPermissionResult(true)
        assertEquals(SoundSourceUi.MIC, v.uiState.value.source)
        main.scheduler.advanceTimeBy(4_000); main.scheduler.runCurrent()
        assertEquals(SoundCheckProgressUi(1, 6, SoundSourceUi.MIC, true), v.uiState.value.checkProgress)
        assertEquals(CameraStatus.RUNNING, camera.state.value.status)
        main.scheduler.advanceTimeBy(25_000); main.scheduler.runCurrent()
        val done = v.uiState.value
        assertNull(done.checkProgress); assertFalse(done.running)
        val result = assertNotNull(done.checkResult)
        assertEquals(listOf(SoundVerdictUi.SAME, SoundVerdictUi.SAME), result.verdicts.map { it.verdict })
        assertEquals(6, result.phases.size); assertTrue(result.phases.all { it.recorded && it.stereo })
        assertEquals(listOf(null, 30, null, null, 30, null), result.phases.map { it.previewFps })
        assertEquals(75.8, assertNotNull(result.viewWideDeg), 0.1); assertTrue(result.motionAvailable); assertNull(result.cameraFailure)
        assertEquals(0, mic.open); assertEquals(0, s.active); assertEquals(CameraStatus.IDLE, camera.state.value.status); assertEquals(2, camera.stopCalls)
        // das Ergebnis bleibt stehen, wenn das Schallbild wieder laeuft und angehalten wird; Anhalten bricht eine Messung ab
        v.onPermissionResult(true); main.scheduler.advanceTimeBy(500); main.scheduler.runCurrent()
        v.onCameraPermissionResult(true)
        main.scheduler.advanceTimeBy(6_000); main.scheduler.runCurrent()
        assertEquals(CameraStatus.RUNNING, camera.state.value.status)
        v.onStop(); main.scheduler.runCurrent()
        assertNull(v.uiState.value.checkProgress); assertFalse(v.uiState.value.running)
        assertEquals(CameraStatus.IDLE, camera.state.value.status); assertEquals(0, mic.open); assertEquals(0, s.active)
        // Urteile und Kamerafehler: jeder Wert der Rechnung hat sein Gegenstueck
        assertEquals(app.cayresim.core.pure.SoundCameraCheck.Verdict.entries.map { it.name }, SoundVerdictUi.entries.map { it.name })
        val blocked = FakeCameraBoundary().apply { startError = app.cayresim.core.boundary.CameraError.IN_USE }
        val w = vm(camera = blocked)
        w.onCameraPermissionResult(true); main.scheduler.advanceTimeBy(30_000); main.scheduler.runCurrent()
        val failed = assertNotNull(w.uiState.value.checkResult)
        assertEquals(SoundCameraFailureUi.NOT_STARTED, failed.cameraFailure); assertEquals(SoundVerdictUi.CAMERA_FAILED, failed.verdicts.first().verdict)
    }

    @Test fun `S-021 ohne Kamera-Berechtigung`() {
        val mic = mic()
        val v = vm(mic)
        v.onPermissionResult(true); main.scheduler.advanceTimeBy(500); main.scheduler.runCurrent()
        v.onCameraPermissionResult(false)
        // das Schallbild laeuft weiter, keine Messung
        val s = v.uiState.value
        assertTrue(s.cameraDenied); assertTrue(s.running); assertNull(s.checkProgress); assertEquals(1, mic.open)
        val events = mutableListOf<String>()
        compose.setContent { CayResimTheme(dark = true) { SoundImageContent(fixed().copy(cameraDenied = true), 0, {}, {}, {}, {}, { events += "reset" }, { events += "check" }) } }
        compose.onNodeWithText("Ohne Kamera-Berechtigung geht der Vergleich mit Kamera nicht. Das Schallbild ohne Kamera bleibt nutzbar.").assertIsDisplayed()
        compose.onNodeWithTag("soundimage_panel").assertExists()
        compose.onNodeWithTag("soundimage").performScrollToNode(hasTestTag("soundimage_check"))
        compose.onNodeWithTag("soundimage_check").performClick()
        compose.onNodeWithTag("soundimage").performScrollToNode(hasTestTag("soundimage_reset"))
        compose.onNodeWithTag("soundimage_reset").performClick()
        assertEquals(listOf("check", "reset"), events)
    }

    @Test fun `S-021 Lagezeile im Schallbild`() {
        var state by mutableStateOf(fixed())
        compose.setContent { CayResimTheme(dark = true) { SoundImageContent(state, 0, {}, {}, {}, {}) } }
        compose.onNodeWithTag("soundimage").performScrollToNode(hasTestTag("soundimage_motion"))
        compose.onNodeWithText("Neigung quer +2°, Kippen -5°, Drehung seit Start 0°, ruhig").assertIsDisplayed()
        compose.onNodeWithText("Richtung im Raum: 31° rechts").assertExists()
        // bewegt: keine Richtung, die Zeile sagt es
        state = fixed().copy(mainAngleDeg = null, worldAngleDeg = null, bands = fixed().bands.map { it.copy(angleDeg = null) },
            motion = SoundMotionUi(-12.0, 3.0, 17.6, true, 1.17, -0.8, 0))
        compose.onNodeWithText("Neigung quer -12°, Kippen +3°, Drehung seit Start +18°, bewegt").assertExists()
        compose.onNodeWithTag("soundimage_world").assertDoesNotExist()
        // flach oder ohne Drehvektor: einzelne Werte unbekannt
        state = fixed().copy(motion = SoundMotionUi(null, null, null, false, 0.0, 0.0, 0), worldAngleDeg = null)
        compose.onNodeWithText("Neigung quer ?°, Kippen ?°, Drehung seit Start ?°, ruhig").assertExists()
        // kein Lagesensor
        state = fixed().copy(motion = null, worldAngleDeg = null)
        compose.onNodeWithText("Lage nicht verfügbar (kein Lagesensor).").assertExists()
        compose.onNodeWithTag("soundimage_circle").assertDoesNotExist()
        assertEquals("+2", signedDeg(2.4)); assertEquals("0", signedDeg(-0.3)); assertEquals("-5", signedDeg(-5.2)); assertEquals("?", signedDeg(null))
    }

    @Test @Config(sdk = [34], qualifiers = "w891dp-h411dp-land-xxhdpi")
    fun `S-021 Lage-Kreis halb gefuellt`() {
        compose.setContent { CayResimTheme(dark = true) { SoundImageContent(fixed(), 90, {}, {}, {}, {}) } }
        compose.onNodeWithTag("soundimage").performScrollToNode(hasTestTag("soundimage_circle"))
        compose.onNodeWithTag("soundimage_circle").assertIsDisplayed()
        compose.onNodeWithText("Lage-Kreis: Abschnitte 6 von 12. Führe den Punkt einmal am inneren Rand entlang (15°)", substring = true).assertIsDisplayed()
        // Punkt auf der Oberflaeche: rechts vom Nutzer ist je nach Drehung der Oberflaeche woanders, oben bleibt oben
        assertEquals(1f to 0f, dotOnScreen(1.0, 0.0, PanelAxis.RIGHT)); assertEquals(0f to -1f, dotOnScreen(0.0, 1.0, PanelAxis.RIGHT))
        assertEquals(-1f to 0f, dotOnScreen(1.0, 0.0, PanelAxis.LEFT)); assertEquals(0f to 1f, dotOnScreen(0.0, 1.0, PanelAxis.LEFT))
        assertEquals(0f to 1f, dotOnScreen(1.0, 0.0, PanelAxis.DOWN)); assertEquals(1f to 0f, dotOnScreen(0.0, 1.0, PanelAxis.DOWN))
        assertEquals(0f to -1f, dotOnScreen(1.0, 0.0, PanelAxis.UP)); assertEquals(-1f to 0f, dotOnScreen(0.0, 1.0, PanelAxis.UP))
    }

    @Test fun `S-021 Lage-Kreis vollstaendig`() {
        val full = fixed().copy(motion = SoundMotionUi(0.0, 0.0, 14.0, false, 0.93, 0.0, 0b1111_1111_1111))
        compose.setContent { CayResimTheme(dark = true) { SoundImageContent(full, 0, {}, {}, {}, {}) } }
        compose.onNodeWithTag("soundimage").performScrollToNode(hasTestTag("soundimage_circle_text"))
        compose.onNodeWithText("Lage-Kreis: Runde vollständig.").assertIsDisplayed()
    }

    @Test fun `S-021 Ergebnis der Messung mit Kamera`() {
        var state by mutableStateOf(SoundImageUiState(checkProgress = SoundCheckProgressUi(4, 6, SoundSourceUi.CAMCORDER, true)))
        val events = mutableListOf<String>()
        compose.setContent { CayResimTheme(dark = true) { SoundImageContent(state, 0, { events += "start" }, { events += "stop" }, {}, {}) } }
        // waehrend der Messung: Fortschritt, Anhalten bricht ab, keine Wahl der Tonquelle
        compose.onNodeWithText("Messung mit Kamera: Phase 5 von 6, Videoton (CAMCORDER), Kamera an. Bitte ruhig halten.").assertIsDisplayed()
        compose.onNodeWithTag("soundimage_source_MIC").assertDoesNotExist()
        compose.onNodeWithTag("soundimage_start").performClick()
        assertEquals(listOf("stop"), events)
        // Ergebnis
        state = SoundImageUiState(checkResult = checked())
        compose.onNodeWithText("Mikrofon (MIC): gleich. Die Kamera ändert den Ton nicht.").assertIsDisplayed()
        compose.onNodeWithText("Videoton (CAMCORDER): nur ein Kanal. Mit Kamera kommen nicht mehr zwei verschiedene Mikrofone an.").assertIsDisplayed()
        compose.onNodeWithText("Mikrofon (MIC), Kamera an: Richtung 29° rechts, Pegel (dBFS) -42 / -43, bewegt 3 %, Drehung 0,8°, Neigung quer +2°, Kippen -5°, Bilder je Sekunde 30").assertExists()
        compose.onNodeWithText("Mikrofon (MIC), Kamera aus: Richtung 30° rechts, Pegel (dBFS) -41 / -43, bewegt 0 %, Drehung 0,6°, Neigung quer +2°, Kippen -4°, verlorene Tonstücke 2").assertExists()
        compose.onNodeWithText("Videoton (CAMCORDER), Kamera an: Richtung keine Richtung, Pegel (dBFS) -44 / -44, nur ein Kanal", substring = true).assertExists()
        compose.onNodeWithText("Videoton (CAMCORDER), Kamera aus: Aufnahme gescheitert").assertExists()
        compose.onNodeWithTag("soundimage").performScrollToNode(hasTestTag("soundimage_check_footer"))
        compose.onNodeWithText("Bildwinkel der Kamera: 75,8° breit, 60,5° hoch").assertExists()
        compose.onNodeWithText("Gleich heißt: Richtung höchstens 5°, Pegel je Kanal höchstens 3 dB Abweichung.").assertExists()
        compose.onNodeWithText("Schallbild starten").assertExists()
        compose.onNodeWithTag("soundimage").performScrollToNode(hasTestTag("back"))
        compose.onRoot().captureRoboImage("src/test/screenshots/soundimage_check.png")
    }

    @Test fun `S-021 Ergebnis nennt Kamerafehler und fehlende Werte`() {
        var state by mutableStateOf(SoundImageUiState(checkResult = checked().copy(
            cameraFailure = SoundCameraFailureUi.NOT_STARTED, viewWideDeg = null, viewHighDeg = null, motionAvailable = false,
            verdicts = SoundVerdictUi.entries.take(4).map { SoundCheckVerdictUi(SoundSourceUi.MIC, it) },
            phases = listOf(SoundCheckPhaseUi(SoundSourceUi.MIC, true, true, 10.0, -40.0, -40.0)),
        )))
        compose.setContent { CayResimTheme(dark = true) { SoundImageContent(state, 0, {}, {}, {}, {}) } }
        compose.onNodeWithText("Die Kamera ließ sich nicht starten.").assertExists()
        compose.onNodeWithText("Bildwinkel der Kamera: unbekannt").assertExists()
        compose.onNodeWithText("Lage nicht verfügbar (kein Lagesensor).").assertExists()
        // ohne Lagesensor entfallen die Lagewerte der Phase; ohne Bilder steht ein Fragezeichen
        compose.onNodeWithText("Mikrofon (MIC), Kamera an: Richtung 10° rechts, Pegel (dBFS) -40 / -40, Bilder je Sekunde ?").assertExists()
        compose.onNodeWithText("Mikrofon (MIC): verändert. Mit Kamera weichen Richtung oder Pegel ab.").assertExists()
        compose.onNodeWithText("Mikrofon (MIC): kein Ton. Die Aufnahme mit Kamera scheiterte oder war stumm.").assertExists()
        state = SoundImageUiState(checkResult = checked().copy(cameraFailure = SoundCameraFailureUi.NO_FRAMES,
            verdicts = SoundVerdictUi.entries.drop(4).map { SoundCheckVerdictUi(SoundSourceUi.MIC, it) }))
        compose.onNodeWithText("Die Kamera lief, lieferte ohne Sucher aber keine Bilder.").assertExists()
        compose.onNodeWithText("Mikrofon (MIC): Szene unruhig. Schon ohne Kamera wich der Ton zwischen den Phasen ab. Bitte wiederholen.").assertExists()
        compose.onNodeWithText("Mikrofon (MIC): bewegt. Das Handy wurde zu stark bewegt. Bitte ruhig halten und wiederholen.").assertExists()
        compose.onNodeWithText("Mikrofon (MIC): keine Quelle. Ohne Kamera war kein gerichteter Ton zu hören. Bitte Musik näher stellen.").assertExists()
        compose.onNodeWithText("Mikrofon (MIC): Kamera scheitert. Die Kamera lief in der Messung nicht.").assertExists()
    }
}
