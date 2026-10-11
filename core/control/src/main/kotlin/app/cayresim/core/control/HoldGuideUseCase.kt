package app.cayresim.core.control

import app.cayresim.core.boundary.ComputeDispatcher
import app.cayresim.core.boundary.MotionKind
import app.cayresim.core.boundary.MotionSensorBoundary
import app.cayresim.core.pure.HoldGuide
import app.cayresim.core.pure.SweepMath
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import javax.inject.Inject

/**
 * Abweichung der Blickrichtung gegen den Start zum Zeitpunkt [nanos] (Zeitbasis des Sensors), dazu die Kennzahlen bis
 * jetzt aus allen Lagewerten (nicht nur den gemeldeten): Anteil der Zeit im Kreis, groesste Abweichung, gemessene Zeit.
 */
data class HoldUpdate(val deviation: HoldGuide.Deviation, val nanos: Long, val insideShare: Double, val maxDeg: Double, val measuredNanos: Long)

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
        // Kennzahlen aus jedem Lagewert: Zittern von 8 bis 12 Hz liegt zwischen zwei Meldungen (Zweitpruefung S-016, W3)
        val stats = HoldGuide.Stats()
        sensors.samples(PERIOD_MICROS).collect { s ->
            if (s.kind != MotionKind.ROTATION) return@collect
            val pose = SweepMath.Pose(s.nanos, s.a.toDouble(), s.b.toDouble(), s.c.toDouble(), s.d.toDouble())
            val first = start ?: pose.also { start = it }
            val d = HoldGuide.deviation(first, pose)
            stats.add(s.nanos, d.totalDeg)
            if (lastEmit == Long.MIN_VALUE || s.nanos - lastEmit >= MIN_GAP_NANOS) {
                lastEmit = s.nanos
                emit(HoldUpdate(d, s.nanos, stats.insideShare, stats.maxDeg, stats.totalNanos))
            }
        }
    }
        // Ein Sensorfehler beendet nur den Kreis, nie die Nachtaufnahme (R14, Zweitpruefung S-016, W1)
        .catch { }
        .flowOn(compute)

    companion object {
        /** 100 Hz reichen fuer die Anzeige; der Sensor laeuft bei der Nachtserie ohnehin. */
        const val PERIOD_MICROS = 10_000
        /** Hoechstens etwa 30 Meldungen je Sekunde an die Oberflaeche. */
        const val MIN_GAP_NANOS = 33_000_000L
    }
}
