package app.cayresim.core.boundary.fake

import app.cayresim.core.boundary.MediaBoundary
import app.cayresim.core.boundary.PhotoSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class FakeMediaBoundary : MediaBoundary {
    val photos = MutableStateFlow<List<PhotoSnapshot>>(emptyList())
    override fun recentPhotos(limit: Int): Flow<List<PhotoSnapshot>> =
        photos.map { list -> list.sortedByDescending { it.takenAtMillis }.take(limit.coerceAtLeast(0)) }
}
