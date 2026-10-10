package app.cayresim.core.boundary

import kotlinx.coroutines.flow.Flow

data class SeriesSnapshot(val id: Long, val name: String, val photoUris: List<String>) {
    val latestUri: String? get() = photoUris.lastOrNull()
}

sealed interface SeriesResult {
    data class Ok(val series: SeriesSnapshot) : SeriesResult
    data class Failed(val reason: SeriesFailure) : SeriesResult
}

enum class SeriesFailure { INVALID_NAME, NOT_FOUND, DUPLICATE_PHOTO, FULL, STORAGE }

/** Vorher-Nachher-Serien (Geister-Overlay, Zeitraffer). */
interface SeriesBoundary {
    fun series(): Flow<List<SeriesSnapshot>>
    suspend fun create(name: String): SeriesResult
    suspend fun addPhoto(seriesId: Long, uri: String, takenAtMillis: Long): SeriesResult
    suspend fun delete(seriesId: Long): Boolean
}

/** Undurchsichtiges Vorschaubild; die Feature-Control packt es aus (Android-Bitmap). */
class ImageHandle(val token: Any, val width: Int, val height: Int)

enum class Look { NONE, WARM, COOL, FILM, MONO }

/** Kennzahlen einer Nachtaufnahme: genutzte und verworfene Bilder, Aufhellung. */
/** [maxShake]: groesster Versatz zum Bezugsbild in Pixeln des Nachtbilds (S-003). */
/** [diagnosis]: S-007, Werte der Boden-Entscheidung (nur 8 Bit, sonst null). */
data class NightStats(
    val used: Int, val dropped: Int, val gain: Float, val maxShake: Int = 0, val shakeMeasurable: Boolean = true,
    val diagnosis: app.cayresim.core.pure.NightDiagnosis? = null,
    /**
     * S-011: Messwerte je Eingangsbild fuer die gespeicherte Nachtserie. Ohne Standardwert, damit kein Aufrufer sie
     * vergisst (Zweitpruefung S-011, B1: der GPU-Adapter gab sie nicht weiter).
     */
    val records: List<app.cayresim.core.pure.NightMerge.FrameRecord>,
)

sealed interface ProcessResult {
    /** [night]: Kennzahlen des Nacht-Kerns (nur Zahlen, Text baut die UI, R23). */
    data class Saved(val uri: String, val night: NightStats? = null) : ProcessResult
    data class Failed(val reason: ProcessFailure) : ProcessResult
}

enum class ProcessFailure { SOURCE_MISSING, GPU, ENCODER, STORAGE, CANCELLED, INVALID_INPUT }

/** Bildverarbeitung auf der GPU (R20) und Video aus Serien. */
interface ProcessingBoundary {
    /** Vorschaubild fuer das Overlay, laengste Seite hoechstens [maxPx]. */
    suspend fun loadPreview(uri: String, maxPx: Int): ImageHandle?

    /** Wendet einen Look an und speichert das Ergebnis als neues Foto. */
    suspend fun applyLook(uri: String, look: Look): ProcessResult

    /** Erzeugt ein Zeitraffer-Video aus den Fotos (aelteste zuerst). */
    suspend fun timelapse(photoUris: List<String>, photosPerSecond: Int): ProcessResult

    /** Stapelt eine Serie (Median: Bewegtes verschwindet, Mittelwert: Langzeitbelichtung) und speichert das Ergebnis. */
    suspend fun stack(burst: FrameBurst, mode: StackMode): ProcessResult

    /** Nacht-Kern: Bilder beim Eintreffen ausrichten, robust aufsummieren, aufhellen und speichern. */
    suspend fun night(frames: Flow<Frame>): ProcessResult

    /** RAW-Nachtweg: Rohbilder entwickeln (2x2 zusammengefasst), wie [night] zusammenfuehren und speichern. */
    suspend fun nightRaw(frames: Flow<RawFrame>): ProcessResult
}
