package app.cayresim.feature.gallery.control

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.cayresim.core.boundary.MediaBoundary
import app.cayresim.core.boundary.ProcessResult
import app.cayresim.core.boundary.ProcessingBoundary
import app.cayresim.core.boundary.SeriesBoundary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@Immutable
data class PhotoItem(val uri: String, val takenAtMillis: Long)

@Immutable
data class SeriesItem(val id: Long, val name: String, val photoCount: Int)

enum class GalleryMessage { VIDEO_SAVED, VIDEO_FAILED, VIDEO_TOO_FEW }

@Immutable
data class GalleryUiState(
    val loading: Boolean = true,
    val photos: List<PhotoItem> = emptyList(),
    val series: List<SeriesItem> = emptyList(),
    val renderingSeriesId: Long? = null,
    val lastVideoUri: String? = null,
    val message: GalleryMessage? = null,
)

@HiltViewModel
class GalleryViewModel @Inject constructor(
    media: MediaBoundary,
    private val seriesBoundary: SeriesBoundary,
    private val processing: ProcessingBoundary,
) : ViewModel() {
    private data class Local(val rendering: Long? = null, val video: String? = null, val message: GalleryMessage? = null)
    private val local = MutableStateFlow(Local())

    val uiState: StateFlow<GalleryUiState> = combine(
        media.recentPhotos(LIMIT),
        seriesBoundary.series().onStart { emit(emptyList()) },
        local,
    ) { photos, series, l ->
        GalleryUiState(
            loading = false,
            photos = photos.map { PhotoItem(it.uri, it.takenAtMillis) },
            series = series.map { SeriesItem(it.id, it.name, it.photoUris.size) },
            renderingSeriesId = l.rendering, lastVideoUri = l.video, message = l.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GalleryUiState())

    /** Zeitraffer aus allen Fotos der Serie (aelteste zuerst), 10 Fotos je Sekunde. */
    fun onTimelapse(seriesId: Long) {
        if (local.value.rendering != null) return
        local.update { it.copy(rendering = seriesId, message = null) }
        viewModelScope.launch {
            val photos = seriesBoundary.series().first().firstOrNull { it.id == seriesId }?.photoUris.orEmpty()
            val r = if (photos.size < 2) null else processing.timelapse(photos, PHOTOS_PER_SECOND)
            local.update {
                when (r) {
                    null -> it.copy(rendering = null, message = GalleryMessage.VIDEO_TOO_FEW)
                    is ProcessResult.Saved -> it.copy(rendering = null, video = r.uri, message = GalleryMessage.VIDEO_SAVED)
                    is ProcessResult.Failed -> it.copy(rendering = null, message = GalleryMessage.VIDEO_FAILED)
                }
            }
        }
    }

    fun onMessageShown() = local.update { it.copy(message = null) }

    companion object { const val LIMIT = 200; const val PHOTOS_PER_SECOND = 10 }
}

