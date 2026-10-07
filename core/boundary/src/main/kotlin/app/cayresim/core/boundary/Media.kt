package app.cayresim.core.boundary

import kotlinx.coroutines.flow.Flow

data class PhotoSnapshot(val uri: String, val takenAtMillis: Long)

/** Gespeicherte Fotos der App (Pictures/CayResim). */
interface MediaBoundary {
    fun recentPhotos(limit: Int): Flow<List<PhotoSnapshot>>
}
