package app.cayresim.core.data

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import app.cayresim.core.boundary.AudioFileBoundary
import app.cayresim.core.boundary.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tondateien des Mikrofon-Tests (S-008) in der Medienablage unter Recordings/CayResim/<Ordner>, damit sie sich
 * ueber die Dateien-App teilen lassen. Schreibt auf dem IoDispatcher (R18); Fehler ergeben null (R24).
 */
@Singleton
class MediaStoreAudioFileAdapter @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) : AudioFileBoundary {

    override suspend fun newFolder(prefix: String): String? {
        if (prefix.isBlank() || prefix.any { it == '/' || it == '\\' }) return null
        return "$BASE/$prefix-${LocalDateTime.now().format(STAMP)}"
    }

    override suspend fun saveWav(folder: String, fileName: String, wav: ByteArray): String? = withContext(io) {
        if (!folder.startsWith(BASE) || fileName.contains('/') || !fileName.endsWith(".wav")) return@withContext null
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/x-wav")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$folder/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = runCatching { resolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) }.getOrNull()
            ?: return@withContext null
        val ok = runCatching {
            resolver.openOutputStream(uri)?.use { it.write(wav) } ?: error("kein Ausgabestrom")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        }.isSuccess
        if (ok) uri.toString() else { runCatching { resolver.delete(uri, null, null) }; null }
    }

    companion object {
        const val BASE = "Recordings/CayResim"
        private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    }
}
