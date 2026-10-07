package app.cayresim.core.control

import app.cayresim.core.boundary.CameraBoundary
import app.cayresim.core.boundary.CameraStatus
import app.cayresim.core.boundary.CaptureResult
import app.cayresim.core.boundary.PhotoMode
import app.cayresim.core.pure.Clock
import javax.inject.Inject

enum class SelfTestCheck { CAMERA_START, CAPABILITIES, MODE_CAPTURE, LOW_LIGHT_BOOST, ULTRA_HDR, RAW, CLEANUP }

/** Ein Ergebnis des Selbsttests. [mode] ist bei MODE_CAPTURE gesetzt. */
data class SelfTestItem(
    val check: SelfTestCheck,
    val passed: Boolean,
    val durationMillis: Long,
    val mode: PhotoMode? = null,
    val detail: String = "",
)

data class SelfTestReport(val items: List<SelfTestItem>) {
    val passed: Boolean get() = items.all { it.passed }
}

/**
 * Selbsttest mit einem Knopf (Testkonzept Abschnitt 4): startet die Kamera, nimmt in jedem
 * angebotenen Modus ein Foto auf, misst die Zeit und loescht die Testfotos wieder.
 * Faehigkeiten wie Low Light Boost gelten als bestanden, wenn sie gemeldet werden koennen;
 * ihr Wert steht im Detail.
 */
class SelfTestUseCase @Inject constructor(
    private val camera: CameraBoundary,
    private val clock: Clock,
) {
    suspend operator fun invoke(): SelfTestReport {
        val items = mutableListOf<SelfTestItem>()
        val created = mutableListOf<String>()
        var t = clock.nowMillis()
        camera.start()
        val started = camera.state.value.status == CameraStatus.RUNNING
        items += SelfTestItem(SelfTestCheck.CAMERA_START, started, clock.nowMillis() - t,
            detail = camera.state.value.error?.name ?: "")
        if (!started) return SelfTestReport(items)

        val caps = camera.capabilities.value
        items += SelfTestItem(SelfTestCheck.CAPABILITIES, caps != null, 0,
            detail = caps?.modes?.joinToString { it.name } ?: "keine")
        if (caps != null) {
            items += SelfTestItem(SelfTestCheck.LOW_LIGHT_BOOST, true, 0, detail = if (caps.lowLightBoost) "ja" else "nein")
            items += SelfTestItem(SelfTestCheck.ULTRA_HDR, true, 0, detail = if (caps.ultraHdr) "ja" else "nein")
            items += SelfTestItem(SelfTestCheck.RAW, true, 0, detail = if (caps.raw) "ja" else "nein")
        }

        val previous = camera.state.value.requestedMode
        for (mode in caps?.modes ?: listOf(PhotoMode.NORMAL)) {
            camera.selectMode(mode)
            val active = camera.state.value.activeMode
            t = clock.nowMillis()
            val r = camera.capture()
            val ms = clock.nowMillis() - t
            when {
                active != mode -> items += SelfTestItem(SelfTestCheck.MODE_CAPTURE, false, ms, mode, "Rueckfall auf ${active.name}")
                r is CaptureResult.Saved -> { created += r.uri; items += SelfTestItem(SelfTestCheck.MODE_CAPTURE, true, ms, mode) }
                r is CaptureResult.Failed -> items += SelfTestItem(SelfTestCheck.MODE_CAPTURE, false, ms, mode, r.reason.name)
            }
        }
        camera.selectMode(previous)

        val deleted = created.count { camera.delete(it) }
        items += SelfTestItem(SelfTestCheck.CLEANUP, deleted == created.size, 0, detail = "$deleted/${created.size}")
        return SelfTestReport(items)
    }
}
