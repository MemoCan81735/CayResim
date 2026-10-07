package app.cayresim.feature.gallery.control

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.cayresim.core.boundary.MediaBoundary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@Immutable
data class PhotoItem(val uri: String, val takenAtMillis: Long)

@Immutable
data class GalleryUiState(val loading: Boolean = true, val photos: List<PhotoItem> = emptyList())

@HiltViewModel
class GalleryViewModel @Inject constructor(media: MediaBoundary) : ViewModel() {
    val uiState: StateFlow<GalleryUiState> = media.recentPhotos(LIMIT)
        .map { list -> GalleryUiState(loading = false, photos = list.map { PhotoItem(it.uri, it.takenAtMillis) }) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GalleryUiState())

    companion object { const val LIMIT = 200 }
}
