package app.cayresim.core.control

import app.cayresim.core.boundary.CameraBoundary
import app.cayresim.core.boundary.CameraStatus
import app.cayresim.core.boundary.CaptureFailure
import app.cayresim.core.boundary.CaptureResult
import javax.inject.Inject

/** Ausloesen mit Schutz vor Doppelklick und vor Aufnahme ohne laufende Kamera. */
class TakePhotoUseCase @Inject constructor(private val camera: CameraBoundary) {
    private var running = false

    suspend operator fun invoke(): CaptureResult {
        val s = camera.state.value
        if (s.status != CameraStatus.RUNNING) return CaptureResult.Failed(CaptureFailure.NOT_READY)
        if (running || s.capturing) return CaptureResult.Failed(CaptureFailure.BUSY)
        running = true
        return try {
            camera.capture()
        } finally {
            running = false
        }
    }
}
