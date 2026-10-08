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
    private val adapter = GlProcessingAdapter(ctx, gpu, Dispatchers.IO, Dispatchers.Main, Dispatchers.Default)
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
        val saved = assertIs<ProcessResult.Saved>(r, "Look fehlgeschlagen: $r")
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

    @Test fun stapelMitMedianEntferntBewegtesUndWirdGespeichert() = runBlocking {
        val w = 120; val h = 90
        val bg = ByteArray(w * h * 3) { ((it * 7) % 256).toByte() }
        val frames = (0 until 9).map { k -> bg.copyOf().also { f -> for (y in 20 until 60) for (x in k * 10 until k * 10 + 15) { val i = (y * w + x) * 3; f[i] = 0; f[i + 1] = 0; f[i + 2] = 0 } } }
        val r = adapter.stack(app.cayresim.core.boundary.FrameBurst(w, h, frames), app.cayresim.core.boundary.StackMode.MEDIAN); track(r)
        val saved = assertIs<ProcessResult.Saved>(r, "Stapeln fehlgeschlagen: $r")
        val bmp = assertNotNull(adapter.decodeOriented(Uri.parse(saved.uri), 1000))
        assertEquals(w, bmp.width); assertEquals(h, bmp.height)
        // Hintergrund bleibt (JPEG erlaubt kleine Abweichungen), kein schwarzer Fleck uebrig
        val c = bmp.getPixel(50, 40); val i = (40 * w + 50) * 3
        assertTrue(kotlin.math.abs(Color.red(c) - (bg[i].toInt() and 0xFF)) < 40, "Bewegtes Objekt ist noch sichtbar")
    }

    /** Fehler vom S24+: "Langzeit" im Dunkeln blieb schwarz (Mittel 1,5 von 255). Jetzt wird aufgehellt. */
    @Test fun dunkleLangzeitSerieWirdAufgehellt() = runBlocking {
        val w = 64; val h = 48
        val rng = java.util.Random(4711)
        val frames = List(20) { ByteArray(w * h * 3) { (1 + rng.nextInt(3) - 1).toByte() } }
        val r = adapter.stack(app.cayresim.core.boundary.FrameBurst(w, h, frames), app.cayresim.core.boundary.StackMode.MEAN); track(r)
        val bmp = assertNotNull(adapter.decodeOriented(Uri.parse(assertIs<ProcessResult.Saved>(r).uri), 1000))
        val c = bmp.getPixel(w / 2, h / 2)
        assertTrue(Color.red(c) >= 8, "Ergebnis muss heller sein als die Einzelbilder (1), war ${Color.red(c)}")
    }

    @Test fun stapelMitUngleichenBildernWirdAbgelehnt() = runBlocking {
        val r = adapter.stack(app.cayresim.core.boundary.FrameBurst(10, 10, listOf(ByteArray(300), ByteArray(299))), app.cayresim.core.boundary.StackMode.MEAN)
        assertEquals(ProcessResult.Failed(ProcessFailure.INVALID_INPUT), r)
    }

    @Test fun stapelDreht() = runBlocking {
        val r = adapter.stack(app.cayresim.core.boundary.FrameBurst(40, 20, listOf(ByteArray(40 * 20 * 3) { 100 }), rotationDegrees = 90), app.cayresim.core.boundary.StackMode.MEAN); track(r)
        val bmp = assertNotNull(adapter.decodeOriented(Uri.parse(assertIs<ProcessResult.Saved>(r).uri), 1000))
        assertEquals(20, bmp.width); assertEquals(40, bmp.height)
    }
}
