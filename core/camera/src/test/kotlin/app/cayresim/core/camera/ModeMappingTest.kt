package app.cayresim.core.camera

import androidx.camera.core.ImageCapture
import androidx.camera.extensions.ExtensionMode
import app.cayresim.core.boundary.CaptureFailure
import app.cayresim.core.boundary.PhotoMode
import app.cayresim.core.entity.ModeKey
import kotlin.test.Test
import kotlin.test.assertEquals

class ModeMappingTest {
    @Test fun `Jeder Boundary-Modus hat einen Entity-Schluessel und zurueck`() {
        PhotoMode.entries.forEach { assertEquals(it, it.toKey().toPhotoMode()) }
        assertEquals(PhotoMode.entries.size, ModeKey.entries.size)
    }

    @Test fun `NORMAL ist keine Extension, alle anderen schon`() {
        assertEquals(ExtensionMode.NONE, ModeKey.NORMAL.toExtensionMode())
        val others = ModeKey.entries.filter { it != ModeKey.NORMAL }.map { it.toExtensionMode() }
        assertEquals(others.size, others.toSet().size, "Modi duerfen nicht doppelt belegt sein")
        others.forEach { kotlin.test.assertNotEquals(ExtensionMode.NONE, it) }
    }

    @Test fun `CameraX-Fehler werden auf Ergebnistypen abgebildet`() {
        assertEquals(CaptureFailure.STORAGE, CameraXCameraAdapter.mapError(ImageCapture.ERROR_FILE_IO))
        assertEquals(CaptureFailure.CAMERA_CLOSED, CameraXCameraAdapter.mapError(ImageCapture.ERROR_CAMERA_CLOSED))
        assertEquals(CaptureFailure.UNKNOWN, CameraXCameraAdapter.mapError(-12345))
    }
}
