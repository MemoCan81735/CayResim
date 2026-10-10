package app.cayresim.core.data

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cayresim.core.pure.Wav
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** S-008 K13: WAV wird in der Medienablage gespeichert, ist lesbar und wird danach geloescht. */
@RunWith(AndroidJUnit4::class)
class MediaStoreAudioFileAdapterDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val adapter = MediaStoreAudioFileAdapter(context, Dispatchers.IO)

    @Test fun s008WavSpeichernUndLesen() = runBlocking {
        val folder = assertNotNull(adapter.newFolder("Mikrotest"))
        val wav = Wav.encode(ShortArray(9600) { (it % 200 - 100).toShort() }, 48_000, 2)
        val uri = Uri.parse(assertNotNull(adapter.saveWav(folder, "01-MIC-v1.wav", wav)))
        try {
            val back = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            assertEquals(wav.toList(), back.toList())
            assertEquals(9600, assertNotNull(Wav.decode(back)).samples.size)
            // Name bleibt erhalten (kein angehaengtes Kuerzel durch einen unpassenden MIME-Typ)
            val name = context.contentResolver.query(uri, arrayOf(android.provider.MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)!!
                .use { c -> c.moveToFirst(); c.getString(0) }
            assertEquals("01-MIC-v1.wav", name)
        } finally {
            context.contentResolver.delete(uri, null, null)
        }
    }
}
