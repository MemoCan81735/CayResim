package app.cayresim.core.data

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import app.cayresim.core.boundary.IoDispatcher
import app.cayresim.core.boundary.MediaBoundary
import app.cayresim.core.boundary.PhotoSnapshot
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import javax.inject.Inject
import javax.inject.Singleton

/** Fotos der App aus der Galerie, aktualisiert sich bei jeder Aenderung. */
@Singleton
class MediaStoreMediaAdapter @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) : MediaBoundary {

    override fun recentPhotos(limit: Int): Flow<List<PhotoSnapshot>> = callbackFlow {
        val resolver = context.contentResolver
        fun load() { trySend(query(limit)) }
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = load()
        }
        resolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
        load()
        awaitClose { resolver.unregisterContentObserver(observer) }
    }.flowOn(io)

    internal fun query(limit: Int): List<PhotoSnapshot> {
        if (limit <= 0) return emptyList()
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_ADDED)
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        val args = arrayOf("$PHOTO_DIR%")
        val sort = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        val result = mutableListOf<PhotoSnapshot>()
        runCatching {
            context.contentResolver.query(uri, projection, selection, args, sort)?.use { c ->
                val id = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val taken = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                val added = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                while (c.moveToNext() && result.size < limit) {
                    val t = if (c.isNull(taken)) c.getLong(added) * 1000 else c.getLong(taken)
                    result += PhotoSnapshot(ContentUris.withAppendedId(uri, c.getLong(id)).toString(), t)
                }
            }
        }
        return result
    }

    companion object { const val PHOTO_DIR = "Pictures/CayResim" }
}
