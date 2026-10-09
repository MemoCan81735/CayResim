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

    /**
     * Nimmt [count] RAW-Bilder bei fester Belichtung nur in den Speicher auf, misst Tempo, Kalibrierung und Signal
     * und verwirft sie sofort. Danach sind Bindung und Belichtung wie vorher, auch bei Abbruch.
     */
    suspend fun probeRaw(count: Int, exposureNanos: Long, iso: Int): RawProbeResult
}

/** Farbmuster des Sensors (Bayer). */
enum class CfaLayout { RGGB, GRBG, GBRG, BGGR, RGB, MONO, UNKNOWN }

/**
 * Messung einer RAW-Serie im Selbsttest (Schritt 0 des RAW-Wegs, claude/foto-app-raw-weg.md).
 * Nur Zahlen und Schluessel (R23), Signal und Rauschen in Sensorstufen ueber dem Schwarzwert.
 */
data class RawProbe(
    val frames: Int,
    val requested: Int,
    val avgFrameMs: Long,
    val maxFrameMs: Long,
    val width: Int,
    val height: Int,
    /** Schwarzwert je Position im 2x2-Muster (oben links, oben rechts, unten links, unten rechts). */
    val blackLevel: List<Int>,
    val whiteLevel: Int?,
    val cfa: CfaLayout,
    val colorMatrix: Boolean,
    val forwardMatrix: Boolean,
    val lensShading: Boolean,
    /** Mittlerer Wert ueber Schwarz im letzten Bild. */
    val meanAboveBlack: Float,
    /** Rauschen aus dem Unterschied der letzten zwei Bilder (geteilt durch Wurzel 2); null bei nur einem Bild. */
    val noise: Float?,
)

enum class RawProbeFailure { NOT_SUPPORTED, NOT_READY, TIMEOUT, CAMERA }

sealed interface RawProbeResult {
    data class Ok(val probe: RawProbe) : RawProbeResult
    data class Failed(val reason: RawProbeFailure) : RawProbeResult
}
