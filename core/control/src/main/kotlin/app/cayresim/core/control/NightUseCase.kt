package app.cayresim.core.control

import app.cayresim.core.boundary.CameraBoundary
import app.cayresim.core.boundary.FrameBoundary
import app.cayresim.core.boundary.ManualCameraBoundary
import app.cayresim.core.boundary.NightPathBoundary
import app.cayresim.core.pure.NightPath
import app.cayresim.core.boundary.ProcessResult
import app.cayresim.core.boundary.ProcessingBoundary
import app.cayresim.core.pure.NightPlan
import app.cayresim.core.pure.Clock
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.withIndex
import javax.inject.Inject
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

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
    /** Wahl aus dem Selbsttest; ohne sie immer der 8-Bit-Weg. */
    private val nightPath: NightPathBoundary? = null,
    /** Misst die Dauer fuer den Hinweis (R2, R27); ohne Uhr keine Dauer. */
    private val clock: Clock? = null,
) {
    /** Eigener Kern bei Dunkelheit oder wenn noch keine Messung vorliegt; bei gemessen hellem Licht Samsungs Modus. */
    fun shouldUseOwn(): Boolean {
        val l = camera.state.value.light
        return NightPlan.isDark(l?.exposureNs, l?.iso) != false
    }

    /** Nur gemessene Dunkelheit (fuer die Automatik; ohne Messung wird normal fotografiert). */
    fun isDark(): Boolean {
        val l = camera.state.value.light
        return NightPlan.isDark(l?.exposureNs, l?.iso) == true
    }

    suspend operator fun invoke(count: Int = NightPlan.FRAMES): StackOutcome {
        val caps = manual.manualCapabilities.value
        val expRange = caps?.exposureRangeNanos; val isoRange = caps?.isoRange
        if (caps?.canExpose != true || expRange == null || isoRange == null)
            return StackOutcome.Failed(StackOutcome.Stage.COLLECT, "NO_MANUAL_EXPOSURE")
        val start = clock?.nowMillis()
        if (nightPath?.load()?.path == NightPath.RAW) rawNight(count, expRange, isoRange, start)?.let { return it }
        val before = manual.manualState.value
        var plan: NightPlan.Exposure? = null
        var meter: app.cayresim.core.boundary.LightSnapshot? = null
        try {
            val stream = frames.frames(METER + SETTLE + count)
                .withIndex()
                .onEach { (i, _) ->
                    if (i == METER - 1) {
                        val l = camera.state.value.light
                        meter = l
                        // Ohne Messung: wie bei voller Dunkelheit
                        val p = NightPlan.plan(l?.exposureNs ?: FALLBACK_NS, l?.iso ?: isoRange.last, expRange.last, isoRange.first, isoRange.last)
                        // Befund M6: nur melden, was wirklich eingestellt wurde
                        plan = if (manual.setExposure(p.exposureNs, p.iso)) p else null
                    }
                }
                .filter { it.index >= METER + SETTLE }
                .map { it.value }
            return when (val r = processing.night(stream)) {
                is ProcessResult.Saved -> {
                    val used = r.night?.used ?: count
                    StackOutcome.Saved(r.uri, used, used < count * 3 / 4,
                        NightReport(plan?.exposureNs, plan?.iso, used, r.night?.dropped ?: 0, r.night?.gain ?: 1f, durationMs = since(start),
                            meterExposureNs = meter?.exposureNs, meterIso = meter?.iso))
                }
                is ProcessResult.Failed -> StackOutcome.Failed(StackOutcome.Stage.PROCESS, r.reason.name)
            }
        } finally {
            // Auch bei Abbruch (Zurueck, Home): sonst bliebe die Nachtbelichtung im Singleton-Adapter haengen
            withContext(NonCancellable) { manual.setExposure(before.exposureNanos, before.iso) }
        }
    }

    /**
     * RAW-Nachtweg: Belichtung aus der letzten Messung der Automatik, die RAW-Sitzung setzt sie selbst.
     * null = RAW gescheitert, der Aufrufer nimmt den 8-Bit-Weg (R14).
     */
    private suspend fun rawNight(count: Int, expRange: LongRange, isoRange: IntRange, start: Long?): StackOutcome? {
        val l = camera.state.value.light
        val p = NightPlan.plan(l?.exposureNs ?: FALLBACK_NS, l?.iso ?: isoRange.last, expRange.last, isoRange.first, isoRange.last)
        return when (val r = processing.nightRaw(frames.rawFrames(count, p.exposureNs, p.iso))) {
            is ProcessResult.Saved -> {
                val used = r.night?.used ?: count
                StackOutcome.Saved(r.uri, used, used < count * 3 / 4,
                    NightReport(p.exposureNs, p.iso, used, r.night?.dropped ?: 0, r.night?.gain ?: 1f, raw = true, durationMs = since(start),
                        meterExposureNs = l?.exposureNs, meterIso = l?.iso))
            }
            is ProcessResult.Failed -> null
        }
    }

    /** Nie negativ, auch wenn die Systemuhr zurueckspringt. */
    private fun since(start: Long?): Long? = if (start == null || clock == null) null else (clock.nowMillis() - start).coerceAtLeast(0)

    companion object {
        /** Bilder mit Automatik zum Messen. */
        const val METER = 4
        /** Bilder nach dem Umstellen, bis die neue Belichtung wirkt. */
        const val SETTLE = 3
        private const val FALLBACK_NS = 66_666_666L
    }
}
