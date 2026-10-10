package app.cayresim.core.boundary.fake

import app.cayresim.core.boundary.MotionAvailability
import app.cayresim.core.boundary.MotionSample
import app.cayresim.core.boundary.MotionSensorBoundary
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/**
 * Lagesensor im Speicher (S-010): sendet [samples] und wartet dann bis zum Abbruch, wie ein echter Sensor.
 * [active] zaehlt angemeldete Sammler; abgemeldet wird in genau einem NonCancellable-Block wie im Adapter.
 */
class FakeMotionSensorBoundary(
    var availability: MotionAvailability = MotionAvailability(rotation = true, acceleration = true),
    var samples: List<MotionSample> = emptyList(),
    /** S-014: Werte im Takt ihrer Zeitstempel senden (virtuelle Zeit), statt alle sofort. */
    var paced: Boolean = false,
) : MotionSensorBoundary {
    var active = 0
        private set
    var maxActive = 0
        private set
    var registrations = 0
        private set

    override fun availability() = availability

    override fun samples(periodMicros: Int): Flow<MotionSample> = flow {
        var registered = false
        try {
            active++; registered = true; registrations++; maxActive = maxOf(maxActive, active)
            // im Takt: Zielzeit ab dem ersten Wert, damit sich Rundungen nicht aufsummieren (Zweitpruefung S-014, H14)
            var waitedMs = 0L
            for (s in samples) {
                if (paced) {
                    val target = (s.nanos - samples.first().nanos) / 1_000_000L
                    if (target > waitedMs) { kotlinx.coroutines.delay(target - waitedMs); waitedMs = target }
                }
                emit(s)
            }
            awaitCancellation()
        } finally {
            withContext(NonCancellable) { if (registered) active-- }
        }
    }
}
