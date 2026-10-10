package app.cayresim.core.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import app.cayresim.core.boundary.IoDispatcher
import app.cayresim.core.boundary.SeriesArchiveBoundary
import app.cayresim.core.boundary.SeriesArchiveSessionBoundary
import app.cayresim.core.boundary.SeriesArchiveSnapshot
import app.cayresim.core.pure.NightSeries
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mess-Archive (S-011, V3) als ZIP unter Download/CayResim, damit Arslan sie teilen kann. Geschrieben wird waehrend der
 * Serie auf dem IoDispatcher (R18); eine abgebrochene oder fehlerhafte Datei wird geloescht (R17, R24).
 */
@Singleton
class MediaStoreSeriesAdapter @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) : SeriesArchiveBoundary {

    // NonCancellable: eine angelegte Datei kommt immer beim Aufrufer an, der sie beim Abbruch loescht (Zweitpruefung S-011, B4)
    override suspend fun open(prefix: String): SeriesArchiveSessionBoundary? = withContext(NonCancellable + io) {
        if (prefix.isBlank() || prefix.any { it == '/' || it == '\\' }) return@withContext null
        val resolver = context.contentResolver
        val name = "$prefix-${LocalDateTime.now().format(STAMP)}.zip"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$FOLDER/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = runCatching { resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) }.getOrNull()
            ?: return@withContext null
        val out = runCatching { resolver.openOutputStream(uri) }.getOrNull()
        if (out == null) { runCatching { resolver.delete(uri, null, null) }; return@withContext null }
        val counting = Counting(BufferedOutputStream(out, BUFFER))
        // Gibt es den Namen schon (zwei Serien in einer Sekunde), benennt MediaStore um: der Hinweis zeigt den echten
        SessionAdapter(uri, displayName(uri) ?: name, counting, NightSeries.ZipWriter(counting))
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()
    }

    /** Zaehlt die geschriebenen Bytes fuer den Hinweis (Groesse der Datei). */
    internal class Counting(out: OutputStream) : FilterOutputStream(out) {
        var count = 0L; private set
        override fun write(b: Int) { out.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); count += len }
    }

    internal inner class SessionAdapter(
        internal val uri: Uri,
        private val name: String,
        private val counting: Counting,
        private val writer: NightSeries.ZipWriter,
    ) : SeriesArchiveSessionBoundary {
        private val lock = Mutex()
        private var closed = false
        private var failed = false

        override suspend fun put(name: String, bytes: ByteArray): Boolean = withContext(io) {
            lock.withLock {
                if (closed || failed) return@withLock false
                runCatching { writer.put(name, bytes) }.onFailure { failed = true }.isSuccess
            }
        }

        override suspend fun finish(): SeriesArchiveSnapshot? = withContext(NonCancellable + io) {
            lock.withLock {
                if (closed) return@withLock null
                closed = true
                // Schliessen immer, auch nach einem Schreibfehler; der innere Strom zusaetzlich, falls das Packen dabei wirft
                val closedOk = runCatching { writer.close() }.isSuccess
                runCatching { counting.close() }
                val ok = !failed && closedOk && runCatching {
                    context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                }.isSuccess
                if (ok) SeriesArchiveSnapshot(name, counting.count) else { runCatching { context.contentResolver.delete(uri, null, null) }; null }
            }
        }

        override suspend fun abort() = withContext(NonCancellable + io) {
            lock.withLock {
                if (closed) return@withLock
                closed = true
                runCatching { writer.close() }
                runCatching { counting.close() }
                runCatching { context.contentResolver.delete(uri, null, null) }
                Unit
            }
        }
    }

    companion object {
        const val FOLDER = "Download/CayResim"
        private const val BUFFER = 1 shl 16
        private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    }
}
