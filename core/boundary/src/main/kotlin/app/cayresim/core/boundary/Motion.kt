package app.cayresim.core.boundary

import kotlinx.coroutines.flow.Flow

/** Art eines Lagewerts (S-010). */
enum class MotionKind { ROTATION, ACCELERATION }

/**
 * Ein Wert des Lagesensors, nur Zahlen (R23). [nanos]: Zeit seit dem Einschalten, dieselbe Zeitbasis wie
 * [MicCapture.startBootNanos]. ROTATION: Quaternion w, x, y, z (dreht Geraet in Welt: x Osten, y Norden, z oben);
 * ACCELERATION: x, y, z in m/s^2, [d] = 0.
 */
data class MotionSample(val kind: MotionKind, val nanos: Long, val a: Float, val b: Float, val c: Float, val d: Float = 0f)

/**
 * Quelle der Lage. GAME: Drehvektor ohne Kompass (bevorzugt, springt nicht bei Kompasskorrekturen; Richtung nach Norden
 * beliebig, fuer "relativ zum Start" egal). ROTATION_VECTOR: mit Kompass, nur als Rueckfall (Zweitpruefung S-010, G3).
 */
enum class RotationSource { GAME, ROTATION_VECTOR, NONE }

data class MotionAvailability(val rotation: Boolean, val acceleration: Boolean, val rotationSource: RotationSource = if (rotation) RotationSource.GAME else RotationSource.NONE)

/**
 * Einzige Sicht der App auf die Lagesensoren (R29). [samples] meldet die Sensoren beim Sammeln an und beim Abbruch
 * wieder ab (R17). Keine Berechtigung noetig bis 200 Hz.
 */
interface MotionSensorBoundary {
    fun availability(): MotionAvailability
    fun samples(periodMicros: Int = 10_000): Flow<MotionSample>
}
