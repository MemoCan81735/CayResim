package app.cayresim.core.control

import app.cayresim.core.boundary.CameraError
import app.cayresim.core.boundary.CaptureFailure
import app.cayresim.core.boundary.CaptureResult
import app.cayresim.core.boundary.OisState
import app.cayresim.core.boundary.PhotoMode
import app.cayresim.core.boundary.fake.FakeCameraBoundary
import app.cayresim.core.boundary.fake.FakeManualCameraBoundary
import app.cayresim.core.boundary.RawProbeFailure
import app.cayresim.core.boundary.RawProbeResult
import app.cayresim.core.boundary.ManualCapabilitiesSnapshot
import app.cayresim.core.boundary.fake.FakeSelfTestJournalBoundary
import kotlin.test.assertFailsWith
import app.cayresim.core.pure.Clock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch
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
        val dev = app.cayresim.core.boundary.DeviceReport(exposureNs = 85_000L..100_000_000L, iso = 25..3200)
        val cam = FakeCameraBoundary(emptySet()).apply { deviceReport = dev }
        val report = SelfTestUseCase(cam, StepClock(), FakeSelfTestJournalBoundary())()
        val d = report.items.single { it.check == SelfTestCheck.DEVICE }
        assertTrue(d.passed)
        assertEquals(dev, d.device)
    }

    @Test fun `Randfall ohne Geraetewerte keine leere Zeile`() = runTest {
        val report = SelfTestUseCase(FakeCameraBoundary(emptySet()), StepClock(), FakeSelfTestJournalBoundary())()
        assertTrue(report.items.none { it.check == SelfTestCheck.DEVICE })
    }

    // ---------- RAW-Serie (Schritt 0 des RAW-Wegs) ----------

    @Test fun `Guter Fall RAW-Serie wird gemessen und typisiert gemeldet`() = runTest {
        val cam = FakeCameraBoundary(setOf(PhotoMode.NIGHT)); cam.start(); cam.selectMode(PhotoMode.NIGHT)
        val manual = FakeManualCameraBoundary(cam)
        val journal = FakeSelfTestJournalBoundary()
        val report = SelfTestUseCase(cam, StepClock(), journal, manual)()
        val item = report.items.single { it.check == SelfTestCheck.RAW_SERIES }
        assertTrue(item.passed, item.toString())
        assertEquals((manual.rawProbe as RawProbeResult.Ok).probe, item.rawProbe)
        assertEquals(listOf(Triple(8, 100_000_000L, 3200)), manual.probeCalls)
        assertTrue("RAW-Serie" in journal.steps, "Schritt wird vorher protokolliert (Neustart-Schutz)")
        assertEquals(PhotoMode.NIGHT, cam.state.value.requestedMode, "vorheriger Modus wiederhergestellt")
    }

    @Test fun `Fehlerfall RAW zu langsam oder gescheitert ist rot`() = runTest {
        val cam = FakeCameraBoundary(emptySet())
        val slow = FakeManualCameraBoundary(cam).apply {
            rawProbe = RawProbeResult.Ok((rawProbe as RawProbeResult.Ok).probe.copy(avgFrameMs = 1_500))
        }
        assertFalse(SelfTestUseCase(cam, StepClock(), FakeSelfTestJournalBoundary(), slow)().items.single { it.check == SelfTestCheck.RAW_SERIES }.passed)
        val failed = FakeManualCameraBoundary(cam).apply { rawProbe = RawProbeResult.Failed(RawProbeFailure.TIMEOUT) }
        val item = SelfTestUseCase(cam, StepClock(), FakeSelfTestJournalBoundary(), failed)().items.single { it.check == SelfTestCheck.RAW_SERIES }
        assertFalse(item.passed); assertEquals("TIMEOUT", item.detail)
    }

    @Test fun `Randfall ohne RAW entfaellt der Schritt`() = runTest {
        val cam = FakeCameraBoundary(emptySet())
        val noRaw = FakeManualCameraBoundary(cam, ManualCapabilitiesSnapshot(100_000L..2_000_000_000L, 50..3200, 10f, raw = false))
        val report = SelfTestUseCase(cam, StepClock(), FakeSelfTestJournalBoundary(), noRaw)()
        assertTrue(report.items.none { it.check == SelfTestCheck.RAW_SERIES }); assertTrue(noRaw.probeCalls.isEmpty())
    }

    @Test fun `Fehlerfall Neustart bei der RAW-Serie wird einmal uebersprungen`() = runTest {
        val cam = FakeCameraBoundary(emptySet()); val manual = FakeManualCameraBoundary(cam)
        val journal = FakeSelfTestJournalBoundary().apply { crashAtStep = "RAW-Serie" }
        runCatching { SelfTestUseCase(cam, StepClock(), journal, manual)() }
        journal.crashAtStep = null
        val second = SelfTestUseCase(cam, StepClock(), journal, manual)()
        val item = second.items.single { it.check == SelfTestCheck.RAW_SERIES }
        assertFalse(item.passed); assertTrue(manual.probeCalls.isEmpty(), "nicht sofort wiederholen")
        assertTrue(SelfTestUseCase(cam, StepClock(), journal, manual)().items.single { it.check == SelfTestCheck.RAW_SERIES }.passed)
    }

    // ---------- Nachtweg: Pruefung auf dem Geraet entscheidet ----------

    private class Rig(rawOk: Boolean = true) {
        val cam = FakeCameraBoundary(emptySet())
        val manual = FakeManualCameraBoundary(cam)
        val frames = app.cayresim.core.boundary.fake.FakeFrameBoundary(cam)
        val proc = app.cayresim.core.boundary.fake.FakeProcessingBoundary().apply { rawFails = !rawOk; onRawSaved = { cam.adopt(it) } }
        val store = app.cayresim.core.boundary.fake.FakeNightPathBoundary()
        fun run(clock: Clock = StepClock(), journal: FakeSelfTestJournalBoundary = FakeSelfTestJournalBoundary()) =
            SelfTestUseCase(cam, clock, journal, manual, frames, proc, store)
    }

    @Test fun `Guter Fall Messung und Probenacht gut, RAW wird gewaehlt und gespeichert`() = runTest {
        val rig = Rig()
        val report = rig.run()()
        val item = report.items.single { it.check == SelfTestCheck.NIGHT_PATH }
        assertEquals(app.cayresim.core.pure.NightPath.RAW, item.nightPath!!.path)
        assertEquals(app.cayresim.core.pure.NightPath.RAW, rig.store.stored.path)
        assertEquals(listOf(12), rig.proc.rawNightRuns, "Probenacht mit 12 RAW-Bildern")
        assertTrue(report.passed, report.toString()); assertEquals(0, rig.cam.saved.size, "Probefoto geloescht")
    }

    @Test fun `Fehlerfall Probenacht scheitert, 8 Bit mit Grund`() = runTest {
        val rig = Rig(rawOk = false)
        val item = rig.run()().items.single { it.check == SelfTestCheck.NIGHT_PATH }
        assertEquals(app.cayresim.core.pure.NightPathRule.Reason.PROBE_FAILED, item.nightPath!!.reason)
        assertEquals(app.cayresim.core.pure.NightPath.YUV, rig.store.stored.path)
    }

    @Test fun `Fehlerfall Bildstrom zu langsam, keine Probenacht`() = runTest {
        val rig = Rig()
        rig.manual.rawProbe = RawProbeResult.Ok((rig.manual.rawProbe as RawProbeResult.Ok).probe.copy(streamFps = 3f))
        val item = rig.run()().items.single { it.check == SelfTestCheck.NIGHT_PATH }
        assertEquals(app.cayresim.core.pure.NightPathRule.Reason.SLOW_STREAM, item.nightPath!!.reason)
        assertTrue(rig.proc.rawNightRuns.isEmpty())
    }

    @Test fun `Geraetewerte nennen den Stabilisator`() = runTest {
        // S-003 K4: angeboten laut Geraet, aktiv laut letzter Aufnahme
        val cam = FakeCameraBoundary().apply { deviceReport = app.cayresim.core.boundary.DeviceReport(ois = true); stabilize(OisState.ON) }
        val d = SelfTestUseCase(cam, StepClock(), FakeSelfTestJournalBoundary())().items.single { it.check == SelfTestCheck.DEVICE }.device!!
        assertEquals(true, d.ois); assertEquals(OisState.ON, d.oisActive)
        // S-006: der Wert kommt erst mit dem ersten Aufnahmeergebnis; der Selbsttest wartet darauf
        val late = FakeCameraBoundary().apply { deviceReport = app.cayresim.core.boundary.DeviceReport(ois = true) }
        val job = launch { kotlinx.coroutines.delay(500); late.stabilize(OisState.ON) }
        assertEquals(OisState.ON, SelfTestUseCase(late, StepClock(), FakeSelfTestJournalBoundary())().items.single { it.check == SelfTestCheck.DEVICE }.device!!.oisActive)
        job.join()
        val unknown = FakeCameraBoundary().apply { deviceReport = app.cayresim.core.boundary.DeviceReport(ois = true) }
        assertEquals(null, SelfTestUseCase(unknown, StepClock(), FakeSelfTestJournalBoundary())().items.single { it.check == SelfTestCheck.DEVICE }.device!!.oisActive)
    }

    @Test fun `S-007 Stabilisator nicht gemeldet`() = runTest {
        // Geraet 10.10.: Aufnahmeergebnis ohne Stabilisator-Wert; das ist ein Ergebnis, kein Warten bis zum Ende
        val cam = FakeCameraBoundary().apply { deviceReport = app.cayresim.core.boundary.DeviceReport(ois = true); stabilize(OisState.NOT_REPORTED) }
        assertEquals(OisState.NOT_REPORTED, SelfTestUseCase(cam, StepClock(), FakeSelfTestJournalBoundary())().items.single { it.check == SelfTestCheck.DEVICE }.device!!.oisActive)
    }

    @Test fun `Fehlerfall Schwarzwert 0 ergibt 8 Bit ohne Probenacht, auch ohne Nullen`() = runTest {
        // S-002 K4: S24+ meldet "Schwarz: 0/0/0/0"; im hellen Raum 0,0 % Nullen
        val rig = Rig()
        rig.manual.rawProbe = RawProbeResult.Ok((rig.manual.rawProbe as RawProbeResult.Ok).probe.copy(blackLevel = listOf(0, 0, 0, 0), zeroShare = 0f))
        val item = rig.run()().items.single { it.check == SelfTestCheck.NIGHT_PATH }
        assertEquals(app.cayresim.core.pure.NightPathRule.Reason.CLIPPED, item.nightPath!!.reason)
        assertEquals(app.cayresim.core.pure.NightPath.YUV, rig.store.stored.path)
        assertTrue(rig.proc.rawNightRuns.isEmpty(), "keine Probenacht")
    }

    @Test fun `Fehlerfall Probenacht zu langsam ergibt 8 Bit`() = runTest {
        val rig = Rig()
        // Uhr springt waehrend der Probenacht um 7 s
        val clock = object : Clock { var t = 0L; override fun nowMillis(): Long { t += if (rig.frames.rawCalls.isNotEmpty() && t < 7_000) 7_000 else 10; return t } }
        val item = rig.run(clock)().items.single { it.check == SelfTestCheck.NIGHT_PATH }
        assertEquals(app.cayresim.core.pure.NightPathRule.Reason.PROBE_SLOW, item.nightPath!!.reason)
    }

    @Test fun `Fehlerfall Neustart bei der Probenacht wird einmal uebersprungen`() = runTest {
        val rig = Rig()
        val journal = FakeSelfTestJournalBoundary().apply { crashAtStep = "RAW-Nacht" }
        runCatching { rig.run(journal = journal)() }
        journal.crashAtStep = null
        val item = rig.run(journal = journal)().items.single { it.check == SelfTestCheck.NIGHT_PATH }
        assertEquals(app.cayresim.core.pure.NightPath.YUV, item.nightPath!!.path)
        assertTrue(rig.proc.rawNightRuns.isEmpty(), "nach einem Absturz nicht sofort wiederholen")
    }

    @Test fun `Randfall ohne RAW-Faehigkeit 8 Bit ohne Probenacht`() = runTest {
        val rig = Rig()
        val noRaw = FakeManualCameraBoundary(rig.cam, ManualCapabilitiesSnapshot(100_000L..2_000_000_000L, 50..3200, 10f, raw = false))
        val item = SelfTestUseCase(rig.cam, StepClock(), FakeSelfTestJournalBoundary(), noRaw, rig.frames, rig.proc, rig.store)()
            .items.single { it.check == SelfTestCheck.NIGHT_PATH }
        assertEquals(app.cayresim.core.pure.NightPathRule.Reason.NO_RAW, item.nightPath!!.reason)
    }
}
