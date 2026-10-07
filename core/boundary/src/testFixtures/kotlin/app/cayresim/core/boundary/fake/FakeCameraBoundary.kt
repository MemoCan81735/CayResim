package app.cayresim.core.boundary.fake

import app.cayresim.core.boundary.CameraBoundary
import app.cayresim.core.boundary.CameraCapabilitiesSnapshot
import app.cayresim.core.boundary.CameraError
import app.cayresim.core.boundary.CameraStateSnapshot
import app.cayresim.core.boundary.CameraStatus
import app.cayresim.core.boundary.CaptureFailure
import app.cayresim.core.boundary.CaptureResult
import app.cayresim.core.boundary.PhotoMode
import app.cayresim.core.boundary.PreviewHandle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Fake statt Mock: verhaelt sich wie der echte Adapter, steuerbar fuer Fehlerfaelle. */
class FakeCameraBoundary(
    var availableModes: Set<PhotoMode> = setOf(PhotoMode.NIGHT, PhotoMode.HDR),
) : CameraBoundary {
    private val _caps = MutableStateFlow<CameraCapabilitiesSnapshot?>(null)
    private val _state = MutableStateFlow(CameraStateSnapshot())
    override val capabilities: StateFlow<CameraCapabilitiesSnapshot?> = _caps
    override val state: StateFlow<CameraStateSnapshot> = _state

    /** Steuerung fuer Tests. */
    var startError: CameraError? = null
    var nextCapture: CaptureResult? = null
    val saved = mutableListOf<String>()
    var startCalls = 0; private set
    var stopCalls = 0; private set
    private var counter = 0

    override suspend fun start() {
        startCalls++
        val modes = listOf(PhotoMode.NORMAL) + PhotoMode.entries.filter { it in availableModes && it != PhotoMode.NORMAL }
        _caps.value = CameraCapabilitiesSnapshot(modes, lowLightBoost = false, ultraHdr = false, raw = false)
        val err = startError
        _state.update {
            if (err != null) it.copy(status = CameraStatus.ERROR, error = err, preview = null)
            else it.copy(status = CameraStatus.RUNNING, error = null, offeredModes = modes, preview = PreviewHandle("fake"))
        }
        if (err == null) applyMode(_state.value.requestedMode)
    }

    override suspend fun stop() {
        stopCalls++
        _state.update { it.copy(status = CameraStatus.IDLE, preview = null, capturing = false) }
    }

    override suspend fun selectMode(mode: PhotoMode) = applyMode(mode)

    private fun applyMode(mode: PhotoMode) {
        val ok = mode == PhotoMode.NORMAL || mode in availableModes
        _state.update { it.copy(requestedMode = mode, activeMode = if (ok) mode else PhotoMode.NORMAL, fallbackFrom = if (ok) null else mode) }
    }

    override suspend fun capture(): CaptureResult {
        if (_state.value.status != CameraStatus.RUNNING) return CaptureResult.Failed(CaptureFailure.NOT_READY)
        val result = nextCapture ?: CaptureResult.Saved("content://fake/${++counter}")
        nextCapture = null
        if (result is CaptureResult.Saved) saved += result.uri
        return result
    }

    override suspend fun delete(uri: String): Boolean = saved.remove(uri)
}
