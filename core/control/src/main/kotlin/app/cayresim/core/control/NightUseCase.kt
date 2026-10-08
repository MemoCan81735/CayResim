package app.cayresim.core.control

import app.cayresim.core.boundary.CameraBoundary
import app.cayresim.core.boundary.FrameBoundary
import app.cayresim.core.boundary.ManualCameraBoundary
import app.cayresim.core.boundary.ProcessResult
import app.cayresim.core.boundary.ProcessingBoundary
import app.cayresim.core.pure.NightPlan
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.withIndex
import javax.inject.Inject

/**
 * Eigener Nacht-Kern statt Samsungs Night-Extension, wenn es dunkel ist (Bildqualitaets-Dossier N-P1).
 *
 * Ablauf: die ersten Bilder laufen noch mit der Automatik und dienen nur der Messung; daraus folgt eine
 * feste Belichtung (bis 1/10 s, ISO bis 3200). Nach einer kurzen Einschwingzeit werden die Bilder einzeln
 * an die Verarbeitung gereicht. Die vorherigen manuellen Werte werden immer wiederhergestellt.
 */
class NightUseCase @Inject constructor(
    private val camera: CameraBoundary,
    private val manual: ManualCameraBoundary,
    private val frames: FrameBoundary,
    private val processing: ProcessingBoundary,
) {
    /** Eigener Kern bei Dunkelheit oder wenn noch keine Messung vorliegt; bei gemessen hellem Licht Samsungs Modus. */
    fun shouldUseOwn(): Boolean {
        val l = camera.state.value.light
        return NightPlan.isDark(l?.exposureNs, l?.iso) != false
    }

    suspend operator fun invoke(count: Int = NightPlan.FRAMES): StackOutcome {
        val caps = manual.manualCapabilities.value
        val expRange = caps?.exposureRangeNanos; val isoRange = caps?.isoRange
        if (caps?.canExpose != true || expRange == null || isoRange == null)
            return StackOutcome.Failed(StackOutcome.Stage.COLLECT, "NO_MANUAL_EXPOSURE")
        val before = manual.manualState.value
        var plan: NightPlan.Exposure? = null
        try {
            val stream = frames.frames(METER + SETTLE + count)
                .withIndex()
                .onEach { (i, _) ->
                    if (i == METER - 1) {
                        val l = camera.state.value.light
                        // Ohne Messung: wie bei voller Dunkelheit
                        val p = NightPlan.plan(l?.exposureNs ?: FALLBACK_NS, l?.iso ?: isoRange.last, expRange.last, isoRange.first, isoRange.last)
                        plan = p
                        manual.setExposure(p.exposureNs, p.iso)
                    }
                }
                .filter { it.index >= METER + SETTLE }
                .map { it.value }
            return when (val r = processing.night(stream)) {
                is ProcessResult.Saved -> StackOutcome.Saved(r.uri, count, false,
                    listOfNotNull(plan?.let { "1/${1_000_000_000L / it.exposureNs.coerceAtLeast(1)} s, ISO ${it.iso}" }, r.info).joinToString(", "))
                is ProcessResult.Failed -> StackOutcome.Failed(StackOutcome.Stage.PROCESS, r.reason.name)
            }
        } finally {
            manual.setExposure(before.exposureNanos, before.iso)
        }
    }

    companion object {
        /** Bilder mit Automatik zum Messen. */
        const val METER = 4
        /** Bilder nach dem Umstellen, bis die neue Belichtung wirkt. */
        const val SETTLE = 3
        private const val FALLBACK_NS = 66_666_666L
    }
}
