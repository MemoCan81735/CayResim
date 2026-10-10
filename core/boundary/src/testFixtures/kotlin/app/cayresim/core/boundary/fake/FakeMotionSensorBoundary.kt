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
            for (s in samples) emit(s)
            awaitCancellation()
        } finally {
            withContext(NonCancellable) { if (registered) active-- }
        }
    }
}
