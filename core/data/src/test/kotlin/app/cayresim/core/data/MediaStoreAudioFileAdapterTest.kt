package app.cayresim.core.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** S-008: Ablage der Tondateien, Fehler- und Randfaelle ohne Geraet. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaStoreAudioFileAdapterTest {
    private val adapter = MediaStoreAudioFileAdapter(ApplicationProvider.getApplicationContext(), Dispatchers.Unconfined)

    @Test fun `S-008 Ordner liegt unter Recordings und traegt Datum und Uhrzeit`() = runTest {
        val f = assertNotNull(adapter.newFolder("Mikrotest"))
        assertTrue(Regex("""Recordings/CayResim/Mikrotest-\d{8}-\d{6}""").matches(f), f)
    }

    @Test fun `S-008 ungueltige Namen werden abgelehnt`() = runTest {
        assertNull(adapter.newFolder(""))
        assertNull(adapter.newFolder("a/b"))
        assertNull(adapter.saveWav("Pictures/x", "a.wav", ByteArray(44)), "nur unter Recordings/CayResim")
        assertNull(adapter.saveWav("Recordings/CayResim/x", "../a.wav", ByteArray(44)))
        assertNull(adapter.saveWav("Recordings/CayResim/x", "a.mp3", ByteArray(44)))
    }

    @Test fun `S-008 Speichern ohne echte Medienablage stuerzt nicht ab`() = runTest {
        // Robolectric hat keinen echten MediaStore; je nach Version wird simuliert gespeichert oder null geliefert.
        // Geprueft wird nur: keine Ausnahme, und wenn gespeichert, dann eine content-URI. Echt speichert K13 auf dem Emulator.
        val r = adapter.saveWav("Recordings/CayResim/Mikrotest-1", "01-MIC-v1.wav", ByteArray(44))
        assertTrue(r == null || r.startsWith("content://"), "Ergebnis $r")
    }
}
