package app.cayresim.core.sensors

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** S-010 K7: Sensoren werden an- und wieder abgemeldet, auch bei Abbruch; Zeitstempel in der Zeitbasis seit dem Einschalten. */
@RunWith(AndroidJUnit4::class)
class MotionSensorAdapterTest {
    private val adapter = MotionSensorAdapter(InstrumentationRegistry.getInstrumentation().targetContext)

    @Test fun s010AnUndAbmelden() = runBlocking {
        val avail = adapter.availability()
        // der Emulator hat immer einen Beschleunigungssensor; ohne ihn koennte dieser Test nie rot werden (Zweitpruefung G7)
        assertTrue(avail.acceleration, "Beschleunigungssensor fehlt: $avail")
        println("S-010 Sensoren: $avail")
        var got = 0; var lastNanos = 0L
        val job = launch(Dispatchers.Default) { adapter.samples().collect { got++; lastNanos = it.nanos } }
        delay(1_000)
        assertEquals(1, adapter.activeListeners, "angemeldet")
        assertTrue(got > 0, "Werte in 1 s: $got")
        val now = android.os.SystemClock.elapsedRealtimeNanos()
        assertTrue(now - lastNanos in 0..2_000_000_000L, "Zeitbasis: jetzt $now, letzter Wert $lastNanos")
        job.cancelAndJoin()
        delay(100)
        assertEquals(0, adapter.activeListeners, "nach Abbruch abgemeldet")
    }
}
