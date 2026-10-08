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

sealed interface ProcessResult {
    /** [info]: kurze Angaben zur Verarbeitung fuer die Diagnose, z. B. Bildzahl und Verstaerkung. */
    data class Saved(val uri: String, val info: String? = null) : ProcessResult
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
}
