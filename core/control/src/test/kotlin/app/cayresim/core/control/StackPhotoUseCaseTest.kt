package app.cayresim.core.control

import app.cayresim.core.boundary.BurstFailure
import app.cayresim.core.boundary.StackMode
import app.cayresim.core.boundary.fake.FakeCameraBoundary
import app.cayresim.core.boundary.fake.FakeFrameBoundary
import app.cayresim.core.boundary.fake.FakeProcessingBoundary
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StackPhotoUseCaseTest {
    private val cam = FakeCameraBoundary(); private val frames = FakeFrameBoundary(cam); private val proc = FakeProcessingBoundary()
    private val uc = StackPhotoUseCase(frames, proc)

    @Test fun `Guter Fall Median`() = runTest {
        cam.start()
        val r = assertIs<StackOutcome.Saved>(uc(StackMode.MEDIAN))
        assertEquals(StackPhotoUseCase.DEFAULT_FRAMES, r.frames); assertFalse(r.shortened)
        assertEquals(StackPhotoUseCase.DEFAULT_FRAMES to StackMode.MEDIAN, proc.stacked.single())
    }

    @Test fun `Waermebudget kuerzt und wird gemeldet`() = runTest {
        cam.start(); frames.allowed = 5
        val r = assertIs<StackOutcome.Saved>(uc(StackMode.MEAN))
        assertEquals(5, r.frames); assertTrue(r.shortened)
    }

    @Test fun `Kamera aus, Sammeln scheitert`() = runTest {
        assertEquals(StackOutcome.Failed(StackOutcome.Stage.COLLECT, "NOT_READY"), uc(StackMode.MEDIAN))
    }

    @Test fun `Zeitueberschreitung beim Sammeln`() = runTest {
        cam.start(); frames.fail = BurstFailure.TIMEOUT
        assertEquals(StackOutcome.Failed(StackOutcome.Stage.COLLECT, "TIMEOUT"), uc(StackMode.MEDIAN))
    }

    @Test fun `Rechnung scheitert`() = runTest {
        cam.start(); proc.gpuFails = true
        assertEquals(StackOutcome.Failed(StackOutcome.Stage.PROCESS, "GPU"), uc(StackMode.MEDIAN))
    }
}
