package app.cayresim.core.control

import app.cayresim.core.boundary.BurstResult
import app.cayresim.core.boundary.FrameBoundary
import app.cayresim.core.boundary.ManualCameraBoundary
import app.cayresim.core.boundary.ProcessResult
import app.cayresim.core.boundary.ProcessingBoundary
import app.cayresim.core.boundary.StackMode
import javax.inject.Inject
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Fokus-Stacking: Fokusreihe aufnehmen, je Bereich das schaerfste Bild nehmen. */
class FocusStackUseCase @Inject constructor(
    private val manual: ManualCameraBoundary,
    private val processing: ProcessingBoundary,
) {
    suspend operator fun invoke(steps: Int = 8): StackOutcome {
        if (manual.manualCapabilities.value?.canFocus != true) return StackOutcome.Failed(StackOutcome.Stage.COLLECT, "FIXED_FOCUS")
        val burst = when (val b = manual.focusBracket(steps)) {
            is BurstResult.Ok -> b
            is BurstResult.Failed -> return StackOutcome.Failed(StackOutcome.Stage.COLLECT, b.reason.name)
        }
        return when (val r = processing.stack(burst.burst, StackMode.FOCUS)) {
            is ProcessResult.Saved -> StackOutcome.Saved(r.uri, burst.burst.frames.size, false)
            is ProcessResult.Failed -> StackOutcome.Failed(StackOutcome.Stage.PROCESS, r.reason.name)
        }
    }
}

/**
 * Astro-Stacking: lange Belichtung und hohe Empfindlichkeit, viele Bilder, ausrichten und mitteln.
 * Die vorherigen manuellen Werte werden danach immer wiederhergestellt, auch bei Fehlern.
 */
class AstroUseCase @Inject constructor(
    private val manual: ManualCameraBoundary,
    private val frames: FrameBoundary,
    private val processing: ProcessingBoundary,
) {
    suspend operator fun invoke(count: Int = 20): StackOutcome {
        val caps = manual.manualCapabilities.value
        if (caps?.canExpose != true) return StackOutcome.Failed(StackOutcome.Stage.COLLECT, "NO_MANUAL_EXPOSURE")
        val before = manual.manualState.value
        try {
            manual.setExposure(minOf(caps.exposureRangeNanos!!.last, MAX_FRAME_EXPOSURE_NS), minOf(caps.isoRange!!.last, ASTRO_ISO))
            val burst = when (val b = frames.collect(count)) {
                is BurstResult.Ok -> b
                is BurstResult.Failed -> return StackOutcome.Failed(StackOutcome.Stage.COLLECT, b.reason.name)
            }
            return when (val r = processing.stack(burst.burst, StackMode.STARS)) {
                is ProcessResult.Saved -> StackOutcome.Saved(r.uri, burst.burst.frames.size, burst.burst.frames.size < burst.requested)
                is ProcessResult.Failed -> StackOutcome.Failed(StackOutcome.Stage.PROCESS, r.reason.name)
            }
        } finally {
            withContext(NonCancellable) { manual.setExposure(before.exposureNanos, before.iso) }
        }
    }

    companion object {
        /** Laenger als ein Analysebild dauern kann, belichtet die Kamera nicht; 250 ms als sichere Obergrenze. */
        const val MAX_FRAME_EXPOSURE_NS = 250_000_000L
        const val ASTRO_ISO = 1600
    }
}
