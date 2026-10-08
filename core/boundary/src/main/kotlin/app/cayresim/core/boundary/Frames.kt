package app.cayresim.core.boundary

import kotlinx.coroutines.flow.Flow

/** Bildserie aus dem eigenen Frame-Strom, RGB-Bytes je Bild (3 Bytes je Pixel). */
class FrameBurst(val width: Int, val height: Int, val frames: List<ByteArray>, val rotationDegrees: Int = 0) {
    val pixels: Int get() = width * height
}

/** Ein einzelnes Bild aus dem Frame-Strom (RGB, 3 Bytes je Pixel). */
class Frame(val width: Int, val height: Int, val rgb: ByteArray, val rotationDegrees: Int = 0)

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

    /** Meldet jedes Mal, wenn der Ausloeser nach den Regeln feuern soll. */
    fun trigger(mode: TriggerMode): Flow<Unit>
}

/** MEDIAN: Bewegtes verschwindet, MEAN: Langzeit, FOCUS: Fokus-Stacking, STARS: ausrichten und mitteln. */
enum class StackMode { MEDIAN, MEAN, FOCUS, STARS }

