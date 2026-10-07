package app.cayresim.core.processing

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import app.cayresim.core.pure.TimelapsePlan
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Zeitraffer aus Fotos mit Media3 Transformer. Muss auf einem Thread mit Looper laufen (Main). */
internal object TimelapseExporter {
    suspend fun export(context: Context, uris: List<String>, plan: TimelapsePlan, outPath: String): Boolean {
        val durationMs = plan.frameDurationMicros / 1000
        val items = uris.map { u ->
            val media = MediaItem.Builder().setUri(u).setMimeType(MimeTypes.IMAGE_JPEG).setImageDurationMs(durationMs).build()
            EditedMediaItem.Builder(media).setFrameRate(plan.fps).build()
        }
        val composition = Composition.Builder(EditedMediaItemSequence.Builder(items).build()).build()
        return suspendCancellableCoroutine { cont ->
            val transformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) { if (cont.isActive) cont.resume(true) }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) { if (cont.isActive) cont.resume(false) }
                }).build()
            cont.invokeOnCancellation { runCatching { transformer.cancel() } }
            runCatching { transformer.start(composition, outPath) }.onFailure { if (cont.isActive) cont.resume(false) }
        }
    }
}
