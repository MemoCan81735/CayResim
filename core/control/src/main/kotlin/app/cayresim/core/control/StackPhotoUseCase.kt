package app.cayresim.core.control

import app.cayresim.core.boundary.BurstResult
import app.cayresim.core.boundary.FrameBoundary
import app.cayresim.core.boundary.ProcessResult
import app.cayresim.core.boundary.ProcessingBoundary
import app.cayresim.core.boundary.StackMode
import javax.inject.Inject

/** Was eine Nachtaufnahme tatsaechlich getan hat; [exposureNs] und [iso] null, wenn die Automatik blieb. */
data class NightReport(val exposureNs: Long?, val iso: Int?, val used: Int, val dropped: Int, val gain: Float, val raw: Boolean = false)

sealed interface StackOutcome {
    data class Saved(val uri: String, val frames: Int, val shortened: Boolean, val night: NightReport? = null) : StackOutcome
    data class Failed(val stage: Stage, val detail: String) : StackOutcome
    enum class Stage { COLLECT, PROCESS }
}

/** Menschen wegrechnen (Median) oder Langzeitbelichtung (Mittelwert) aus einer Serie. */
class StackPhotoUseCase @Inject constructor(
    private val frames: FrameBoundary,
    private val processing: ProcessingBoundary,
) {
    suspend operator fun invoke(mode: StackMode, count: Int = DEFAULT_FRAMES): StackOutcome {
        val burst = when (val b = frames.collect(count)) {
            is BurstResult.Ok -> b
            is BurstResult.Failed -> return StackOutcome.Failed(StackOutcome.Stage.COLLECT, b.reason.name)
        }
        return when (val r = processing.stack(burst.burst, mode)) {
            is ProcessResult.Saved -> StackOutcome.Saved(r.uri, burst.burst.frames.size, burst.burst.frames.size < burst.requested)
            is ProcessResult.Failed -> StackOutcome.Failed(StackOutcome.Stage.PROCESS, r.reason.name)
        }
    }

    companion object { const val DEFAULT_FRAMES = 20 }
}
