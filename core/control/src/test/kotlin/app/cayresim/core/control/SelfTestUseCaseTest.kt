package app.cayresim.core.control

import app.cayresim.core.boundary.CameraError
import app.cayresim.core.boundary.CaptureFailure
import app.cayresim.core.boundary.CaptureResult
import app.cayresim.core.boundary.PhotoMode
import app.cayresim.core.boundary.fake.FakeCameraBoundary
import app.cayresim.core.boundary.fake.FakeSelfTestJournalBoundary
import kotlin.test.assertFailsWith
import app.cayresim.core.pure.Clock
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SelfTestUseCaseTest {
    private class StepClock : Clock { var t = 0L; override fun nowMillis(): Long { t += 10; return t } }

    @Test fun `Guter Fall prueft jeden Modus und raeumt auf`() = runTest {
        val cam = FakeCameraBoundary(setOf(PhotoMode.NIGHT, PhotoMode.HDR))
        val report = SelfTestUseCase(cam, StepClock(), FakeSelfTestJournalBoundary())()
        assertTrue(report.passed, report.toString())
        val modes = report.items.filter { it.check == SelfTestCheck.MODE_CAPTURE }.map { it.mode }
        assertEquals(listOf(PhotoMode.NORMAL, PhotoMode.NIGHT, PhotoMode.HDR), modes)
        assertEquals(0, cam.saved.size, "Testfotos muessen geloescht sein")
        assertTrue(report.items.filter { it.check == SelfTestCheck.MODE_CAPTURE }.all { it.durationMillis > 0 })
    }

    @Test fun `Kamera startet nicht, Bericht endet rot und frueh`() = runTest {
        val cam = FakeCameraBoundary().apply { startError = CameraError.IN_USE }
        val report = SelfTestUseCase(cam, StepClock(), FakeSelfTestJournalBoundary())()
        assertFalse(report.passed)
        assertEquals(1, report.items.size)
        assertEquals("IN_USE", report.items.single().detail)
    }

    @Test fun `Fehlgeschlagene Aufnahme wird rot gemeldet`() = runTest {
        val cam = FakeCameraBoundary(emptySet()).apply { nextCapture = CaptureResult.Failed(CaptureFailure.STORAGE) }
        val report = SelfTestUseCase(cam, StepClock(), FakeSelfTestJournalBoundary())()
        assertFalse(report.passed)
        assertEquals("STORAGE", report.items.single { it.check == SelfTestCheck.MODE_CAPTURE }.detail)
    }

    @Test fun `Ohne Extensions wird nur NORMAL geprueft`() = runTest {
        val report = SelfTestUseCase(FakeCameraBoundary(emptySet()), StepClock(), FakeSelfTestJournalBoundary())()
        assertTrue(report.passed)
        assertEquals(1, report.items.count { it.check == SelfTestCheck.MODE_CAPTURE })
    }

    @Test fun `Vorheriger Modus wird wiederhergestellt`() = runTest {
        val cam = FakeCameraBoundary(setOf(PhotoMode.HDR)); cam.start(); cam.selectMode(PhotoMode.HDR)
        SelfTestUseCase(cam, StepClock(), FakeSelfTestJournalBoundary())()
        assertEquals(PhotoMode.HDR, cam.state.value.requestedMode)
    }

    // ---------- Neustart des Geraets mitten im Test (Fehler vom S24+, v0.1.27) ----------

    @Test fun `Guter Fall protokolliert jeden Schritt und schliesst ab`() = runTest {
        val journal = FakeSelfTestJournalBoundary()
        SelfTestUseCase(FakeCameraBoundary(setOf(PhotoMode.NIGHT)), StepClock(), journal)()
        assertEquals(listOf("Kamera starten", "Aufnahme NORMAL", "Aufnahme NIGHT", "Aufraeumen"), journal.steps)
        assertEquals(null, journal.unfinished(), "vollstaendiger Lauf hinterlaesst nichts")
    }

    @Test fun `Fehlerfall Neustart wird beim naechsten Lauf rot gemeldet und Testfotos geloescht`() = runTest {
        val cam = FakeCameraBoundary(setOf(PhotoMode.NIGHT, PhotoMode.BOKEH))
        val journal = FakeSelfTestJournalBoundary().apply { crashAtStep = "Aufnahme NIGHT" }
        assertFailsWith<FakeSelfTestJournalBoundary.SimulatedReboot> { SelfTestUseCase(cam, StepClock(), journal)() }
        assertEquals(1, cam.saved.size, "Foto aus NORMAL liegt nach dem Neustart noch da")

        journal.crashAtStep = null
        val report = SelfTestUseCase(cam, StepClock(), journal)()
        val last = report.items.first()
        assertEquals(SelfTestCheck.LAST_RUN, last.check)
        assertFalse(last.passed)
        assertTrue("Aufnahme NIGHT" in last.detail, last.detail)
        assertTrue("1/1" in last.detail, last.detail)
        assertEquals(0, cam.saved.size, "alle Testfotos geloescht, auch die aus dem abgebrochenen Lauf")
    }

    @Test fun `Randfall der abgebrochene Schritt wird genau einmal uebersprungen`() = runTest {
        val cam = FakeCameraBoundary(setOf(PhotoMode.NIGHT, PhotoMode.BOKEH))
        val journal = FakeSelfTestJournalBoundary().apply { crashAtStep = "Aufnahme NIGHT" }
        runCatching { SelfTestUseCase(cam, StepClock(), journal)() }
        journal.crashAtStep = null
        val second = SelfTestUseCase(cam, StepClock(), journal)()
        val night = second.items.single { it.mode == PhotoMode.NIGHT }
        assertFalse(night.passed); assertEquals(0, night.durationMillis)
        assertTrue(second.items.single { it.mode == PhotoMode.BOKEH }.passed, "die Schritte danach laufen weiter")
        val third = SelfTestUseCase(cam, StepClock(), journal)()
        assertTrue(third.passed, "dritter Lauf prueft NIGHT wieder: $third")
    }

    @Test fun `Randfall Neustart beim Kamerastart wird nicht uebersprungen`() = runTest {
        val cam = FakeCameraBoundary(emptySet())
        val journal = FakeSelfTestJournalBoundary().apply { crashAtStep = "Kamera starten" }
        runCatching { SelfTestUseCase(cam, StepClock(), journal)() }
        journal.crashAtStep = null
        val report = SelfTestUseCase(cam, StepClock(), journal)()
        assertTrue(report.items.any { it.check == SelfTestCheck.CAMERA_START && it.passed })
        assertEquals(1, report.items.count { it.check == SelfTestCheck.MODE_CAPTURE && it.passed })
    }

    // ---------- Zeitgrenze je Aufnahme ----------

    /** Uhr, die nur waehrend capture() die angegebene Zeit vergehen laesst. */
    private class CaptureClock(private val cam: FakeCameraBoundary, private val captureMs: Long) : Clock {
        var t = 0L; private var lastCount = -1
        override fun nowMillis(): Long {
            val n = cam.saved.size
            if (lastCount >= 0 && n != lastCount) t += captureMs
            lastCount = n
            return t
        }
    }

    @Test fun `Fehlerfall gespeichert aber langsamer als 5 s ist rot`() = runTest {
        val cam = FakeCameraBoundary(emptySet())
        val report = SelfTestUseCase(cam, CaptureClock(cam, 9_410), FakeSelfTestJournalBoundary())()
        val shot = report.items.single { it.check == SelfTestCheck.MODE_CAPTURE }
        assertFalse(shot.passed); assertFalse(report.passed)
        assertEquals("langsamer als 5 s", shot.detail)
        assertEquals(0, cam.saved.size, "langsames Foto wird trotzdem geloescht")
    }

    @Test fun `Randfall genau 5 s ist noch gruen, 5001 ms rot`() = runTest {
        val ok = FakeCameraBoundary(emptySet())
        assertTrue(SelfTestUseCase(ok, CaptureClock(ok, 5_000), FakeSelfTestJournalBoundary())().passed)
        val slow = FakeCameraBoundary(emptySet())
        assertFalse(SelfTestUseCase(slow, CaptureClock(slow, 5_001), FakeSelfTestJournalBoundary())().passed)
    }

    @Test fun `Guter Fall Werte vom S24+ sind gruen`() = runTest {
        val cam = FakeCameraBoundary(emptySet())
        assertTrue(SelfTestUseCase(cam, CaptureClock(cam, 2_272), FakeSelfTestJournalBoundary())().passed)
    }

    // ---------- Geraetewerte ----------

    @Test fun `Geraetewerte erscheinen als eigene Zeile`() = runTest {
        val cam = FakeCameraBoundary(emptySet()).apply { deviceReport = listOf("Belichtung" to "1/100000 s bis 0,5 s", "ISO" to "50 bis 3200") }
        val report = SelfTestUseCase(cam, StepClock(), FakeSelfTestJournalBoundary())()
        val d = report.items.single { it.check == SelfTestCheck.DEVICE }
        assertTrue(d.passed)
        assertEquals("Belichtung: 1/100000 s bis 0,5 s\nISO: 50 bis 3200", d.detail)
    }

    @Test fun `Randfall ohne Geraetewerte keine leere Zeile`() = runTest {
        val report = SelfTestUseCase(FakeCameraBoundary(emptySet()), StepClock(), FakeSelfTestJournalBoundary())()
        assertTrue(report.items.none { it.check == SelfTestCheck.DEVICE })
    }
}
