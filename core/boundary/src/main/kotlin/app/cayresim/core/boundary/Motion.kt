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

data class MotionAvailability(val rotation: Boolean, val acceleration: Boolean)

/**
 * Einzige Sicht der App auf die Lagesensoren (R29). [samples] meldet die Sensoren beim Sammeln an und beim Abbruch
 * wieder ab (R17). Keine Berechtigung noetig bis 200 Hz.
 */
interface MotionSensorBoundary {
    fun availability(): MotionAvailability
    fun samples(periodMicros: Int = 10_000): Flow<MotionSample>
}
