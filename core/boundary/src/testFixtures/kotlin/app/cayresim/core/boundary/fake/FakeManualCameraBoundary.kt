package app.cayresim.core.boundary.fake

import app.cayresim.core.boundary.BurstFailure
import app.cayresim.core.boundary.BurstResult
import app.cayresim.core.boundary.CameraStatus
import app.cayresim.core.boundary.FrameBurst
import app.cayresim.core.boundary.ManualCameraBoundary
import app.cayresim.core.boundary.ManualCapabilitiesSnapshot
import app.cayresim.core.boundary.ManualStateSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

class FakeManualCameraBoundary(
    private val camera: FakeCameraBoundary,
    caps: ManualCapabilitiesSnapshot? = ManualCapabilitiesSnapshot(100_000L..2_000_000_000L, 50..3200, 10f, raw = true),
) : ManualCameraBoundary {
    private val _caps = MutableStateFlow(caps)
    private val _state = MutableStateFlow(ManualStateSnapshot())
    override val manualCapabilities: StateFlow<ManualCapabilitiesSnapshot?> = _caps
    override val manualState: StateFlow<ManualStateSnapshot> = _state
    val history = mutableListOf<ManualStateSnapshot>()

    override suspend fun setExposure(nanos: Long?, iso: Int?): Boolean {
        // Wie der echte Adapter (withContext): in einer abgebrochenen Coroutine wirft der Aufruf sofort
        kotlinx.coroutines.yield()
        val c = _caps.value
        if (nanos == null || iso == null) { _state.update { it.copy(exposureNanos = null, iso = null) }; history += _state.value; return true }
        if (c?.canExpose != true) return false
        _state.update { it.copy(exposureNanos = nanos.coerceIn(c.exposureRangeNanos!!), iso = iso.coerceIn(c.isoRange!!)) }
        history += _state.value; return true
    }

    override suspend fun setFocus(diopters: Float?): Boolean {
        if (diopters == null) { _state.update { it.copy(focusDiopters = null) }; return true }
        val c = _caps.value
        if (c?.canFocus != true) return false
        _state.update { it.copy(focusDiopters = diopters.coerceIn(0f, c.maxFocusDiopters!!)) }; return true
    }

    override suspend fun setRaw(enabled: Boolean): Boolean {
        if (enabled && _caps.value?.raw != true) return false
        _state.update { it.copy(raw = enabled) }; return true
    }

    /** Antwort fuer [probeRaw]; Standard: gemessene Werte in der Art des S24+. */
    var rawProbe: app.cayresim.core.boundary.RawProbeResult = app.cayresim.core.boundary.RawProbeResult.Ok(
        app.cayresim.core.boundary.RawProbe(8, 8, 180, 240, 4080, 3060, listOf(64, 64, 64, 64), 1023,
            app.cayresim.core.boundary.CfaLayout.GRBG, colorMatrix = true, forwardMatrix = true, lensShading = true,
            meanAboveBlack = 3.2f, noise = 4.1f))
    val probeCalls = mutableListOf<Triple<Int, Long, Int>>()

    override suspend fun probeRaw(count: Int, exposureNanos: Long, iso: Int): app.cayresim.core.boundary.RawProbeResult {
        probeCalls += Triple(count, exposureNanos, iso)
        if (_caps.value?.raw != true) return app.cayresim.core.boundary.RawProbeResult.Failed(app.cayresim.core.boundary.RawProbeFailure.NOT_SUPPORTED)
        if (camera.state.value.status != CameraStatus.RUNNING) return app.cayresim.core.boundary.RawProbeResult.Failed(app.cayresim.core.boundary.RawProbeFailure.NOT_READY)
        return rawProbe
    }

    override suspend fun focusBracket(steps: Int): BurstResult {
        if (camera.state.value.status != CameraStatus.RUNNING) return BurstResult.Failed(BurstFailure.NOT_READY)
        if (_caps.value?.canFocus != true) return BurstResult.Failed(BurstFailure.NOT_READY)
        val n = steps.coerceIn(2, 15)
        return BurstResult.Ok(FrameBurst(4, 3, List(n) { ByteArray(36) }), n)
    }
}
