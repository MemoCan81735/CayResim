package app.cayresim.core.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import app.cayresim.core.boundary.MotionAvailability
import app.cayresim.core.boundary.MotionKind
import app.cayresim.core.boundary.MotionSample
import app.cayresim.core.boundary.MotionSensorBoundary
import app.cayresim.core.boundary.RotationSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lagesensoren ueber den SensorManager (S-010, R29): Drehvektor als Quaternion und Beschleunigung. Angemeldet wird
 * beim Sammeln, abgemeldet in awaitClose, also auch bei Abbruch (R17). Zeitstempel in ns seit dem Einschalten
 * (laut Android dieselbe Zeitbasis wie SystemClock.elapsedRealtimeNanos und der Zeitstempel der Tonaufnahme).
 */
@Singleton
class MotionSensorAdapter @Inject constructor(@ApplicationContext private val context: Context) : MotionSensorBoundary {
    private val manager: SensorManager? get() = context.getSystemService(SensorManager::class.java)
    private val listeners = AtomicInteger(0)

    /** Angemeldete Sammler; fuer den Emulatortest (muss nach jedem Sammeln 0 sein). */
    val activeListeners: Int get() = listeners.get()

    /** Drehvektor ohne Kompass bevorzugt, sonst mit Kompass (Zweitpruefung S-010, G3). */
    private fun rotationSensor(m: SensorManager?): Pair<Sensor?, RotationSource> {
        m?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)?.let { return it to RotationSource.GAME }
        m?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let { return it to RotationSource.ROTATION_VECTOR }
        return null to RotationSource.NONE
    }

    override fun availability(): MotionAvailability {
        val m = manager
        val (rot, source) = rotationSensor(m)
        return MotionAvailability(rot != null, m?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null, source)
    }

    override fun samples(periodMicros: Int): Flow<MotionSample> = callbackFlow {
        val m = manager
        val q = FloatArray(4)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                when (e.sensor.type) {
                    Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_ROTATION_VECTOR -> {
                        SensorManager.getQuaternionFromVector(q, e.values)
                        trySend(MotionSample(MotionKind.ROTATION, e.timestamp, q[0], q[1], q[2], q[3]))
                    }
                    Sensor.TYPE_ACCELEROMETER -> trySend(MotionSample(MotionKind.ACCELERATION, e.timestamp, e.values[0], e.values[1], e.values[2]))
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        var registered = false
        if (m != null) {
            for (s in listOfNotNull(rotationSensor(m).first, m.getDefaultSensor(Sensor.TYPE_ACCELEROMETER))) {
                registered = m.registerListener(listener, s, periodMicros) || registered
            }
        }
        if (registered) listeners.incrementAndGet()
        awaitClose {
            // R17: genau eine Abmeldung, auch bei Abbruch
            if (registered) { m?.unregisterListener(listener); listeners.decrementAndGet() }
        }
    }.buffer(Channel.UNLIMITED)
}
