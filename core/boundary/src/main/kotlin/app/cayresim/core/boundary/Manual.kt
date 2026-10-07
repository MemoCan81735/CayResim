package app.cayresim.core.boundary

import kotlinx.coroutines.flow.StateFlow

/** Was die Kamera manuell erlaubt (Phase 4). Bereiche null = nicht einstellbar. */
data class ManualCapabilitiesSnapshot(
    val exposureRangeNanos: LongRange?,
    val isoRange: IntRange?,
    val maxFocusDiopters: Float?,
    val raw: Boolean,
) {
    val canExpose: Boolean get() = exposureRangeNanos != null && isoRange != null
    val canFocus: Boolean get() = (maxFocusDiopters ?: 0f) > 0f
}

data class ManualStateSnapshot(val exposureNanos: Long? = null, val iso: Int? = null, val focusDiopters: Float? = null, val raw: Boolean = false)

/** Manuelle Kamera ueber Camera2-Interop, nur im normalen Modus ohne Extensions. */
interface ManualCameraBoundary {
    val manualCapabilities: StateFlow<ManualCapabilitiesSnapshot?>
    val manualState: StateFlow<ManualStateSnapshot>

    /** null, null = Automatik. false, wenn nicht einstellbar. */
    suspend fun setExposure(nanos: Long?, iso: Int?): Boolean

    /** null = Autofokus. */
    suspend fun setFocus(diopters: Float?): Boolean

    suspend fun setRaw(enabled: Boolean): Boolean

    /** Bilder mit verschobenem Fokus von nah nach fern fuer das Fokus-Stacking. */
    suspend fun focusBracket(steps: Int): BurstResult
}
