package app.cayresim.core.data

import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cayresim.core.pure.NightSeries
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** S-011 K7: ZIP in Download/CayResim schreiben, lesen; ein abgebrochenes Archiv ist danach weg. */
@RunWith(AndroidJUnit4::class)
class MediaStoreSeriesAdapterTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val adapter = MediaStoreSeriesAdapter(context, Dispatchers.IO)

    private fun exists(uri: android.net.Uri) =
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { it.moveToFirst() } == true

    @Test fun s011SchreibenUndAbbrechen() = runBlocking {
        val s = assertNotNull(adapter.open("Nachtserie")) as MediaStoreSeriesAdapter.SessionAdapter
        val y = ByteArray(1440 * 1080) { (it % 7).toByte() }
        assertTrue(s.put(NightSeries.lumaName(0), y))
        assertTrue(s.put(NightSeries.META, "{\"format\": 1}".toByteArray()))
        val snap = assertNotNull(s.finish())
        try {
            assertTrue(snap.name.startsWith("Nachtserie-") && snap.name.endsWith(".zip"), snap.name)
            assertTrue(snap.bytes in 100..y.size.toLong(), "Groesse ${snap.bytes}")
            val back = assertNotNull(context.contentResolver.openInputStream(s.uri)!!.use { NightSeries.read(it) })
            assertContentEquals(y, back.entries.getValue(NightSeries.lumaName(0)))
            val path = context.contentResolver.query(s.uri, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)!!
                .use { c -> c.moveToFirst(); c.getString(0) }
            assertEquals("${MediaStoreSeriesAdapter.FOLDER}/", path)
        } finally {
            context.contentResolver.delete(s.uri, null, null)
        }
        // Abbruch: halbe Datei geloescht, weitere Eintraege werden abgelehnt
        val a = assertNotNull(adapter.open("Nachtserie")) as MediaStoreSeriesAdapter.SessionAdapter
        assertTrue(a.put(NightSeries.lumaName(0), y))
        a.abort(); a.abort()
        assertTrue(!exists(a.uri), "abgebrochene Datei existiert noch")
        assertTrue(!a.put(NightSeries.lumaName(1), y))
    }
}
