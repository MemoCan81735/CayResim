package app.cayresim.core.control

import app.cayresim.core.boundary.ComputeDispatcher
import app.cayresim.core.boundary.MotionKind
import app.cayresim.core.boundary.MotionSensorBoundary
import app.cayresim.core.pure.HoldGuide
import app.cayresim.core.pure.SweepMath
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import javax.inject.Inject

/** Abweichung der Blickrichtung gegen den Start zum Zeitpunkt [nanos] (Zeitbasis des Sensors). */
data class HoldUpdate(val deviation: HoldGuide.Deviation, val nanos: Long)

/**
 * S-016: Halte-Kreis waehrend der Nachtaufnahme. Meldet die Abweichung gegen die erste Lage, hoechstens etwa
 * 30-mal je Sekunde. Der Sensor ist nur angemeldet, solange gesammelt wird (R17, R29); ohne Drehvektor kommt nichts.
 */
class HoldGuideUseCase @Inject constructor(
    private val sensors: MotionSensorBoundary,
    @ComputeDispatcher private val compute: CoroutineDispatcher,
) {
    fun track(): Flow<HoldUpdate> = flow {
        if (!sensors.availability().rotation) return@flow
        var start: SweepMath.Pose? = null
        var lastEmit = Long.MIN_VALUE
        sensors.samples(PERIOD_MICROS).collect { s ->
            if (s.kind != MotionKind.ROTATION) return@collect
            val pose = SweepMath.Pose(s.nanos, s.a.toDouble(), s.b.toDouble(), s.c.toDouble(), s.d.toDouble())
            val first = start ?: pose.also { start = it }
            if (lastEmit == Long.MIN_VALUE || s.nanos - lastEmit >= MIN_GAP_NANOS) {
                lastEmit = s.nanos
                emit(HoldUpdate(HoldGuide.deviation(first, pose), s.nanos))
            }
        }
    }.flowOn(compute)

    companion object {
        /** 100 Hz reichen fuer die Anzeige; der Sensor laeuft bei der Nachtserie ohnehin. */
        const val PERIOD_MICROS = 10_000
        /** Hoechstens etwa 30 Meldungen je Sekunde an die Oberflaeche. */
        const val MIN_GAP_NANOS = 33_000_000L
    }
}
