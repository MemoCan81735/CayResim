package app.cayresim.core.control

import app.cayresim.core.boundary.BurstFailure
import app.cayresim.core.boundary.ManualCapabilitiesSnapshot
import app.cayresim.core.boundary.StackMode
import app.cayresim.core.boundary.fake.FakeCameraBoundary
import app.cayresim.core.boundary.fake.FakeFrameBoundary
import app.cayresim.core.boundary.fake.FakeManualCameraBoundary
import app.cayresim.core.boundary.fake.FakeProcessingBoundary
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class ProUseCasesTest {
    private val cam = FakeCameraBoundary(); private val proc = FakeProcessingBoundary(); private val frames = FakeFrameBoundary(cam)

    @Test fun `Fokus-Stacking guter Fall`() = runTest {
        cam.start(); val manual = FakeManualCameraBoundary(cam)
        val r = assertIs<StackOutcome.Saved>(FocusStackUseCase(manual, proc)(5))
        assertEquals(5, r.frames); assertEquals(StackMode.FOCUS, proc.stacked.single().second)
    }

    @Test fun `Fokus-Stacking bei Fixfokus wird abgelehnt`() = runTest {
        cam.start(); val manual = FakeManualCameraBoundary(cam, ManualCapabilitiesSnapshot(null, null, 0f, false))
        assertEquals(StackOutcome.Failed(StackOutcome.Stage.COLLECT, "FIXED_FOCUS"), FocusStackUseCase(manual, proc)())
    }

    @Test fun `Fokus-Stacking ohne Kamera`() = runTest {
        assertEquals(StackOutcome.Failed(StackOutcome.Stage.COLLECT, "NOT_READY"), FocusStackUseCase(FakeManualCameraBoundary(cam), proc)())
    }

    @Test fun `Astro setzt lange Belichtung und stellt danach die Automatik wieder her`() = runTest {
        cam.start(); val manual = FakeManualCameraBoundary(cam)
        val r = assertIs<StackOutcome.Saved>(AstroUseCase(manual, frames, proc)(10))
        assertEquals(StackMode.STARS, proc.stacked.single().second); assertEquals(10, r.frames)
        assertEquals(AstroUseCase.MAX_FRAME_EXPOSURE_NS, manual.history.first().exposureNanos)
        assertEquals(1600, manual.history.first().iso)
        assertNull(manual.manualState.value.exposureNanos, "Automatik muss wiederhergestellt sein")
    }

    @Test fun `Astro stellt auch nach Fehler die vorherigen Werte wieder her`() = runTest {
        cam.start(); val manual = FakeManualCameraBoundary(cam); manual.setExposure(5_000_000, 200)
        frames.fail = BurstFailure.TIMEOUT
        assertEquals(StackOutcome.Failed(StackOutcome.Stage.COLLECT, "TIMEOUT"), AstroUseCase(manual, frames, proc)())
        assertEquals(5_000_000L, manual.manualState.value.exposureNanos); assertEquals(200, manual.manualState.value.iso)
    }

    @Test fun `Astro ohne manuelle Belichtung wird abgelehnt`() = runTest {
        cam.start(); val manual = FakeManualCameraBoundary(cam, ManualCapabilitiesSnapshot(null, null, null, false))
        assertEquals(StackOutcome.Failed(StackOutcome.Stage.COLLECT, "NO_MANUAL_EXPOSURE"), AstroUseCase(manual, frames, proc)())
    }

    @Test fun `Astro bei Rechenfehler`() = runTest {
        cam.start(); proc.gpuFails = true
        assertEquals(StackOutcome.Failed(StackOutcome.Stage.PROCESS, "GPU"), AstroUseCase(FakeManualCameraBoundary(cam), frames, proc)())
    }

    @Test fun `Astro begrenzt auf die Moeglichkeiten der Kamera`() = runTest {
        cam.start(); val manual = FakeManualCameraBoundary(cam, ManualCapabilitiesSnapshot(1_000L..100_000_000L, 100..800, null, false))
        AstroUseCase(manual, frames, proc)()
        assertEquals(100_000_000L, manual.history.first().exposureNanos); assertEquals(800, manual.history.first().iso)
    }

    @Test fun `Astro Abbruch stellt die Automatik trotzdem wieder her`() = runTest {
        cam.start(); val manual = FakeManualCameraBoundary(cam)
        val slowFrames = object : app.cayresim.core.boundary.FrameBoundary by frames {
            override suspend fun collect(count: Int): app.cayresim.core.boundary.BurstResult { kotlinx.coroutines.delay(5_000); return frames.collect(count) }
        }
        val job = launch { AstroUseCase(manual, slowFrames, proc)(10) }
        advanceTimeBy(1_000)
        job.cancel(); job.join()
        assertNull(manual.manualState.value.exposureNanos)
    }
}
