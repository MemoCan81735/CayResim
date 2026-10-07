package app.cayresim.core.boundary

import kotlinx.coroutines.flow.StateFlow

/** Fotomodi, die die Boundary anbietet (R13). */
enum class PhotoMode { NORMAL, AUTO, NIGHT, HDR, BOKEH, FACE_RETOUCH }

enum class CameraStatus { IDLE, STARTING, RUNNING, ERROR }

/** Faehigkeiten der Kamera, einmal beim Start abgefragt (R13). */
data class CameraCapabilitiesSnapshot(
    val modes: List<PhotoMode>,
    val lowLightBoost: Boolean,
    val ultraHdr: Boolean,
    val raw: Boolean,
)

/**
 * Undurchsichtiger Wert fuer den Sucher (Ausnahme A1). Nur der Sucher-Screen darf ihn
 * als CameraX-SurfaceRequest auspacken; alle anderen Schichten reichen ihn nur weiter.
 */
class PreviewHandle(val token: Any)

/** Aktueller Zoom, die Grenzen der Kamera (S24+: 0,6x bis 10x) und die Schnellwahl-Stufen. */
data class ZoomSnapshot(val ratio: Float = 1f, val min: Float = 1f, val max: Float = 1f, val presets: List<Float> = emptyList())

data class CameraStateSnapshot(
    val status: CameraStatus = CameraStatus.IDLE,
    val requestedMode: PhotoMode = PhotoMode.NORMAL,
    val activeMode: PhotoMode = PhotoMode.NORMAL,
    val fallbackFrom: PhotoMode? = null,
    val offeredModes: List<PhotoMode> = listOf(PhotoMode.NORMAL),
    val preview: PreviewHandle? = null,
    val capturing: Boolean = false,
    val error: CameraError? = null,
    val zoom: ZoomSnapshot = ZoomSnapshot(),
)

enum class CameraError { NO_CAMERA, IN_USE, BIND_FAILED, UNKNOWN }

enum class CaptureFailure { NOT_READY, BUSY, CAMERA_CLOSED, STORAGE, UNKNOWN }

/** Ergebnis einer Aufnahme als Wert, nie als Exception (R24). */
sealed interface CaptureResult {
    /** [rawUri]: zusaetzliche DNG-Datei, wenn RAW eingeschaltet ist. */
    data class Saved(val uri: String, val rawUri: String? = null) : CaptureResult
    data class Failed(val reason: CaptureFailure) : CaptureResult
}

/** Einzige Sicht der App auf die Kamera (R11). */
interface CameraBoundary {
    val capabilities: StateFlow<CameraCapabilitiesSnapshot?>
    val state: StateFlow<CameraStateSnapshot>

    /** Kamera oeffnen und Sucher starten. Fehler landen im Zustand, nie als Exception. */
    suspend fun start()

    /** Kamera freigeben (R15). */
    suspend fun stop()

    /** Modus waehlen; nicht verfuegbare Modi fallen auf NORMAL zurueck (R14). */
    suspend fun selectMode(mode: PhotoMode)

    suspend fun capture(): CaptureResult

    /** Zoom setzen; Werte ausserhalb der Grenzen werden begrenzt. false, wenn die Kamera nicht laeuft. */
    suspend fun setZoom(ratio: Float): Boolean

    /**
     * Scharfstellen und Belichten auf einen Punkt des Sucherbilds, normiert auf 0..1
     * (0,0 = oben links des Kamerabilds). false bei ungueltigem Punkt oder ohne laufende Kamera.
     */
    suspend fun focusAt(x: Float, y: Float): Boolean

    /** Gespeicherte Aufnahme loeschen (Selbsttest raeumt damit auf). */
    suspend fun delete(uri: String): Boolean
}
