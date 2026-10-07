package app.cayresim.core.boundary

import kotlinx.coroutines.flow.Flow

/** Bildserie aus dem eigenen Frame-Strom, RGB-Bytes je Bild (3 Bytes je Pixel). */
class FrameBurst(val width: Int, val height: Int, val frames: List<ByteArray>, val rotationDegrees: Int = 0) {
    val pixels: Int get() = width * height
}

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

    /** Meldet jedes Mal, wenn der Ausloeser nach den Regeln feuern soll. */
    fun trigger(mode: TriggerMode): Flow<Unit>
}

enum class StackMode { MEDIAN, MEAN }

