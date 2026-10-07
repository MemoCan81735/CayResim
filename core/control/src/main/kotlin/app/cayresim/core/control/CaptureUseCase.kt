package app.cayresim.core.control

import app.cayresim.core.boundary.CameraBoundary
import app.cayresim.core.boundary.CaptureFailure
import app.cayresim.core.boundary.CaptureResult
import app.cayresim.core.boundary.Look
import app.cayresim.core.boundary.ProcessResult
import app.cayresim.core.boundary.ProcessingBoundary
import app.cayresim.core.boundary.SeriesBoundary
import app.cayresim.core.boundary.SeriesResult
import app.cayresim.core.pure.Clock
import javax.inject.Inject

/** Ergebnis einer Aufnahme mit Look und Serie. Teilerfolge werden benannt, nie verschluckt. */
sealed interface CaptureOutcome {
    /** [lookFailed]: Look konnte nicht angewendet werden, das Original ist gespeichert. [seriesFailed]: Foto ist da, aber nicht in der Serie. */
    data class Saved(val uri: String, val lookFailed: Boolean = false, val seriesFailed: Boolean = false, val rawUri: String? = null) : CaptureOutcome
    data class Failed(val reason: CaptureFailure) : CaptureOutcome
}

/**
 * Ausloesen in drei Schritten: Foto aufnehmen, Look anwenden (Original wird dann ersetzt),
 * Foto der gewaehlten Serie hinzufuegen. Jeder Schritt kann scheitern, ohne dass ein Foto verloren geht.
 */
class CaptureUseCase @Inject constructor(
    private val takePhoto: TakePhotoUseCase,
    private val camera: CameraBoundary,
    private val processing: ProcessingBoundary,
    private val series: SeriesBoundary,
    private val clock: Clock,
) {
    suspend operator fun invoke(look: Look, seriesId: Long?): CaptureOutcome {
        val shot = takePhoto()
        if (shot is CaptureResult.Failed) return CaptureOutcome.Failed(shot.reason)
        var uri = (shot as CaptureResult.Saved).uri
        var lookFailed = false
        if (look != Look.NONE) {
            when (val p = processing.applyLook(uri, look)) {
                is ProcessResult.Saved -> { camera.delete(uri); uri = p.uri }
                is ProcessResult.Failed -> lookFailed = true
            }
        }
        var seriesFailed = false
        if (seriesId != null) {
            seriesFailed = series.addPhoto(seriesId, uri, clock.nowMillis()) !is SeriesResult.Ok
        }
        return CaptureOutcome.Saved(uri, lookFailed, seriesFailed, shot.rawUri)
    }
}
