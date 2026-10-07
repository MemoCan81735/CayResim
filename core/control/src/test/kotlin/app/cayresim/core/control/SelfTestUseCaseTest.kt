package app.cayresim.core.control

import app.cayresim.core.boundary.CameraError
import app.cayresim.core.boundary.CaptureFailure
import app.cayresim.core.boundary.CaptureResult
import app.cayresim.core.boundary.PhotoMode
import app.cayresim.core.boundary.fake.FakeCameraBoundary
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
        val report = SelfTestUseCase(cam, StepClock())()
        assertTrue(report.passed, report.toString())
        val modes = report.items.filter { it.check == SelfTestCheck.MODE_CAPTURE }.map { it.mode }
        assertEquals(listOf(PhotoMode.NORMAL, PhotoMode.NIGHT, PhotoMode.HDR), modes)
        assertEquals(0, cam.saved.size, "Testfotos muessen geloescht sein")
        assertTrue(report.items.filter { it.check == SelfTestCheck.MODE_CAPTURE }.all { it.durationMillis > 0 })
    }

    @Test fun `Kamera startet nicht, Bericht endet rot und frueh`() = runTest {
        val cam = FakeCameraBoundary().apply { startError = CameraError.IN_USE }
        val report = SelfTestUseCase(cam, StepClock())()
        assertFalse(report.passed)
        assertEquals(1, report.items.size)
        assertEquals("IN_USE", report.items.single().detail)
    }

    @Test fun `Fehlgeschlagene Aufnahme wird rot gemeldet`() = runTest {
        val cam = FakeCameraBoundary(emptySet()).apply { nextCapture = CaptureResult.Failed(CaptureFailure.STORAGE) }
        val report = SelfTestUseCase(cam, StepClock())()
        assertFalse(report.passed)
        assertEquals("STORAGE", report.items.single { it.check == SelfTestCheck.MODE_CAPTURE }.detail)
    }

    @Test fun `Ohne Extensions wird nur NORMAL geprueft`() = runTest {
        val report = SelfTestUseCase(FakeCameraBoundary(emptySet()), StepClock())()
        assertTrue(report.passed)
        assertEquals(1, report.items.count { it.check == SelfTestCheck.MODE_CAPTURE })
    }

    @Test fun `Vorheriger Modus wird wiederhergestellt`() = runTest {
        val cam = FakeCameraBoundary(setOf(PhotoMode.HDR)); cam.start(); cam.selectMode(PhotoMode.HDR)
        SelfTestUseCase(cam, StepClock())()
        assertEquals(PhotoMode.HDR, cam.state.value.requestedMode)
    }
}
