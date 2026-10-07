package app.cayresim.core.processing

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cayresim.core.boundary.Look
import app.cayresim.core.boundary.ProcessFailure
import app.cayresim.core.boundary.ProcessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Testkonzept Phase 2: Look-Datei, Vorschau und Zeitraffer mit richtiger Laenge, auf dem Emulator. */
@RunWith(AndroidJUnit4::class)
class ProcessingAdapterTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val gpu = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val adapter = GlProcessingAdapter(ctx, gpu, Dispatchers.IO, Dispatchers.Main)
    private val created = mutableListOf<Uri>()

    @After fun cleanUp() { created.forEach { runCatching { ctx.contentResolver.delete(it, null, null) } }; gpu.close() }

    private fun photo(color: Int, w: Int = 640, h: Int = 480): String {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "test_${System.nanoTime()}"); put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/CayResimTest")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        ctx.contentResolver.openOutputStream(uri)!!.use { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        created += uri; return uri.toString()
    }

    private fun track(r: ProcessResult) { if (r is ProcessResult.Saved) created += Uri.parse(r.uri) }

    @Test fun lookWirdAlsNeuesFotoGespeichert() = runBlocking {
        val r = adapter.applyLook(photo(Color.rgb(200, 100, 50)), Look.MONO); track(r)
        val saved = assertIs<ProcessResult.Saved>(r)
        val bmp = assertNotNull(adapter.decodeOriented(Uri.parse(saved.uri), 64))
        val c = bmp.getPixel(bmp.width / 2, bmp.height / 2)
        assertTrue(kotlin.math.abs(Color.red(c) - Color.blue(c)) <= 3, "Mono muss grau sein: ${Integer.toHexString(c)}")
    }

    @Test fun fehlendeQuelleBeimLook() = runBlocking {
        assertEquals(ProcessResult.Failed(ProcessFailure.SOURCE_MISSING), adapter.applyLook("content://media/external/images/media/999999999", Look.FILM))
    }

    @Test fun vorschauIstBegrenzt() = runBlocking {
        val h = assertNotNull(adapter.loadPreview(photo(Color.GREEN, 4000, 3000), 1440))
        assertTrue(maxOf(h.width, h.height) <= 1440)
        assertEquals(null, adapter.loadPreview(photo(Color.GREEN), 0))
    }

    @Test fun zeitrafferHatDieRichtigeLaenge() = runBlocking {
        val photos = (0 until 10).map { photo(Color.rgb(it * 25, 80, 255 - it * 25)) }
        val r = adapter.timelapse(photos, 10); track(r)
        val saved = assertIs<ProcessResult.Saved>(r, "Zeitraffer fehlgeschlagen: $r")
        val mmr = MediaMetadataRetriever().apply { setDataSource(ctx, Uri.parse(saved.uri)) }
        val ms = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
        mmr.release()
        assertTrue(ms in 800..1300, "10 Fotos bei 10 je Sekunde muessen etwa 1 s ergeben, waren $ms ms")
    }

    @Test fun zeitrafferMitFehlenderQuelle() = runBlocking {
        val r = adapter.timelapse(listOf(photo(Color.RED), "content://media/external/images/media/999999999"), 10)
        assertEquals(ProcessResult.Failed(ProcessFailure.SOURCE_MISSING), r)
    }

    @Test fun zeitrafferOhneFotos() = runBlocking {
        assertEquals(ProcessResult.Failed(ProcessFailure.INVALID_INPUT), adapter.timelapse(emptyList(), 10))
        assertEquals(ProcessResult.Failed(ProcessFailure.INVALID_INPUT), adapter.timelapse(listOf("x"), 0))
    }
}
