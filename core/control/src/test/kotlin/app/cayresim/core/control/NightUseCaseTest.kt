package app.cayresim.core.control

import app.cayresim.core.boundary.LightSnapshot
import app.cayresim.core.boundary.ManualCapabilitiesSnapshot
import app.cayresim.core.boundary.fake.FakeCameraBoundary
import app.cayresim.core.boundary.fake.FakeFrameBoundary
import app.cayresim.core.boundary.fake.FakeManualCameraBoundary
import app.cayresim.core.boundary.fake.FakeProcessingBoundary
import kotlinx.coroutines.test.runTest
import app.cayresim.core.pure.NightPlan
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.onEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NightUseCaseTest {
    private val cam = FakeCameraBoundary(); private val proc = FakeProcessingBoundary(); private val frames = FakeFrameBoundary(cam)
    /** Wie das S24+: hoechstens 1/10 s, ISO 25 bis 3200. */
    private val manual = FakeManualCameraBoundary(cam, ManualCapabilitiesSnapshot(85_000L..100_000_000L, 25..3200, 10f, raw = true))
    private val night = NightUseCase(cam, manual, frames, proc)

    @Test fun `Guter Fall dunkel nutzt den eigenen Kern mit 1 durch 10 s und hoher ISO`() = runTest {
        cam.start(); cam.measure(LightSnapshot(66_666_666, 3200))
        assertTrue(night.shouldUseOwn())
        val r = assertIs<StackOutcome.Saved>(night(20))
        assertEquals(listOf(20), proc.nightRuns, "Messbilder und Einschwingbilder gehen nicht in die Verarbeitung")
        assertEquals(100_000_000L, manual.history.first().exposureNanos)
        assertEquals(3200, manual.history.first().iso)
        assertEquals(3200, r.night!!.iso); assertEquals(100_000_000L, r.night!!.exposureNs); assertEquals(20, r.night!!.used)
        kotlin.test.assertFalse(r.shortened)
    }

    @Test fun `Danach ist die Automatik wieder da`() = runTest {
        cam.start(); cam.measure(LightSnapshot(66_666_666, 3200)); night(10)
        assertNull(manual.manualState.value.exposureNanos)
    }

    @Test fun `Vorherige manuelle Werte bleiben erhalten`() = runTest {
        cam.start(); manual.setExposure(20_000_000, 400); night(10)
        assertEquals(20_000_000L, manual.manualState.value.exposureNanos); assertEquals(400, manual.manualState.value.iso)
    }

    @Test fun `Randfall hell gemessen nutzt Samsungs Modus`() = runTest {
        cam.start(); cam.measure(LightSnapshot(5_000_000, 50))
        assertFalse(night.shouldUseOwn())
    }

    @Test fun `Randfall ohne Messung nutzt den eigenen Kern`() = runTest {
        cam.start(); assertTrue(night.shouldUseOwn())
        assertIs<StackOutcome.Saved>(night(5))
        assertEquals(3200, manual.history.first().iso)
    }

    @Test fun `Fehlerfall Kamera ohne manuelle Belichtung`() = runTest {
        cam.start()
        val noManual = NightUseCase(cam, FakeManualCameraBoundary(cam, ManualCapabilitiesSnapshot(null, null, 0f, false)), frames, proc)
        assertEquals(StackOutcome.Failed(StackOutcome.Stage.COLLECT, "NO_MANUAL_EXPOSURE"), noManual())
    }

    @Test fun `Fehlerfall Kamera laeuft nicht, kein Bild, Automatik bleibt`() = runTest {
        val r = night(10)
        assertEquals(StackOutcome.Failed(StackOutcome.Stage.PROCESS, "INVALID_INPUT"), r)
        assertNull(manual.manualState.value.exposureNanos)
    }

    @Test fun `Fehlerfall Waerme laesst nur die Messbilder zu`() = runTest {
        cam.start(); frames.allowed = NightUseCase.METER + NightUseCase.SETTLE
        assertIs<StackOutcome.Failed>(night(20))
        assertNull(manual.manualState.value.exposureNanos)
    }

    @Test fun `Fehlerfall Verarbeitung scheitert, Automatik kommt trotzdem zurueck`() = runTest {
        cam.start(); proc.gpuFails = true
        assertEquals(StackOutcome.Failed(StackOutcome.Stage.PROCESS, "GPU"), night(5))
        assertNull(manual.manualState.value.exposureNanos)
    }

    // ---------- Abbruch (Befund C1 der Architekturpruefung) ----------

    @Test fun `Fehlerfall Abbruch mitten in der Serie stellt die Automatik trotzdem wieder her`() = runTest {
        cam.start(); cam.measure(LightSnapshot(66_666_666, 3200))
        val slow = object : app.cayresim.core.boundary.ProcessingBoundary by proc {
            override suspend fun night(frames: kotlinx.coroutines.flow.Flow<app.cayresim.core.boundary.Frame>) =
                proc.night(frames.onEach { kotlinx.coroutines.delay(1_000) })
        }
        val job = launch { NightUseCase(cam, manual, frames, slow)(20) }
        advanceTimeBy(10_500) // mitten in der Serie: Nachtbelichtung ist gesetzt
        assertEquals(100_000_000L, manual.manualState.value.exposureNanos)
        job.cancel(); job.join()
        assertNull(manual.manualState.value.exposureNanos, "Nachtbelichtung blieb nach dem Abbruch stehen")
    }

    @Test fun `M6 Waerme kuerzt die Serie, das Ergebnis meldet es ehrlich`() = runTest {
        cam.start(); cam.measure(LightSnapshot(66_666_666, 3200))
        frames.allowed = NightUseCase.METER + NightUseCase.SETTLE + 8
        val r = assertIs<StackOutcome.Saved>(night(20))
        assertEquals(8, r.frames); assertTrue(r.shortened)
    }

    @Test fun `M6 laesst sich die Belichtung nicht setzen, meldet das Ergebnis die Automatik`() = runTest {
        cam.start()
        val stubborn = object : app.cayresim.core.boundary.ManualCameraBoundary by manual {
            override suspend fun setExposure(nanos: Long?, iso: Int?): Boolean = if (nanos == null) manual.setExposure(null, null) else false
        }
        val r = assertIs<StackOutcome.Saved>(NightUseCase(cam, stubborn, frames, proc)(10))
        assertNull(r.night!!.exposureNs); assertNull(r.night!!.iso)
    }

    // ---------- RAW-Nachtweg (Wahl aus dem Selbsttest) ----------

    private fun rawNight(stored: app.cayresim.core.pure.NightPath) =
        NightUseCase(cam, manual, frames, proc, app.cayresim.core.boundary.fake.FakeNightPathBoundary(app.cayresim.core.boundary.NightPathSnapshot(stored)))

    @Test fun `Guter Fall gespeichert RAW nutzt den RAW-Strom mit geplanter Belichtung`() = runTest {
        cam.start(); cam.measure(LightSnapshot(39_990_000, 3200))
        val r = assertIs<StackOutcome.Saved>(rawNight(app.cayresim.core.pure.NightPath.RAW)(36))
        assertEquals(listOf(36), proc.rawNightRuns); assertTrue(proc.nightRuns.isEmpty(), "kein 8-Bit-Weg")
        assertEquals(Triple(36, 100_000_000L, 3200), frames.rawCalls.single())
        assertTrue(r.night!!.raw); assertEquals(3200, r.night!!.iso)
        assertTrue(manual.history.isEmpty(), "RAW-Sitzung setzt die Belichtung selbst, die manuellen Werte bleiben unberuehrt")
    }

    @Test fun `Fehlerfall RAW scheitert, der 8-Bit-Weg springt ein`() = runTest {
        cam.start(); cam.measure(LightSnapshot(66_666_666, 3200)); proc.rawFails = true
        val r = assertIs<StackOutcome.Saved>(rawNight(app.cayresim.core.pure.NightPath.RAW)(20))
        assertEquals(listOf(20), proc.nightRuns); assertFalse(r.night!!.raw)
    }

    @Test fun `Randfall gespeichert 8 Bit oder nichts gespeichert nutzt nie RAW`() = runTest {
        cam.start(); cam.measure(LightSnapshot(66_666_666, 3200))
        rawNight(app.cayresim.core.pure.NightPath.YUV)(10); night(10)
        assertTrue(frames.rawCalls.isEmpty()); assertEquals(listOf(10, 10), proc.nightRuns)
    }

    @Test fun `Bericht nennt die Messung der Automatik`() = runTest {
        // S-002 K3: ohne diese Werte ist eine Ueberbelichtung auf dem Geraet nicht nachweisbar
        cam.start(); cam.measure(LightSnapshot(50_000_000, 640))
        val r = assertIs<StackOutcome.Saved>(night(10))
        assertEquals(50_000_000L, r.night!!.meterExposureNs); assertEquals(640, r.night!!.meterIso)
        assertEquals(1280, r.night!!.iso, "hoechstens 4-mal so hell wie die Automatik")
        val raw = assertIs<StackOutcome.Saved>(rawNight(app.cayresim.core.pure.NightPath.RAW)(10))
        assertEquals(50_000_000L, raw.night!!.meterExposureNs); assertEquals(640, raw.night!!.meterIso)
    }

    @Test fun `Nachttest S24+ tiefe Dunkelheit nimmt 72 Bilder, maessige 36`() = runTest {
        // S-006: Automatik 1/25 s bei ISO 3200 (10. Oktober, Vorhang): Samsung belichtete bis 8 s, wir bisher 3,6 s
        cam.start(); cam.measure(LightSnapshot(40_000_000, 3200))
        val deep = assertIs<StackOutcome.Saved>(night())
        assertEquals(listOf(NightPlan.FRAMES_DEEP), proc.nightRuns); assertEquals(72, deep.frames); kotlin.test.assertFalse(deep.shortened)
        cam.measure(LightSnapshot(33_333_333, 800)) // Lichtwert 27: dunkel, aber nicht tief
        night()
        assertEquals(listOf(72, NightPlan.FRAMES), proc.nightRuns)
    }

    @Test fun `Bericht sagt, wenn das Wackeln nicht messbar ist`() = runTest {
        cam.start(); cam.measure(LightSnapshot(66_666_666, 3200)); proc.nightShakeMeasurable = false
        assertFalse(assertIs<StackOutcome.Saved>(night(10)).night!!.shakeMeasurable)
    }

    @Test fun `S-007 Diagnose kommt im Bericht an`() = runTest {
        val d = app.cayresim.core.pure.NightDiagnosis(true, false, 0.01f, 0.004f, 0.002f, 0.001f, 0.6f, 0.55f, 0.7f, 0.41f, 12f, 11f, 15f)
        cam.start(); cam.measure(LightSnapshot(66_666_666, 3200)); proc.nightDiagnosis = d
        assertEquals(d, assertIs<StackOutcome.Saved>(night(10)).night!!.diagnosis)
    }

    @Test fun `Bericht nennt das Wackeln`() = runTest {
        // S-003 K2
        cam.start(); cam.measure(LightSnapshot(66_666_666, 3200)); proc.nightShake = 12
        assertEquals(12, assertIs<StackOutcome.Saved>(night(10)).night!!.shakePx)
    }

    @Test fun `Randfall ohne Messung der Automatik bleibt der Bericht ohne Automatik`() = runTest {
        cam.start()
        val r = assertIs<StackOutcome.Saved>(night(10))
        assertNull(r.night!!.meterExposureNs); assertNull(r.night!!.meterIso)
    }

    /** Uhr, die mit den gelieferten Bildern laeuft: 100 ms je Bild (8 Bit und RAW). */
    private val frameClock = app.cayresim.core.pure.Clock { (frames.streamed + frames.rawStreamed) * 100L }

    @Test fun `Dauer wird gemessen vom Ausloesen bis gespeichert`() = runTest {
        val timed = NightUseCase(cam, manual, frames, proc, null, frameClock)
        cam.start(); cam.measure(LightSnapshot(66_666_666, 3200))
        val r = assertIs<StackOutcome.Saved>(timed(10))
        // Messbilder und Einschwingbilder zaehlen mit: die Dauer beginnt beim Ausloesen
        assertEquals((NightUseCase.METER + NightUseCase.SETTLE + 10) * 100L, r.night!!.durationMs)
        assertNull(assertIs<StackOutcome.Saved>(night(10)).night!!.durationMs, "ohne Uhr keine Dauer")
    }

    @Test fun `Dauer wird auch im RAW-Weg gemessen`() = runTest {
        val timed = NightUseCase(cam, manual, frames, proc,
            app.cayresim.core.boundary.fake.FakeNightPathBoundary(app.cayresim.core.boundary.NightPathSnapshot(app.cayresim.core.pure.NightPath.RAW)), frameClock)
        cam.start(); cam.measure(LightSnapshot(66_666_666, 3200))
        val r = assertIs<StackOutcome.Saved>(timed(10))
        assertTrue(r.night!!.raw); assertEquals(1_000L, r.night!!.durationMs)
    }

    @Test fun `Dauer nach gescheitertem RAW enthaelt den RAW-Versuch`() = runTest {
        val timed = NightUseCase(cam, manual, frames, proc,
            app.cayresim.core.boundary.fake.FakeNightPathBoundary(app.cayresim.core.boundary.NightPathSnapshot(app.cayresim.core.pure.NightPath.RAW)), frameClock)
        cam.start(); cam.measure(LightSnapshot(66_666_666, 3200)); proc.rawFails = true
        val r = assertIs<StackOutcome.Saved>(timed(10))
        assertFalse(r.night!!.raw)
        assertEquals((10 + NightUseCase.METER + NightUseCase.SETTLE + 10) * 100L, r.night!!.durationMs)
    }

    // ---------- S-011: Nachtserie zum Nachmessen ----------

    private fun motion() = app.cayresim.core.boundary.fake.FakeMotionSensorBoundary(samples = List(5) { i ->
        app.cayresim.core.boundary.MotionSample(app.cayresim.core.boundary.MotionKind.ROTATION, 1_000_000_000L + i * 10_000_000L, 1f, 0f, 0f, 0f)
    })

    @Test fun `S-011 Serie aus und an`() = runTest {
        cam.start(); cam.measure(LightSnapshot(66_666_666, 3200))
        val archive = app.cayresim.core.boundary.fake.FakeSeriesArchiveBoundary()
        val sensors = motion()
        // aus: kein Archiv, kein Sensor
        val off = assertIs<StackOutcome.Saved>(NightUseCase(cam, manual, frames, proc, null, null, archive, app.cayresim.core.boundary.fake.FakeDebugOptionsBoundary(false), sensors)(10))
        assertTrue(archive.sessions.isEmpty()); assertEquals(0, sensors.registrations); assertNull(off.night!!.seriesName)
        // an
        val debug = app.cayresim.core.boundary.fake.FakeDebugOptionsBoundary(true)
        val r = assertIs<StackOutcome.Saved>(NightUseCase(cam, manual, frames, proc, null, null, archive, debug, sensors)(10))
        val s = archive.sessions.single()
        assertTrue(s.finished)
        val first = NightUseCase.METER + NightUseCase.SETTLE // erste Nummer nach Messen und Einschwingen
        val yNames = s.entries.keys.filter { it.startsWith("y-") }
        assertEquals((0 until 10).map { app.cayresim.core.pure.NightSeries.lumaName(it) }, yNames, "jedes Bild in Reihenfolge")
        yNames.forEachIndexed { i, n -> assertTrue(s.entries.getValue(n).all { it.toInt() == first + i }, "Helligkeit Bild $i") }
        assertTrue(s.entries.getValue(app.cayresim.core.pure.NightSeries.RGB_FIRST).all { it.toInt() == first })
        assertTrue(s.entries.getValue(app.cayresim.core.pure.NightSeries.RGB_MID).all { it.toInt() == first + 5 })
        assertTrue(s.entries.getValue(app.cayresim.core.pure.NightSeries.RGB_LAST).all { it.toInt() == first + 9 })
        val meta = String(s.entries.getValue(app.cayresim.core.pure.NightSeries.META))
        assertTrue("\"format\": 1" in meta && "\"used\": 10" in meta && "\"dx\": 9" in meta && "\"timestampNs\": ${1_000_000_000L + (first + 9) * 100_000_000L}" in meta, meta)
        assertTrue("\"exposureNs\": 100000000" in meta && "\"meterExposureNs\": 66666666" in meta, meta)
        val lage = String(s.entries.getValue(app.cayresim.core.pure.NightSeries.LAGE))
        assertTrue(lage.startsWith("format=v1") && lage.lines().count { it.startsWith("ROTATION,") } == 5, lage)
        assertEquals(0, sensors.active, "Sensor abgemeldet")
        assertEquals(s.name, r.night!!.seriesName); assertTrue(r.night!!.seriesBytes!! > 0); assertFalse(r.night!!.seriesFailed)
    }

    @Test fun `S-011 Abbruch und Speicherfehler`() = runTest {
        cam.start(); cam.measure(LightSnapshot(66_666_666, 3200))
        val debug = app.cayresim.core.boundary.fake.FakeDebugOptionsBoundary(true)
        // Abbruch waehrend der Serie: halbe Datei weg, Sensor abgemeldet, Automatik zurueck
        val archive = app.cayresim.core.boundary.fake.FakeSeriesArchiveBoundary()
        val sensors = motion()
        val slow = object : app.cayresim.core.boundary.ProcessingBoundary by proc {
            override suspend fun night(frames: kotlinx.coroutines.flow.Flow<app.cayresim.core.boundary.Frame>) =
                proc.night(frames.onEach { kotlinx.coroutines.delay(1_000) })
        }
        val job = launch { NightUseCase(cam, manual, frames, slow, null, null, archive, debug, sensors)(20) }
        advanceTimeBy(10_500)
        assertEquals(1, sensors.active, "Sensor laeuft waehrend der Serie")
        job.cancel(); job.join()
        val s = archive.sessions.single()
        assertTrue(s.aborted && !s.finished, "halbe Datei geloescht")
        assertEquals(0, sensors.active); assertNull(manual.manualState.value.exposureNanos)
        // Schreibfehler: Nachtbild trotzdem gespeichert, Hinweis "nicht gespeichert"
        val failing = app.cayresim.core.boundary.fake.FakeSeriesArchiveBoundary().apply { failPutAfter = 3 }
        val r = assertIs<StackOutcome.Saved>(NightUseCase(cam, manual, frames, proc, null, null, failing, debug, motion())(10))
        assertTrue(r.night!!.seriesFailed); assertNull(r.night!!.seriesName)
        assertTrue(failing.sessions.single().aborted)
        // Datei laesst sich nicht anlegen: ebenso
        val noOpen = app.cayresim.core.boundary.fake.FakeSeriesArchiveBoundary().apply { failOpen = true }
        val r2 = assertIs<StackOutcome.Saved>(NightUseCase(cam, manual, frames, proc, null, null, noOpen, debug, motion())(10))
        assertTrue(r2.night!!.seriesFailed)
        // Verarbeitung scheitert: Archiv abgebrochen
        val archive3 = app.cayresim.core.boundary.fake.FakeSeriesArchiveBoundary()
        proc.gpuFails = true
        assertIs<StackOutcome.Failed>(NightUseCase(cam, manual, frames, proc, null, null, archive3, debug, motion())(10))
        assertTrue(archive3.sessions.single().aborted)
        proc.gpuFails = false
    }
}
