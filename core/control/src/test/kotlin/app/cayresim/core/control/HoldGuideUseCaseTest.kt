package app.cayresim.core.control

import app.cayresim.core.boundary.MotionAvailability
import app.cayresim.core.boundary.MotionKind
import app.cayresim.core.boundary.MotionSample
import app.cayresim.core.boundary.fake.FakeMotionSensorBoundary
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.ContinuationInterceptor
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** S-016 K3: Halte-Kreis aus dem Lagesensor; Lage kuenstlich (Drehung nach rechts, 1 Grad je Sekunde). */
class HoldGuideUseCaseTest {
    private suspend fun here(): CoroutineDispatcher = currentCoroutineContext()[ContinuationInterceptor] as CoroutineDispatcher

    private fun samples() = List(201) { i ->
        // alle 10 ms; Drehung um Geraete-y mit negativem Winkel: Kamera zeigt nach rechts; dazu Beschleunigung
        val deg = -i * 0.01; val h = deg * PI / 360
        listOf(
            MotionSample(MotionKind.ROTATION, i * 10_000_000L, cos(h).toFloat(), 0f, sin(h).toFloat(), 0f),
            MotionSample(MotionKind.ACCELERATION, i * 10_000_000L, 0f, 0f, 9.81f),
        )
    }.flatten()

    @Test fun `S-016 Abweichung und Abmelden`() = runTest {
        val sensors = FakeMotionSensorBoundary(samples = samples(), paced = true)
        val updates = ArrayList<HoldUpdate>()
        val job = launch { HoldGuideUseCase(sensors, here()).track().collect { updates += it } }
        advanceTimeBy(2_100)
        assertEquals(1, sensors.active, "Sensor angemeldet")
        job.cancel(); job.join()
        assertEquals(0, sensors.active, "Sensor nach Abbruch abgemeldet")
        assertEquals(0.0, updates.first().deviation.rightDeg, 1e-6, "Start ist die Mitte")
        // hoechstens etwa 30 je Sekunde (201 Lagen in 2 s)
        assertTrue(updates.size in 50..70, "Meldungen ${updates.size}")
        val last = updates.last()
        assertEquals(last.nanos / 1e9, last.deviation.rightDeg, 0.05)
        assertTrue(updates.zipWithNext().all { (a, b) -> b.deviation.rightDeg >= a.deviation.rightDeg - 1e-9 }, "steigt")
        assertTrue(updates.first { it.deviation.rightDeg > 0.5 }.let { !it.deviation.steady })
        // ohne Drehvektor: keine Meldung, kein Sensor
        val none = FakeMotionSensorBoundary(MotionAvailability(rotation = false, acceleration = true), samples())
        assertTrue(HoldGuideUseCase(none, here()).track().toList().isEmpty()); assertEquals(0, none.registrations)
    }
}
