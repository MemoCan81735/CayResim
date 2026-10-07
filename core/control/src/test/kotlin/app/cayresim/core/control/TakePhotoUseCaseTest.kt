package app.cayresim.core.control

import app.cayresim.core.boundary.CaptureFailure
import app.cayresim.core.boundary.CaptureResult
import app.cayresim.core.boundary.fake.FakeCameraBoundary
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TakePhotoUseCaseTest {
    @Test fun `Guter Fall speichert`() = runTest {
        val cam = FakeCameraBoundary(); cam.start()
        assertIs<CaptureResult.Saved>(TakePhotoUseCase(cam)())
        assertEquals(1, cam.saved.size)
    }

    @Test fun `Ohne laufende Kamera NOT_READY`() = runTest {
        assertEquals(CaptureResult.Failed(CaptureFailure.NOT_READY), TakePhotoUseCase(FakeCameraBoundary())())
    }

    @Test fun `Nach Stopp NOT_READY`() = runTest {
        val cam = FakeCameraBoundary(); cam.start(); cam.stop()
        assertEquals(CaptureResult.Failed(CaptureFailure.NOT_READY), TakePhotoUseCase(cam)())
    }

    @Test fun `Speicherfehler wird als Wert weitergegeben`() = runTest {
        val cam = FakeCameraBoundary(); cam.start(); cam.nextCapture = CaptureResult.Failed(CaptureFailure.STORAGE)
        assertEquals(CaptureResult.Failed(CaptureFailure.STORAGE), TakePhotoUseCase(cam)())
        assertEquals(0, cam.saved.size)
    }

    @Test fun `Nach einem Fehler klappt die naechste Aufnahme`() = runTest {
        val cam = FakeCameraBoundary(); cam.start(); cam.nextCapture = CaptureResult.Failed(CaptureFailure.UNKNOWN)
        val uc = TakePhotoUseCase(cam); uc()
        assertIs<CaptureResult.Saved>(uc())
    }

    @Test fun `Viele Aufnahmen hintereinander`() = runTest {
        val cam = FakeCameraBoundary(); cam.start(); val uc = TakePhotoUseCase(cam)
        repeat(200) { assertIs<CaptureResult.Saved>(uc()) }
        assertEquals(200, cam.saved.toSet().size)
    }
}
