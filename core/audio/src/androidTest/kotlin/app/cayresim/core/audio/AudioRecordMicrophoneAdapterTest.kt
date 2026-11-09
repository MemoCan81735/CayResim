package app.cayresim.core.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import app.cayresim.core.boundary.AudioSourceKey
import app.cayresim.core.boundary.MicRecordResult
import app.cayresim.core.boundary.MicRequest
import app.cayresim.core.boundary.MicStreamEvent
import app.cayresim.core.pure.Wav
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** S-008 K13: echte Aufnahme auf dem Emulator (virtuelles Mikrofon, meist Stille) und Freigabe. */
@RunWith(AndroidJUnit4::class)
class AudioRecordMicrophoneAdapterTest {
    @get:Rule val permission: GrantPermissionRule = GrantPermissionRule.grant(android.Manifest.permission.RECORD_AUDIO)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val adapter = AudioRecordMicrophoneAdapter(context, Dispatchers.IO)

    @Test fun s008AufnahmeUndFreigabe() = runBlocking {
        withTimeout(20_000) {
            assertTrue(adapter.hasPermission())
            val t0 = android.os.SystemClock.elapsedRealtime()
            val r = adapter.record(MicRequest(AudioSourceKey.MIC, millis = 2_000))
            val took = android.os.SystemClock.elapsedRealtime() - t0
            val c = assertIs<MicRecordResult.Ok>(r, "Ergebnis $r").capture
            // die Aufnahme dauert wirklich etwa 2 s (der Puffer allein hat immer 96.000 Frames, Zweitpruefung S-008)
            assertTrue(took in 1_800..3_100, "Dauer $took ms")
            assertEquals(96_000, c.pcm.size / c.channels)
            assertTrue(c.channels in 1..2)
            assertEquals(0, adapter.openRecordings, "Mikrofon nach der Aufnahme frei")
            // als WAV kodierbar und lesbar
            val back = assertNotNull(Wav.decode(Wav.encode(c.pcm, c.sampleRate, c.channels)))
            assertEquals(c.pcm.size, back.samples.size)
        }
    }

    @Test fun s008AbbruchGibtFrei() = runBlocking {
        val job = launch(Dispatchers.Default) { adapter.record(MicRequest(AudioSourceKey.MIC, millis = 5_000)) }
        delay(800)
        job.cancel(); job.join()
        assertEquals(0, adapter.openRecordings, "Mikrofon nach Abbruch frei")
        // danach geht die naechste Aufnahme
        assertIs<MicRecordResult.Ok>(adapter.record(MicRequest(AudioSourceKey.MIC, millis = 300)))
        Unit
    }

    /** S-010 K7: Zeitbezug des ersten Frames in der Zeitbasis seit dem Einschalten, genau oder grob. */
    @Test fun s010Zeitbezug() = runBlocking {
        val before = android.os.SystemClock.elapsedRealtimeNanos()
        val r = adapter.record(MicRequest(AudioSourceKey.MIC, millis = 1_000))
        val after = android.os.SystemClock.elapsedRealtimeNanos()
        val c = assertIs<MicRecordResult.Ok>(r, "Ergebnis $r").capture
        val start = assertNotNull(c.startBootNanos, "Zeitbezug fehlt")
        // im Testprotokoll sichtbar, ob der genaue Weg (Zeitstempel) oder der grobe lief (Zweitpruefung G7)
        println("S-010 Zeitbezug: genau=${c.timeExact}, Start ${(start - before) / 1_000_000} ms nach Aufruf")
        assertTrue(start in before..after, "Start $start nicht zwischen $before und $after (genau: ${c.timeExact})")
        assertTrue(after - start >= 900_000_000L, "erster Frame mindestens 0,9 s vor dem Ende")
    }

    /** S-018 K18: Tonstrom liefert Stuecke zu 100 ms, bis der Sammler abbricht, und gibt das Mikrofon frei. */
    @Test fun s018TonstromLiefertStueckeUndGibtFrei() = runBlocking {
        withTimeout(20_000) {
            val chunks = mutableListOf<MicStreamEvent.Chunk>()
            var failed: MicStreamEvent.Failed? = null
            // 3 s Fenster: der Emulator braucht zum Anlaufen der Aufnahme unterschiedlich lang (Zweitpruefung S-018)
            withTimeoutOrNull(3_000) {
                adapter.stream(MicRequest(AudioSourceKey.MIC), 100).collect { e ->
                    when (e) {
                        is MicStreamEvent.Chunk -> chunks += e
                        is MicStreamEvent.Failed -> failed = e
                    }
                }
            }
            assertEquals(null, failed, "Tonstrom gescheitert")
            assertTrue(chunks.size >= 8, "nur ${chunks.size} Stuecke in 3 s")
            val numbers = chunks.map { it.chunk.index }
            assertTrue(numbers.zipWithNext().all { (a, b) -> b > a }, "Nummern steigen: $numbers")
            val c = chunks.first().chunk
            assertTrue(c.channels in 1..2)
            assertEquals(c.sampleRate / 10 * c.channels, c.pcm.size)
            assertEquals(0, adapter.openRecordings, "Mikrofon nach Abbruch des Tonstroms frei")
            // danach geht eine Einzelaufnahme und wieder ein Strom
            assertIs<MicRecordResult.Ok>(adapter.record(MicRequest(AudioSourceKey.MIC, millis = 300)))
            assertIs<MicStreamEvent.Chunk>(adapter.stream(MicRequest(AudioSourceKey.MIC), 100).first())
            assertEquals(0, adapter.openRecordings)
        }
    }

    /** S-019 K4: drei Kanaele ueber die Index-Maske enden als Aufnahme oder Fehlerwert, nie als Absturz. */
    @Test fun s019DreiKanaele() = runBlocking {
        withTimeout(20_000) {
            when (val r = adapter.record(MicRequest(AudioSourceKey.MIC, millis = 500, channels = 3))) {
                is MicRecordResult.Ok -> {
                    val c = r.capture
                    println("S-019 drei Kanaele angefragt: ${c.channels} geliefert")
                    assertTrue(c.channels in 1..3, "Kanaele ${c.channels}")
                    assertEquals(24_000, c.pcm.size / c.channels)
                }
                is MicRecordResult.Failed -> println("S-019 drei Kanaele angefragt: ${r.reason}")
            }
            assertEquals(0, adapter.openRecordings, "Mikrofon frei")
            // fuenf Kanaele lehnt der Adapter selbst ab
            assertEquals(MicRecordResult.Failed(app.cayresim.core.boundary.MicFailure.INIT_FAILED), adapter.record(MicRequest(AudioSourceKey.MIC, millis = 500, channels = 5)))
        }
    }

    /** S-020 K6: zwei Aufnahmen gleichzeitig enden je als Aufnahme oder Fehlerwert und geben das Mikrofon frei. */
    @Test fun s020ZweiAufnahmenGleichzeitig() = runBlocking {
        withTimeout(20_000) {
            val pair = adapter.recordPair(MicRequest(AudioSourceKey.MIC, millis = 500), MicRequest(AudioSourceKey.CAMCORDER, millis = 500))
            for ((name, r) in listOf("MIC" to pair.first, "CAMCORDER" to pair.second)) when (r) {
                is MicRecordResult.Ok -> { println("S-020 $name: ${r.capture.channels} Kanaele"); assertEquals(24_000, r.capture.pcm.size / r.capture.channels) }
                is MicRecordResult.Failed -> println("S-020 $name: ${r.reason}")
            }
            assertEquals(0, adapter.openRecordings, "Mikrofon frei")
            // die neuen Quellen stuerzen nicht ab
            for (s in listOf(AudioSourceKey.VOICE_COMMUNICATION, AudioSourceKey.VOICE_PERFORMANCE)) adapter.record(MicRequest(s, millis = 300))
            assertEquals(0, adapter.openRecordings)
            assertIs<MicRecordResult.Ok>(adapter.record(MicRequest(AudioSourceKey.MIC, millis = 300)))
            // Abbruch mitten in zwei Aufnahmen gibt beide frei
            val job = launch(Dispatchers.Default) { adapter.recordPair(MicRequest(AudioSourceKey.MIC, millis = 5_000), MicRequest(AudioSourceKey.CAMCORDER, millis = 5_000)) }
            delay(500)
            job.cancel(); job.join()
            assertEquals(0, adapter.openRecordings, "Mikrofon nach Abbruch zweier Aufnahmen frei")
        }
    }

    @Test fun s008InventarStuerztNichtAb() = runBlocking {
        val inv = adapter.inventory()
        // Der Emulator meldet mindestens ein Eingabegeraet; Mikrofonliste darf leer sein
        assertTrue(inv.devices.isNotEmpty(), "Eingabegeraete: ${inv.devices}")
    }
}
