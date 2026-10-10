package app.cayresim.feature.settings

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import app.cayresim.core.boundary.AudioSourceKey
import app.cayresim.core.boundary.MicFailure
import app.cayresim.core.boundary.MicRequest
import app.cayresim.core.boundary.fake.FakeAudioFileBoundary
import app.cayresim.core.boundary.fake.FakeMicrophoneBoundary
import app.cayresim.core.control.MicTestUseCase
import app.cayresim.core.designsystem.CayResimTheme
import app.cayresim.feature.settings.control.ClapPhaseUi
import app.cayresim.feature.settings.control.MicStepUi
import app.cayresim.feature.settings.control.MicTestUiState
import app.cayresim.feature.settings.control.MicTestViewModel
import app.cayresim.feature.settings.control.SourceRowUi
import app.cayresim.feature.settings.control.SourceUi
import app.cayresim.feature.settings.control.DirectionUi
import app.cayresim.feature.settings.ui.MicTestContent
import app.cayresim.feature.settings.ui.db
import app.cayresim.feature.settings.ui.signed2
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
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** S-008 K12: Mikrofon-Test im ViewModel und auf dem Bildschirm, mit kuenstlichem Ton. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class MicTestTest {
    @get:Rule val compose = createComposeRule()
    private val main = UnconfinedTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(main)
    @After fun tearDown() = Dispatchers.resetMain()

    private var t = 0L
    private val clock = app.cayresim.core.pure.Clock { t }

    private fun noise(r: MicRequest, ch: Int): ShortArray {
        val rnd = Random(r.hashCode()); return ShortArray(r.sampleRate * r.millis / 1000 * ch) { rnd.nextInt(-200, 200).toShort() }
    }

    private fun dualMono(r: MicRequest, ch: Int): ShortArray {
        val rnd = Random(2); val f = r.sampleRate * r.millis / 1000
        val m = ShortArray(f) { rnd.nextInt(-200, 200).toShort() }
        return ShortArray(f * ch) { m[it / ch] }
    }

    /** Drei Klatscher; Kanal 0 hoert [lag] Abtastwerte spaeter. */
    private fun claps(r: MicRequest, ch: Int, lag: Int): ShortArray {
        val f = r.sampleRate * r.millis / 1000; val pcm = noise(r, ch); val rnd = Random(9)
        for (at in listOf(30_000, 80_000, 130_000)) for (i in 0 until 480) {
            val v = (rnd.nextDouble(-1.0, 1.0) * 14_000 * exp(-i / 120.0)).toInt().toShort()
            if (at + i + lag < f) pcm[(at + i + lag) * ch] = v
            pcm[(at + i) * ch + 1] = v
        }
        return pcm
    }

    private fun vm(mic: FakeMicrophoneBoundary = FakeMicrophoneBoundary(), files: FakeAudioFileBoundary = FakeAudioFileBoundary()) =
        MicTestViewModel(MicTestUseCase(mic, files, clock, main))

    private fun stereoMic() = FakeMicrophoneBoundary().apply {
        sound = { r, ch ->
            when {
                r.millis != 4_000 -> if (r.source == AudioSourceKey.UNPROCESSED) dualMono(r, ch) else noise(r, ch)
                else -> claps(r, ch, if (requests.count { it.millis == 4_000 } == 1) 14 else if (requests.count { it.millis == 4_000 } == 2) -14 else 0)
            }
        }
        onRecord = { t += it.millis + 120L }
    }

    @Test fun `S-008 ViewModel fuellt das Ergebnis`() {
        val v = vm(stereoMic())
        v.onPermissionResult(true)
        val s = v.uiState.value
        assertFalse(s.running); assertEquals(MicStepUi.DONE, s.step)
        val r = assertNotNull(s.result)
        assertEquals(SourceUi.VOICE_RECOGNITION, r.clapSource, "UNPROCESSED ist hier doppeltes Mono")
        assertEquals(3, r.mics.size); assertEquals(3, r.devices.size)
        assertEquals(MicTestUseCase.plan(FakeMicrophoneBoundary.defaultInventory()).size, r.sources.size)
        assertFalse(r.sources.single { it.source == SourceUi.UNPROCESSED }.distinct)
        assertEquals(ClapPhaseUi.entries, r.claps.map { it.phase })
        assertEquals(3, r.claps.first().claps.size)
        assertEquals(14_000.0 / 48_000, r.claps.first().claps.first().delayMs, 0.03)
        assertEquals(15.6, r.spacingCm, 0.1)
        assertTrue(r.durationSeconds > 0)
    }

    @Test fun `S-008 ohne Berechtigung kein Lauf`() {
        val mic = FakeMicrophoneBoundary()
        val v = vm(mic)
        v.onPermissionResult(false)
        assertTrue(v.uiState.value.permissionDenied); assertTrue(mic.requests.isEmpty()); assertNull(v.uiState.value.result)
        // Berechtigung im System entzogen, aber Start gedrueckt: der UseCase meldet es
        mic.permission = false
        v.onPermissionResult(true)
        assertTrue(v.uiState.value.permissionDenied); assertTrue(mic.requests.isEmpty())
    }

    @Test fun `S-008 Verlassen bricht ab und gibt frei`() {
        val mic = FakeMicrophoneBoundary(recordDelayMillis = 2_000)
        val v = vm(mic)
        v.onStart()
        assertTrue(v.uiState.value.running)
        assertEquals(1, mic.open)
        v.onStop(); main.scheduler.runCurrent()
        assertFalse(v.uiState.value.running); assertEquals(0, mic.open)
        assertEquals(MicStepUi.IDLE, v.uiState.value.step)
    }

    @Test fun `S-008 Fehler einer Quelle wird angezeigt`() {
        val mic = stereoMic().apply { failures[AudioSourceKey.CAMCORDER] = MicFailure.INIT_FAILED }
        val v = vm(mic); v.onStart()
        val s = v.uiState.value
        compose.setContent { CayResimTheme(dark = true) { MicTestContent(s, {}, {}) } }
        compose.onNodeWithTag("mictest").performScrollToNode(hasTestTag("source_1"))
        compose.onNodeWithText("Aufnahme ließ sich nicht starten", substring = true).assertExists()
    }

    @Test fun mikrotest_ergebnis() {
        val v = vm(stereoMic()); v.onStart()
        val s = v.uiState.value
        compose.setContent { CayResimTheme(dark = true) { MicTestContent(s, {}, {}) } }
        compose.onNodeWithText("Echtes Stereo mit Quelle", substring = true).assertExists()
        compose.onRoot().captureRoboImage("src/test/screenshots/mictest_result.png")
        compose.onNodeWithTag("mictest").performScrollToNode(hasTestTag("clap_0"))
        compose.onNodeWithText("Laufzeit (ms) +0,29", substring = true).assertExists()
        compose.onRoot().captureRoboImage("src/test/screenshots/mictest_claps.png")
    }

    @Test fun mikrotest_ohne_stereo() {
        val mic = FakeMicrophoneBoundary().apply { sound = ::dualMono }
        val v = vm(mic); v.onStart()
        val s = v.uiState.value
        assertNull(s.result?.clapSource)
        compose.setContent { CayResimTheme(dark = false) { MicTestContent(s, {}, {}) } }
        compose.onNodeWithText("Ortung ist so nicht möglich", substring = true).assertExists()
        compose.onRoot().captureRoboImage("src/test/screenshots/mictest_no_stereo.png")
    }

    @Test fun `S-008 unerwarteter Fehler beendet nur den Lauf`() {
        val mic = FakeMicrophoneBoundary().apply { onRecord = { throw IllegalStateException("Treiber") } }
        val v = vm(mic); v.onStart()
        val s = v.uiState.value
        assertFalse(s.running); assertTrue(s.failed)
        compose.setContent { CayResimTheme { MicTestContent(s, {}, {}) } }
        compose.onNodeWithTag("mictest_error").assertExists()
    }

    @Test fun mikrotest_klatschen_ansage() {
        val state = MicTestUiState(running = true, step = MicStepUi.CLAP, clapPhase = ClapPhaseUi.RIGHT, clapSeconds = 4)
        compose.setContent { CayResimTheme(dark = true) { MicTestContent(state, {}, {}) } }
        compose.onNodeWithText("Rechts neben dem Handy klatschen").assertExists()
        compose.onNodeWithTag("mictest_start").assertIsNotEnabled()
        compose.onRoot().captureRoboImage("src/test/screenshots/mictest_clap.png")
    }

    @Test fun mikrotest_fortschritt() {
        val state = MicTestUiState(running = true, step = MicStepUi.SOURCES, sourceIndex = 2, sourceTotal = 8,
            currentSource = SourceRowUi(SourceUi.MIC, DirectionUi.TOWARDS_USER, null, null))
        compose.setContent { CayResimTheme { MicTestContent(state, {}, {}) } }
        compose.onNodeWithText("Aufnahme 3/8: Normal (MIC), Richtung zum Bildschirm").assertExists()
    }

    @Test fun mikrotest_start_und_zurueck() {
        val events = mutableListOf<String>()
        compose.setContent { CayResimTheme { MicTestContent(MicTestUiState(), { events += "start" }, { events += "back" }) } }
        compose.onNodeWithTag("mictest_start").performClick()
        compose.onNodeWithTag("back").performClick()
        assertEquals(listOf("start", "back"), events)
    }

    @Test fun `S-008 Zahlen deutsch und Stille lesbar`() {
        assertEquals("-inf", db(Double.NEGATIVE_INFINITY))
        assertEquals("-60,0", db(-60.0))
        assertEquals("+0,29", signed2(0.2917))
        assertEquals("0,00", app.cayresim.feature.settings.ui.dec2(app.cayresim.feature.settings.ui.noNegativeZero(-0.001)))
    }

    /** Hausregel: keine Gedankenstriche in den neuen Texten. */
    @Test fun texte_ohne_gedankenstriche() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        R.string::class.java.fields.filter { it.name.startsWith("mictest_") || it.name == "settings_mictest_hint" }.forEach { f ->
            val text = ctx.getString(f.getInt(null))
            assertFalse('–' in text || '—' in text, "Gedankenstrich in ${f.name}")
        }
    }
}
