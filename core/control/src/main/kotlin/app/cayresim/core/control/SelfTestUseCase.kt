package app.cayresim.core.control

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import app.cayresim.core.boundary.CameraBoundary
import app.cayresim.core.boundary.CameraStatus
import app.cayresim.core.boundary.CaptureResult
import app.cayresim.core.boundary.ManualCameraBoundary
import app.cayresim.core.boundary.FrameBoundary
import app.cayresim.core.boundary.NightPathBoundary
import app.cayresim.core.boundary.NightPathSnapshot
import app.cayresim.core.boundary.ProcessResult
import app.cayresim.core.boundary.ProcessingBoundary
import app.cayresim.core.pure.NightPath
import app.cayresim.core.pure.NightPathRule
import app.cayresim.core.boundary.RawProbe
import app.cayresim.core.boundary.RawProbeResult
import app.cayresim.core.boundary.PhotoMode
import app.cayresim.core.boundary.SelfTestJournalBoundary
import app.cayresim.core.pure.Clock
import javax.inject.Inject

enum class SelfTestCheck { LAST_RUN, CAMERA_START, CAPABILITIES, DEVICE, MODE_CAPTURE, LOW_LIGHT_BOOST, ULTRA_HDR, RAW, RAW_SERIES, NIGHT_PATH, CLEANUP }

/** Ein Ergebnis des Selbsttests. [mode] ist bei MODE_CAPTURE gesetzt. */
data class SelfTestItem(
    val check: SelfTestCheck,
    val passed: Boolean,
    val durationMillis: Long,
    val mode: PhotoMode? = null,
    val detail: String = "",
    val device: app.cayresim.core.boundary.DeviceReport? = null,
    val rawProbe: RawProbe? = null,
    val nightPath: NightPathRule.Verdict? = null,
)

data class SelfTestReport(val items: List<SelfTestItem>) {
    val passed: Boolean get() = items.all { it.passed }
}

/**
 * Selbsttest mit einem Knopf (Testkonzept Abschnitt 4): startet die Kamera, nimmt in jedem
 * angebotenen Modus ein Foto auf, misst die Zeit und loescht die Testfotos wieder.
 * Faehigkeiten wie Low Light Boost gelten als bestanden, wenn sie gemeldet werden koennen;
 * ihr Wert steht im Detail.
 *
 * Jeder Schritt wird vorher dauerhaft protokolliert. Brach der letzte Lauf ab (etwa weil das Geraet
 * neu gestartet ist), meldet der naechste Lauf den Schritt rot (LAST_RUN), loescht die liegen
 * gebliebenen Testfotos und ueberspringt genau diesen Schritt einmal, damit sich der Absturz
 * nicht sofort wiederholt.
 */
class SelfTestUseCase @Inject constructor(
    private val camera: CameraBoundary,
    private val clock: Clock,
    private val journal: SelfTestJournalBoundary,
    /** Fuer die RAW-Messung (Schritt 0 des RAW-Wegs); ohne sie entfaellt der Schritt. */
    private val manual: ManualCameraBoundary? = null,
    /** Fuer die RAW-Probenacht und das Speichern der Wahl; ohne sie entfaellt der Schritt "Nachtweg". */
    private val frames: FrameBoundary? = null,
    private val processing: ProcessingBoundary? = null,
    private val nightPath: NightPathBoundary? = null,
) {
    suspend operator fun invoke(): SelfTestReport {
        val items = mutableListOf<SelfTestItem>()
        val created = mutableListOf<String>()
        val aborted = journal.unfinished()
        if (aborted != null) {
            val leftovers = aborted.photoUris.count { camera.delete(it) }
            items += SelfTestItem(SelfTestCheck.LAST_RUN, false, 0,
                detail = "abgebrochen bei " + (aborted.lastStep ?: "Start") +
                    if (aborted.photoUris.isEmpty()) "" else ", $leftovers/${aborted.photoUris.size} Testfotos nachtraeglich geloescht")
        }
        val skip = aborted?.lastStep
        journal.begin()
        var t = clock.nowMillis()
        journal.step(STEP_START)
        camera.start()
        val started = camera.state.value.status == CameraStatus.RUNNING
        items += SelfTestItem(SelfTestCheck.CAMERA_START, started, clock.nowMillis() - t,
            detail = camera.state.value.error?.name ?: "")
        if (!started) { journal.finish(); return SelfTestReport(items) }

        val caps = camera.capabilities.value
        items += SelfTestItem(SelfTestCheck.CAPABILITIES, caps != null, 0,
            detail = caps?.modes?.joinToString { it.name } ?: "keine")
        if (caps != null) {
            items += SelfTestItem(SelfTestCheck.LOW_LIGHT_BOOST, true, 0, detail = if (caps.lowLightBoost) "ja" else "nein")
            items += SelfTestItem(SelfTestCheck.ULTRA_HDR, true, 0, detail = if (caps.ultraHdr) "ja" else "nein")
            items += SelfTestItem(SelfTestCheck.RAW, true, 0, detail = if (caps.raw) "ja" else "nein")
            // S-003: Stabilisator aktiv laut letzter Aufnahme
            caps.device?.let { d ->
                // S-006: der Wert kommt erst mit dem ersten Aufnahmeergebnis (Geraet 10.10.: direkt nach dem Start "unbekannt")
                val ois = if (d.ois == true) withTimeoutOrNull(OIS_WAIT_MS) { camera.state.first { it.stabilization != null } }?.stabilization else null
                items += SelfTestItem(SelfTestCheck.DEVICE, true, 0, device = d.copy(oisActive = ois))
            }
        }

        val previous = camera.state.value.requestedMode
        for (mode in caps?.modes ?: listOf(PhotoMode.NORMAL)) {
            val step = STEP_CAPTURE + mode.name
            if (step == skip) {
                items += SelfTestItem(SelfTestCheck.MODE_CAPTURE, false, 0, mode, "uebersprungen, der letzte Lauf brach hier ab")
                continue
            }
            journal.step(step)
            camera.selectMode(mode)
            val active = camera.state.value.activeMode
            t = clock.nowMillis()
            val r = camera.capture()
            val ms = clock.nowMillis() - t
            when {
                active != mode -> items += SelfTestItem(SelfTestCheck.MODE_CAPTURE, false, ms, mode, "Rueckfall auf ${active.name}")
                r is CaptureResult.Saved -> {
                    created += r.uri; journal.photo(r.uri)
                    // Ein gespeichertes, aber zu langsames Foto ist ein Fehler (Fall v0.1.27: 10 s je Aufnahme blieb gruen)
                    val fast = ms <= MAX_CAPTURE_MS
                    items += SelfTestItem(SelfTestCheck.MODE_CAPTURE, fast, ms, mode, if (fast) "" else "langsamer als ${MAX_CAPTURE_MS / 1000} s")
                }
                r is CaptureResult.Failed -> items += SelfTestItem(SelfTestCheck.MODE_CAPTURE, false, ms, mode, r.reason.name)
            }
        }
        val raw = rawSeries(skip)
        raw?.let { items += it }
        choosePath(raw?.rawProbe, skip, created)?.let { items += it }
        journal.step(STEP_CLEANUP)
        camera.selectMode(previous)

        val deleted = created.count { camera.delete(it) }
        items += SelfTestItem(SelfTestCheck.CLEANUP, deleted == created.size, 0, detail = "$deleted/${created.size}")
        journal.finish()
        return SelfTestReport(items)
    }

    /** RAW-Serie nur in den Speicher, im normalen Modus; misst Tempo und Kalibrierung fuer den RAW-Weg. */
    private suspend fun rawSeries(skip: String?): SelfTestItem? {
        val m = manual ?: return null
        if (m.manualCapabilities.value?.raw != true) return null
        if (skip == STEP_RAW) return SelfTestItem(SelfTestCheck.RAW_SERIES, false, 0, detail = "uebersprungen, der letzte Lauf brach hier ab")
        journal.step(STEP_RAW)
        camera.selectMode(PhotoMode.NORMAL)
        val t = clock.nowMillis()
        val r = m.probeRaw(RAW_FRAMES, RAW_EXPOSURE_NS, RAW_ISO)
        val ms = clock.nowMillis() - t
        return when (r) {
            is RawProbeResult.Failed -> SelfTestItem(SelfTestCheck.RAW_SERIES, false, ms, detail = r.reason.name)
            is RawProbeResult.Ok -> {
                val p = r.probe
                val ok = p.frames == p.requested && p.avgFrameMs <= MAX_RAW_FRAME_MS
                SelfTestItem(SelfTestCheck.RAW_SERIES, ok, ms, rawProbe = p,
                    detail = if (ok) "" else "langsamer als ${MAX_RAW_FRAME_MS} ms je Bild")
            }
        }
    }

    /**
     * Entscheidet den Nachtweg fuer dieses Geraet und speichert ihn: Vorpruefung aus der RAW-Messung, dann eine echte
     * RAW-Probenacht. Immer gruen, denn beide Wege funktionieren; das Detail sagt, welcher gewaehlt wurde und warum.
     */
    private suspend fun choosePath(probe: RawProbe?, skip: String?, created: MutableList<String>): SelfTestItem? {
        val store = nightPath ?: return null
        val rawCaps = manual?.manualCapabilities?.value?.raw == true
        var ms = 0L
        val verdict = if (probe == null) {
            NightPathRule.Verdict(NightPath.YUV, if (rawCaps) NightPathRule.Reason.PROBE_FAILED else NightPathRule.Reason.NO_RAW)
        } else {
            NightPathRule.precheck(true, probe.streamFps, probe.zeroShare, probe.colorMatrix && probe.whiteLevel != null, probe.blackLevel) ?: run {
                val fr = frames; val pr = processing
                if (fr == null || pr == null || skip == STEP_RAW_NIGHT) {
                    NightPathRule.Verdict(NightPath.YUV, NightPathRule.Reason.PROBE_FAILED)
                } else {
                    journal.step(STEP_RAW_NIGHT)
                    camera.selectMode(PhotoMode.NORMAL)
                    val t = clock.nowMillis()
                    val r = pr.nightRaw(fr.rawFrames(NightPathRule.PROBE_FRAMES, RAW_EXPOSURE_NS, RAW_ISO))
                    ms = clock.nowMillis() - t
                    if (r is ProcessResult.Saved) { created += r.uri; journal.photo(r.uri) }
                    NightPathRule.afterProbe(r is ProcessResult.Saved, ms)
                }
            }
        }
        store.save(NightPathSnapshot(verdict.path, verdict.reason))
        return SelfTestItem(SelfTestCheck.NIGHT_PATH, true, ms, nightPath = verdict)
    }

    internal companion object {
        const val STEP_RAW = "RAW-Serie"
        /** S-006: so lange wartet der Selbsttest auf das erste Aufnahmeergebnis mit dem Stabilisator-Wert. */
        const val OIS_WAIT_MS = 1_500L
        const val STEP_RAW_NIGHT = "RAW-Nacht"
        /** Messung wie fuer die Nacht: 8 Bilder bei 1/10 s und ISO 3200. */
        const val RAW_FRAMES = 8
        const val RAW_EXPOSURE_NS = 100_000_000L
        const val RAW_ISO = 3200
        /** Langsamer als 1 s je RAW-Bild taugt nicht fuer eine Nachtserie. */
        const val MAX_RAW_FRAME_MS = 1_000L
        const val STEP_START = "Kamera starten"
        const val STEP_CAPTURE = "Aufnahme "
        const val STEP_CLEANUP = "Aufraeumen"
        /** Obergrenze je Aufnahme; Nacht und Portraet brauchen auf dem S24+ etwa 2 s. */
        const val MAX_CAPTURE_MS = 5_000L
    }
}
