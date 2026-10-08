package app.cayresim.core.control

import app.cayresim.core.boundary.CameraBoundary
import app.cayresim.core.boundary.CameraStatus
import app.cayresim.core.boundary.CaptureResult
import app.cayresim.core.boundary.PhotoMode
import app.cayresim.core.boundary.SelfTestJournalBoundary
import app.cayresim.core.pure.Clock
import javax.inject.Inject

enum class SelfTestCheck { LAST_RUN, CAMERA_START, CAPABILITIES, DEVICE, MODE_CAPTURE, LOW_LIGHT_BOOST, ULTRA_HDR, RAW, CLEANUP }

/** Ein Ergebnis des Selbsttests. [mode] ist bei MODE_CAPTURE gesetzt. */
data class SelfTestItem(
    val check: SelfTestCheck,
    val passed: Boolean,
    val durationMillis: Long,
    val mode: PhotoMode? = null,
    val detail: String = "",
    val device: app.cayresim.core.boundary.DeviceReport? = null,
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
            caps.device?.let { items += SelfTestItem(SelfTestCheck.DEVICE, true, 0, device = it) }
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
        journal.step(STEP_CLEANUP)
        camera.selectMode(previous)

        val deleted = created.count { camera.delete(it) }
        items += SelfTestItem(SelfTestCheck.CLEANUP, deleted == created.size, 0, detail = "$deleted/${created.size}")
        journal.finish()
        return SelfTestReport(items)
    }

    internal companion object {
        const val STEP_START = "Kamera starten"
        const val STEP_CAPTURE = "Aufnahme "
        const val STEP_CLEANUP = "Aufraeumen"
        /** Obergrenze je Aufnahme; Nacht und Portraet brauchen auf dem S24+ etwa 2 s. */
        const val MAX_CAPTURE_MS = 5_000L
    }
}
