package app.cayresim.core.processing

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import app.cayresim.core.boundary.GpuDispatcher
import app.cayresim.core.boundary.ImageHandle
import app.cayresim.core.boundary.IoDispatcher
import app.cayresim.core.boundary.MainDispatcher
import app.cayresim.core.boundary.ComputeDispatcher
import app.cayresim.core.boundary.FrameBurst
import app.cayresim.core.boundary.StackMode
import app.cayresim.core.pure.Stacking
import app.cayresim.core.pure.FocusStacking
import app.cayresim.core.pure.StarAlignment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import app.cayresim.core.pure.timelapsePlan
import java.io.File
import app.cayresim.core.boundary.Look
import app.cayresim.core.boundary.ProcessFailure
import app.cayresim.core.boundary.ProcessResult
import app.cayresim.core.boundary.ProcessingBoundary
import app.cayresim.core.pure.LookId
import app.cayresim.core.pure.Looks
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** GPU-Verarbeitung: Looks per 3D-LUT (Phase 2). Zeitraffer folgt mit Media3. */
@Singleton
class GlProcessingAdapter @Inject constructor(
    @ApplicationContext private val context: Context,
    @GpuDispatcher private val gpu: CoroutineDispatcher,
    @IoDispatcher private val io: CoroutineDispatcher,
    @MainDispatcher private val main: CoroutineDispatcher,
    @ComputeDispatcher private val compute: CoroutineDispatcher,
) : ProcessingBoundary {

    private val renderer = GlLutRenderer()

    override suspend fun loadPreview(uri: String, maxPx: Int): ImageHandle? = withContext(io) {
        if (maxPx <= 0) return@withContext null
        runCatching { decodeOriented(Uri.parse(uri), maxPx) }.getOrNull()?.let { ImageHandle(it, it.width, it.height) }
    }

    override suspend fun applyLook(uri: String, look: Look): ProcessResult {
        val source = withContext(io) { runCatching { decodeOriented(Uri.parse(uri), Int.MAX_VALUE) }.getOrNull() }
            ?: return ProcessResult.Failed(ProcessFailure.SOURCE_MISSING)
        if (look == Look.NONE) return withContext(io) { save(source, "orig") }
        val rendered = withContext(gpu) { runCatching { renderer.render(source, Looks.lut(LookId.valueOf(look.name))) }.getOrNull() }
        source.recycle()
        rendered ?: return ProcessResult.Failed(ProcessFailure.GPU)
        return withContext(io) { save(rendered, look.name.lowercase()).also { rendered.recycle() } }
    }

    override suspend fun timelapse(photoUris: List<String>, photosPerSecond: Int): ProcessResult {
        if (photoUris.isEmpty() || photosPerSecond !in 1..60) return ProcessResult.Failed(ProcessFailure.INVALID_INPUT)
        val plan = timelapsePlan(photoUris.size, photosPerSecond)
        // Fehlende Quellen vorher erkennen, statt den Encoder scheitern zu lassen
        val missing = withContext(io) { photoUris.any { u -> runCatching { context.contentResolver.openInputStream(Uri.parse(u))?.use { true } ?: false }.getOrDefault(false).not() } }
        if (missing) return ProcessResult.Failed(ProcessFailure.SOURCE_MISSING)
        val out = File(context.cacheDir, "timelapse_${System.nanoTime()}.mp4")
        val ok = withContext(main) { TimelapseExporter.export(context, photoUris, plan, out.absolutePath) }
        if (!ok) { out.delete(); return ProcessResult.Failed(ProcessFailure.ENCODER) }
        return withContext(io) { publishVideo(out).also { out.delete() } }
    }

    override suspend fun stack(burst: FrameBurst, mode: StackMode): ProcessResult {
        if (burst.frames.isEmpty() || burst.width <= 0 || burst.height <= 0 || burst.frames.any { it.size != burst.pixels * 3 })
            return ProcessResult.Failed(ProcessFailure.INVALID_INPUT)
        val rgb = try {
            withContext(compute) {
                when (mode) {
                    StackMode.MEDIAN -> parallelStack(burst, median = true)
                    StackMode.MEAN -> parallelStack(burst, median = false)
                    StackMode.FOCUS -> FocusStacking.stack(burst.frames, burst.width, burst.height) { ensureActive() }.second
                    StackMode.STARS -> StarAlignment.alignAndMean(burst.frames, burst.width, burst.height, maxShift = minOf(16, burst.width / 4, burst.height / 4))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return ProcessResult.Failed(ProcessFailure.INVALID_INPUT)
        }
        val bitmap = withContext(compute) { rgbToBitmap(rgb, burst.width, burst.height, burst.rotationDegrees) }
        return withContext(io) { save(bitmap, when (mode) { StackMode.MEDIAN -> "ohne_bewegung"; StackMode.MEAN -> "langzeit"; StackMode.FOCUS -> "fokus"; StackMode.STARS -> "sterne" }).also { bitmap.recycle() } }
    }

    /** Teilt das Bild in Baender, jedes Band rechnet ein eigener Kern; zwischen den Kacheln wird auf Abbruch geprueft (R17). */
    private suspend fun parallelStack(burst: FrameBurst, median: Boolean): ByteArray = coroutineScope {
        val out = ByteArray(burst.pixels * 3)
        val bands = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
        val per = (burst.pixels + bands - 1) / bands
        (0 until bands).map { b ->
            async {
                var p = b * per; val end = minOf(burst.pixels, p + per)
                while (p < end) {
                    ensureActive()
                    val e = minOf(end, p + TILE)
                    if (median) Stacking.medianRange(burst.frames, out, p, e) else Stacking.meanRange(burst.frames, out, p, e)
                    p = e
                }
            }
        }.awaitAll()
        out
    }

    private fun rgbToBitmap(rgb: ByteArray, w: Int, h: Int, rotation: Int): Bitmap {
        val px = IntArray(w * h)
        for (i in px.indices) px[i] = (0xFF shl 24) or ((rgb[i * 3].toInt() and 0xFF) shl 16) or ((rgb[i * 3 + 1].toInt() and 0xFF) shl 8) or (rgb[i * 3 + 2].toInt() and 0xFF)
        val bmp = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        if (rotation % 360 == 0) return bmp
        val m = Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(bmp, 0, 0, w, h, m, true).also { if (it !== bmp) bmp.recycle() }
    }

    private fun publishVideo(file: File): ProcessResult {
        val name = "CAY_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.ROOT).format(Date()) + "_zeitraffer"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, VIDEO_DIR)
        }
        val resolver = context.contentResolver
        val target = runCatching { resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) }.getOrNull()
            ?: return ProcessResult.Failed(ProcessFailure.STORAGE)
        val ok = runCatching { resolver.openOutputStream(target)?.use { o -> file.inputStream().use { it.copyTo(o) } } != null }.getOrDefault(false)
        if (!ok) { runCatching { resolver.delete(target, null, null) }; return ProcessResult.Failed(ProcessFailure.STORAGE) }
        return ProcessResult.Saved(target.toString())
    }

    /** Dekodiert mit Ausrichtung aus EXIF, laengste Seite hoechstens [maxPx]. */
    internal fun decodeOriented(uri: Uri, maxPx: Int): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // Mit inJustDecodeBounds liefert decodeStream immer null; nur die Masse in bounds zaehlen.
        val opened = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds); true } ?: false
        if (!opened) return null
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx && maxPx != Int.MAX_VALUE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bmp = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val rotation = resolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
        val scaled = if (maxPx != Int.MAX_VALUE && maxOf(bmp.width, bmp.height) > maxPx) {
            val f = maxPx.toFloat() / maxOf(bmp.width, bmp.height)
            Bitmap.createScaledBitmap(bmp, (bmp.width * f).toInt().coerceAtLeast(1), (bmp.height * f).toInt().coerceAtLeast(1), true).also { if (it !== bmp) bmp.recycle() }
        } else bmp
        if (rotation == 0) return scaled
        val m = Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(scaled, 0, 0, scaled.width, scaled.height, m, true).also { if (it !== scaled) scaled.recycle() }
    }

    private fun save(bitmap: Bitmap, suffix: String): ProcessResult {
        val name = "CAY_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.ROOT).format(Date()) + "_$suffix"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, PHOTO_DIR)
        }
        val resolver = context.contentResolver
        val target = runCatching { resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) }.getOrNull()
            ?: return ProcessResult.Failed(ProcessFailure.STORAGE)
        val ok = runCatching { resolver.openOutputStream(target)?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) } == true }.getOrDefault(false)
        if (!ok) { runCatching { resolver.delete(target, null, null) }; return ProcessResult.Failed(ProcessFailure.STORAGE) }
        return ProcessResult.Saved(target.toString())
    }

    companion object {
        const val PHOTO_DIR = "Pictures/CayResim"
        const val VIDEO_DIR = "Movies/CayResim"
        const val TILE = 16_384
    }
}
