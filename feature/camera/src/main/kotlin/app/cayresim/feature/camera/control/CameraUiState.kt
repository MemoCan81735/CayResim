package app.cayresim.feature.camera.control

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap

/** Modi aus Sicht der Oberflaeche; die UI kennt keine Boundary-Typen (R1). */
enum class ModeOption { NORMAL, AUTO, NIGHT, HDR, BOKEH, FACE_RETOUCH }

enum class PermissionStatus { UNKNOWN, GRANTED, DENIED }

enum class ScreenStatus { IDLE, STARTING, RUNNING, ERROR }

enum class MessageKind { SAVED, FAILED_STORAGE, FAILED_CAMERA, FAILED_OTHER, SAVED_WITHOUT_LOOK, SAVED_WITHOUT_SERIES, SERIES_CREATED, SERIES_INVALID }

enum class LookOption { NONE, WARM, COOL, FILM, MONO }

@Immutable
data class SeriesOption(val id: Long, val name: String, val photoCount: Int)

/** Einmalige Meldung als Teil des Zustands; die UI bestaetigt sie mit [CameraViewModel.onMessageShown] (R22). */
@Immutable
data class UserMessage(val id: Long, val kind: MessageKind)

@Immutable
data class CameraUiState(
    val permission: PermissionStatus = PermissionStatus.UNKNOWN,
    val status: ScreenStatus = ScreenStatus.IDLE,
    val modes: List<ModeOption> = listOf(ModeOption.NORMAL),
    val selected: ModeOption = ModeOption.NORMAL,
    val active: ModeOption = ModeOption.NORMAL,
    val fallbackFrom: ModeOption? = null,
    /** Undurchsichtiger Sucher-Wert (A1). */
    val previewToken: Any? = null,
    val capturing: Boolean = false,
    val lastPhotoUri: String? = null,
    val message: UserMessage? = null,
    val look: LookOption = LookOption.NONE,
    val series: List<SeriesOption> = emptyList(),
    val selectedSeriesId: Long? = null,
    /** Geister-Overlay: juengstes Foto der gewaehlten Serie. */
    val overlay: ImageBitmap? = null,
    val overlayAlpha: Float = 0.4f,
) {
    val canShoot: Boolean get() = status == ScreenStatus.RUNNING && !capturing
}
