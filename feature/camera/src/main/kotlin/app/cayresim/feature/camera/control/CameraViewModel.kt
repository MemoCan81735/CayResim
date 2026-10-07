package app.cayresim.feature.camera.control

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.cayresim.core.boundary.CameraBoundary
import app.cayresim.core.boundary.CameraStateSnapshot
import app.cayresim.core.boundary.CameraStatus
import app.cayresim.core.boundary.CaptureFailure
import app.cayresim.core.boundary.CaptureResult
import app.cayresim.core.boundary.PhotoMode
import app.cayresim.core.control.TakePhotoUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CameraViewModel @Inject constructor(
    private val camera: CameraBoundary,
    private val takePhoto: TakePhotoUseCase,
) : ViewModel() {

    private data class Local(
        val permission: PermissionStatus = PermissionStatus.UNKNOWN,
        val visible: Boolean = false,
        val lastPhotoUri: String? = null,
        val message: UserMessage? = null,
        val nextMessageId: Long = 1,
    )

    private val local = MutableStateFlow(Local())

    val uiState: StateFlow<CameraUiState> = combine(camera.state, local) { s, l -> toUi(s, l) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, CameraUiState())

    fun onPermissionResult(granted: Boolean) {
        local.update { it.copy(permission = if (granted) PermissionStatus.GRANTED else PermissionStatus.DENIED) }
        if (granted && local.value.visible) viewModelScope.launch { camera.start() }
    }

    /** Screen sichtbar (lebenszyklusbewusst aus der Composable). */
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

    fun onShutter() {
        viewModelScope.launch {
            when (val r = takePhoto()) {
                is CaptureResult.Saved -> post(MessageKind.SAVED) { it.copy(lastPhotoUri = r.uri) }
                is CaptureResult.Failed -> when (r.reason) {
                    CaptureFailure.BUSY -> Unit // Doppelklick: still ignorieren
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

    private fun toUi(s: CameraStateSnapshot, l: Local) = CameraUiState(
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
    )
}
