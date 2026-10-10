package app.cayresim.core.boundary

import kotlinx.coroutines.flow.Flow

/** Bildserie aus dem eigenen Frame-Strom, RGB-Bytes je Bild (3 Bytes je Pixel). */
class FrameBurst(val width: Int, val height: Int, val frames: List<ByteArray>, val rotationDegrees: Int = 0) {
    val pixels: Int get() = width * height
}

/**
 * Ein einzelnes Bild aus dem Frame-Strom (RGB, 3 Bytes je Pixel). [timestampNs]: Aufnahmezeit in ns seit dem
 * Einschalten (Zeitbasis der Lagesensoren, S-011); null, wenn unbekannt.
 */
class Frame(val width: Int, val height: Int, val rgb: ByteArray, val rotationDegrees: Int = 0, val timestampNs: Long? = null)

/**
 * Ein RAW-Bild (Bayer, je Wert 16 Bit, [rowStride] Werte je Zeile) mit der Kalibrierung seiner Aufnahme.
 * Nur Zahlen (R23): Schwarz je Position im 2x2-Muster, Weisswert, Weissabgleich Rot/Gruen/Blau,
 * Farbmatrix Kamera nach sRGB linear (3x3 zeilenweise).
 */
class RawFrame(
    val width: Int,
    val height: Int,
    val rowStride: Int,
    val data: ShortArray,
    val cfa: CfaLayout,
    val black: FloatArray,
    val white: Float,
    val gains: FloatArray,
    val colorMatrix: FloatArray,
    val rotationDegrees: Int = 0,
)

enum class BurstFailure { NOT_READY, TIMEOUT, CANCELLED }

sealed interface BurstResult {
    class Ok(val burst: FrameBurst, val requested: Int) : BurstResult
    data class Failed(val reason: BurstFailure) : BurstResult
}

enum class TriggerMode { MOTION, STILLNESS }

/**
 * Eigene Pipeline (Phase 3): Bilder aus der Bildanalyse statt Hersteller-Extensions.
 * Laeuft immer im normalen Modus; der Wechsel bindet die Kamera neu (R12).
 */
interface FrameBoundary {
    /** Sammelt bis zu [count] Bilder; das Waermebudget kann weniger erlauben (R27). */
    suspend fun collect(count: Int): BurstResult

    /**
     * Bilder einzeln, solange gesammelt wird; hoechstens [maxCount] (das Waermebudget kann weniger erlauben).
     * Die Bilder werden nicht gespeichert, der Empfaenger verarbeitet sie sofort (Nacht-Kern).
     * Ohne laufende Kamera endet der Strom sofort ohne Bild.
     */
    fun frames(maxCount: Int): Flow<Frame>

    /**
     * RAW-Bildstrom mit fester Belichtung fuer den RAW-Nachtweg (eigene Camera2-Sitzung, Ausnahme A3); hoechstens
     * [maxCount] Bilder, hoechstens eins gleichzeitig unterwegs (R19). Leer, wenn RAW nicht moeglich ist.
     * Danach laeuft die Kamera wie vorher.
     */
    fun rawFrames(maxCount: Int, exposureNs: Long, iso: Int): Flow<RawFrame>

    /** Meldet jedes Mal, wenn der Ausloeser nach den Regeln feuern soll. */
    fun trigger(mode: TriggerMode): Flow<Unit>
}

/** MEDIAN: Bewegtes verschwindet, MEAN: Langzeit, FOCUS: Fokus-Stacking, STARS: ausrichten und mitteln. */
enum class StackMode { MEDIAN, MEAN, FOCUS, STARS }

