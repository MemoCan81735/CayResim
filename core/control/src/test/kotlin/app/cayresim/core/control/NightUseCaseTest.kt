package app.cayresim.core.control

import app.cayresim.core.boundary.LightSnapshot
import app.cayresim.core.boundary.ManualCapabilitiesSnapshot
import app.cayresim.core.boundary.fake.FakeCameraBoundary
import app.cayresim.core.boundary.fake.FakeFrameBoundary
import app.cayresim.core.boundary.fake.FakeManualCameraBoundary
import app.cayresim.core.boundary.fake.FakeProcessingBoundary
import kotlinx.coroutines.test.runTest
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
        assertTrue(r.info!!.contains("ISO 3200"), r.info)
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
}
