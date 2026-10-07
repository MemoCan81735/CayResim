package app.cayresim.feature.camera.control

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.cayresim.core.boundary.CameraBoundary
import app.cayresim.core.boundary.CameraStateSnapshot
import app.cayresim.core.boundary.CameraStatus
import app.cayresim.core.boundary.CaptureFailure
import app.cayresim.core.boundary.Look
import app.cayresim.core.boundary.PhotoMode
import app.cayresim.core.boundary.ProcessingBoundary
import app.cayresim.core.boundary.SeriesBoundary
import app.cayresim.core.boundary.SeriesResult
import app.cayresim.core.boundary.SeriesSnapshot
import app.cayresim.core.control.CaptureOutcome
import app.cayresim.core.control.CaptureUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CameraViewModel @Inject constructor(
    private val camera: CameraBoundary,
    private val capture: CaptureUseCase,
    private val seriesBoundary: SeriesBoundary,
    private val processing: ProcessingBoundary,
) : ViewModel() {

    private data class Local(
        val permission: PermissionStatus = PermissionStatus.UNKNOWN,
        val visible: Boolean = false,
        val lastPhotoUri: String? = null,
        val message: UserMessage? = null,
        val nextMessageId: Long = 1,
        val look: LookOption = LookOption.NONE,
        val selectedSeriesId: Long? = null,
        val overlayAlpha: Float = 0.4f,
    )

    private val local = MutableStateFlow(Local())
    private val allSeries = seriesBoundary.series().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Juengstes Foto der gewaehlten Serie als Overlay, neu geladen nur wenn es sich aendert. */
    private val overlay = combine(local.map { it.selectedSeriesId }.distinctUntilChanged(), allSeries) { id, list ->
        list.firstOrNull { it.id == id }?.latestUri
    }.distinctUntilChanged().flatMapLatest { uri ->
        if (uri == null) flowOf(null) else flowOf(uri).map { u ->
            (processing.loadPreview(u, OVERLAY_PX)?.token as? Bitmap)?.asImageBitmap()
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val uiState: StateFlow<CameraUiState> = combine(camera.state, local, allSeries, overlay) { s, l, series, ov -> toUi(s, l, series, ov) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, CameraUiState())

    fun onPermissionResult(granted: Boolean) {
        local.update { it.copy(permission = if (granted) PermissionStatus.GRANTED else PermissionStatus.DENIED) }
        if (granted && local.value.visible) viewModelScope.launch { camera.start() }
    }

    fun onScreenStart() {
        local.update { it.copy(visible = true) }
        if (local.value.permission == PermissionStatus.GRANTED) viewModelScope.launch { camera.start() }
    }

    fun onScreenStop() {
        local.update { it.copy(visible = false) }
        viewModelScope.launch { camera.stop() }
    }

    fun onModeSelected(mode: ModeOption) {
        viewModelScope.launch { camera.selectMode(PhotoMode.valueOf(mode.name)) }
    }

    /** Naechster Look in fester Reihenfolge. */
    fun onNextLook() = local.update { it.copy(look = LookOption.entries[(it.look.ordinal + 1) % LookOption.entries.size]) }

    fun onSeriesSelected(id: Long?) = local.update { it.copy(selectedSeriesId = id) }

    fun onOverlayAlpha(alpha: Float) = local.update { it.copy(overlayAlpha = alpha.coerceIn(0f, 0.9f)) }

    fun onCreateSeries(name: String) {
        viewModelScope.launch {
            when (val r = seriesBoundary.create(name)) {
                is SeriesResult.Ok -> post(MessageKind.SERIES_CREATED) { it.copy(selectedSeriesId = r.series.id) }
                is SeriesResult.Failed -> post(MessageKind.SERIES_INVALID)
            }
        }
    }

    fun onShutter() {
        val l = local.value
        viewModelScope.launch {
            when (val r = capture(Look.valueOf(l.look.name), l.selectedSeriesId)) {
                is CaptureOutcome.Saved -> {
                    val kind = when {
                        r.lookFailed -> MessageKind.SAVED_WITHOUT_LOOK
                        r.seriesFailed -> MessageKind.SAVED_WITHOUT_SERIES
                        else -> MessageKind.SAVED
                    }
                    post(kind) { it.copy(lastPhotoUri = r.uri) }
                }
                is CaptureOutcome.Failed -> when (r.reason) {
                    CaptureFailure.BUSY -> Unit
                    CaptureFailure.STORAGE -> post(MessageKind.FAILED_STORAGE)
                    CaptureFailure.NOT_READY, CaptureFailure.CAMERA_CLOSED -> post(MessageKind.FAILED_CAMERA)
                    CaptureFailure.UNKNOWN -> post(MessageKind.FAILED_OTHER)
                }
            }
        }
    }

    fun onMessageShown(id: Long) {
        local.update { if (it.message?.id == id) it.copy(message = null) else it }
    }

    private fun post(kind: MessageKind, extra: (Local) -> Local = { it }) {
        local.update { l -> extra(l).copy(message = UserMessage(l.nextMessageId, kind), nextMessageId = l.nextMessageId + 1) }
    }

    private fun toUi(s: CameraStateSnapshot, l: Local, series: List<SeriesSnapshot>, ov: androidx.compose.ui.graphics.ImageBitmap?) = CameraUiState(
        permission = l.permission,
        status = when (s.status) {
            CameraStatus.IDLE -> ScreenStatus.IDLE
            CameraStatus.STARTING -> ScreenStatus.STARTING
            CameraStatus.RUNNING -> ScreenStatus.RUNNING
            CameraStatus.ERROR -> ScreenStatus.ERROR
        },
        modes = s.offeredModes.map { ModeOption.valueOf(it.name) },
        selected = ModeOption.valueOf(s.requestedMode.name),
        active = ModeOption.valueOf(s.activeMode.name),
        fallbackFrom = s.fallbackFrom?.let { ModeOption.valueOf(it.name) },
        previewToken = s.preview?.token,
        capturing = s.capturing,
        lastPhotoUri = l.lastPhotoUri,
        message = l.message,
        look = l.look,
        series = series.map { SeriesOption(it.id, it.name, it.photoUris.size) },
        selectedSeriesId = l.selectedSeriesId?.takeIf { id -> series.any { it.id == id } },
        overlay = ov,
        overlayAlpha = l.overlayAlpha,
    )

    companion object { const val OVERLAY_PX = 1440 }
}
