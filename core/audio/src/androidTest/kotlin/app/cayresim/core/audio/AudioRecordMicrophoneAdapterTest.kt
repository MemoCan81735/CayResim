package app.cayresim.core.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import app.cayresim.core.boundary.AudioSourceKey
import app.cayresim.core.boundary.MicRecordResult
import app.cayresim.core.boundary.MicRequest
import app.cayresim.core.pure.Wav
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

    @Test fun s008InventarStuerztNichtAb() = runBlocking {
        val inv = adapter.inventory()
        // Der Emulator meldet mindestens ein Eingabegeraet; Mikrofonliste darf leer sein
        assertTrue(inv.devices.isNotEmpty(), "Eingabegeraete: ${inv.devices}")
    }
}
