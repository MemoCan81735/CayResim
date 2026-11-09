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
    /**
     * S-021: Optik der Kamera fuer den Bildwinkel; null, wenn das Geraet sie nicht nennt. Ohne Standardwert, damit
     * jeder Adapter sie setzen muss.
     */
    val lens: LensSnapshot?,
    /** Geraetewerte fuer die Diagnose im Selbsttest; null, wenn nicht lesbar. */
    val device: DeviceReport? = null,
)

/** S-021: Brennweite und Groesse des Sensors in mm (Breite ist die lange Seite). Den Bildwinkel rechnet `ViewAngle`. */
data class LensSnapshot(val focalLengthMm: Float, val sensorWidthMm: Float, val sensorHeightMm: Float)

enum class HardwareLevel { LEGACY, LIMITED, FULL, LEVEL_3, EXTERNAL, UNKNOWN }

/** Was das Geraet Drittanbieter-Apps erlaubt; nur Zahlen und Schluessel, den Text baut die UI (R23). */
data class DeviceReport(
    val hardwareLevel: HardwareLevel = HardwareLevel.UNKNOWN,
    val exposureNs: LongRange? = null,
    val iso: IntRange? = null,
    val maxFrameNs: Long? = null,
    val slowestFps: IntRange? = null,
    val raw: Boolean = false,
    val burst: Boolean = false,
    val sensorWidth: Int? = null,
    val sensorHeight: Int? = null,
    val zsl: Boolean? = null,
    val zoomMin: Float? = null,
    val zoomMax: Float? = null,
    val physicalCameras: Int? = null,
    val chip: String = "",
    val system: String = "",
    /** S-003: optischer Stabilisator angeboten (laut Geraet) und aktiv (laut letzter Aufnahme, null = kein Ergebnis). */
    val ois: Boolean? = null,
    val oisActive: OisState? = null,
)

/**
 * Undurchsichtiger Wert fuer den Sucher (Ausnahme A1). Nur der Sucher-Screen darf ihn
 * als CameraX-SurfaceRequest auspacken; alle anderen Schichten reichen ihn nur weiter.
 */
class PreviewHandle(val token: Any)

/** Aktueller Zoom, die Grenzen der Kamera (S24+: 0,6x bis 10x) und die Schnellwahl-Stufen. */
data class ZoomSnapshot(val ratio: Float = 1f, val min: Float = 1f, val max: Float = 1f, val presets: List<Float> = emptyList())

/** Was die Belichtungsautomatik gerade misst; Grundlage fuer "ist es dunkel?". */
data class LightSnapshot(val exposureNs: Long, val iso: Int)

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
    /** Letzte Messung der Automatik im normalen Modus; null, solange keine vorliegt. */
    val light: LightSnapshot? = null,
    /** S-003: optischer Stabilisator laut letzter Aufnahme der eigenen Pipeline; null = noch kein Ergebnis (z. B. Samsung-Modi). */
    val stabilization: OisState? = null,
    /** S-021: Bilder je Sekunde im Sucherstrom, einmal je Sekunde gemessen; null, solange noch keine Sekunde gelaufen ist. */
    val previewFps: Int? = null,
)

/**
 * S-007: Stabilisator laut Aufnahmeergebnis. [NOT_REPORTED]: ein Ergebnis kam, aber ohne diesen Wert (Selbsttest S24+
 * am 10. Oktober: "unbekannt" auch nach 1,5 s, die Ursache war nicht unterscheidbar).
 */
enum class OisState { ON, OFF, NOT_REPORTED }

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

    /**
     * S-021: Laesst die laufende Kamera ohne sichtbaren Sucher Bilder liefern (unsichtbare Ersatz-Flaeche) und wartet
     * auf die erste Messung von [CameraStateSnapshot.previewFps]. false, wenn die Kamera nicht laeuft oder keine
     * Bilder kommen.
     */
    suspend fun runWithoutViewfinder(): Boolean

    /** Gespeicherte Aufnahme loeschen (Selbsttest raeumt damit auf). */
    suspend fun delete(uri: String): Boolean
}
