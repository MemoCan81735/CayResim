package app.cayresim.feature.camera.control

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap

/** Modi aus Sicht der Oberflaeche; die UI kennt keine Boundary-Typen (R1). */
enum class ModeOption { NORMAL, AUTO, NIGHT, HDR, BOKEH, FACE_RETOUCH }

enum class PermissionStatus { UNKNOWN, GRANTED, DENIED }

enum class ScreenStatus { IDLE, STARTING, RUNNING, ERROR }

enum class MessageKind { SAVED, FAILED_STORAGE, FAILED_CAMERA, FAILED_OTHER, SAVED_WITHOUT_LOOK, SAVED_WITHOUT_SERIES, SERIES_CREATED, SERIES_INVALID, STACK_SAVED, STACK_SHORTENED, STACK_FAILED, TRIGGER_FIRED, FIXED_FOCUS, NO_MANUAL, SAVED_WITH_RAW, NIGHT_SAVED, NIGHT_FAILED }

enum class LookOption { NONE, WARM, COOL, FILM, MONO }

/** Spezialaufnahmen der eigenen Pipeline (Phase 3). */
enum class SpecialOption { NONE, CLEAN_PLATE, LONG_EXPOSURE, TRIGGER_MOTION, TRIGGER_STILL, PRO, FOCUS_STACK, ASTRO }

/** Manuelle Werte fuer das Pro-Panel; Regler laufen von 0 bis 1, null = Automatik. */
@Immutable
data class ProUi(
    val canExpose: Boolean = false,
    val canFocus: Boolean = false,
    val canRaw: Boolean = false,
    val exposure: Float? = null,
    val iso: Float? = null,
    val focus: Float? = null,
    val raw: Boolean = false,
    val exposureLabel: String = "Auto",
    val isoLabel: String = "Auto",
)

enum class SpecialStatus { IDLE, COLLECTING, PROCESSING, ARMED }

@Immutable
data class SeriesOption(val id: Long, val name: String, val photoCount: Int)

/** Einmalige Meldung als Teil des Zustands; die UI bestaetigt sie mit [CameraViewModel.onMessageShown] (R22). */
@Immutable
data class UserMessage(val id: Long, val kind: MessageKind, val night: NightInfo? = null)

/** Kennzahlen einer Nachtaufnahme fuer den Hinweis; den Text baut die Oberflaeche (R23). */
@Immutable
data class NightInfo(val exposureNs: Long?, val iso: Int?, val used: Int, val dropped: Int, val gain: Float)

/** Eine Zoom-Schnellwahl, z. B. "0,6x"; [active] = sie entspricht dem aktuellen Zoom. */
@Immutable
data class ZoomPresetUi(val ratio: Float, val label: String, val active: Boolean)

/** Anzeige wie "0,6x", "1x", "2,4x" (deutsches Komma). */
fun zoomLabel(ratio: Float): String {
    val tenths = Math.round(ratio * 10)
    return if (tenths % 10 == 0) "${tenths / 10}x" else "${tenths / 10},${tenths % 10}x"
}

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
    val special: SpecialOption = SpecialOption.NONE,
    val specialStatus: SpecialStatus = SpecialStatus.IDLE,
    val pro: ProUi = ProUi(),
    /** Automatik hat Dunkelheit erkannt: der Ausloeser startet den Nacht-Kern. */
    val autoNight: Boolean = false,
    val zoomRatio: Float = 1f,
    val zoomPresets: List<ZoomPresetUi> = emptyList(),
) {
    val zoomLabel: String get() = zoomLabel(zoomRatio)

    val canShoot: Boolean get() = status == ScreenStatus.RUNNING && !capturing &&
        (specialStatus == SpecialStatus.IDLE || specialStatus == SpecialStatus.ARMED)
}
