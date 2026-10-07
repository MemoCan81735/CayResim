package app.cayresim.core.boundary.contract

import app.cayresim.core.boundary.CameraBoundary
import app.cayresim.core.boundary.CameraStatus
import app.cayresim.core.boundary.CaptureFailure
import app.cayresim.core.boundary.CaptureResult
import app.cayresim.core.boundary.PhotoMode

/**
 * Gemeinsamer Vertragskatalog (Testebene 3). Laeuft gegen den Fake auf der JVM und gegen den
 * echten CameraX-Adapter auf dem Emulator. Ohne JUnit-Abhaengigkeit, damit beide Seiten ihn nutzen koennen.
 */
object CameraBoundaryContract {
    private fun check(cond: Boolean, msg: String) { if (!cond) throw AssertionError(msg) }

    suspend fun captureBeforeStartIsNotReady(c: CameraBoundary) {
        val r = c.capture()
        check(r == CaptureResult.Failed(CaptureFailure.NOT_READY), "Aufnahme vor start() muss NOT_READY liefern, war $r")
    }

    suspend fun startRunsAndOffersNormal(c: CameraBoundary) {
        c.start()
        val s = c.state.value
        check(s.status == CameraStatus.RUNNING, "Status nach start() muss RUNNING sein, war ${s.status}")
        check(PhotoMode.NORMAL in s.offeredModes, "NORMAL muss immer angeboten werden")
        check(c.capabilities.value != null, "Faehigkeiten muessen nach start() bekannt sein")
        check(c.capabilities.value!!.modes.first() == PhotoMode.NORMAL, "NORMAL steht an erster Stelle")
    }

    suspend fun captureSavesAndDeletes(c: CameraBoundary) {
        c.start()
        val r = c.capture()
        check(r is CaptureResult.Saved, "Aufnahme muss gespeichert werden, war $r")
        check(c.delete((r as CaptureResult.Saved).uri), "Gespeichertes Foto muss sich loeschen lassen")
        check(!c.delete(r.uri), "Zweites Loeschen muss false liefern")
    }

    suspend fun unavailableModeFallsBack(c: CameraBoundary, unavailable: PhotoMode) {
        c.start()
        c.selectMode(unavailable)
        val s = c.state.value
        check(s.requestedMode == unavailable, "Wunschmodus muss erhalten bleiben")
        check(s.activeMode == PhotoMode.NORMAL, "Nicht verfuegbarer Modus muss auf NORMAL fallen, war ${s.activeMode}")
        check(s.fallbackFrom == unavailable, "Rueckfall muss vermerkt sein")
        check(s.status == CameraStatus.RUNNING, "Rueckfall darf die Kamera nicht anhalten")
    }

    suspend fun stopReleasesAndRestartWorks(c: CameraBoundary) {
        c.start(); c.stop()
        check(c.state.value.status == CameraStatus.IDLE, "Nach stop() muss IDLE gelten")
        check(c.state.value.preview == null, "Nach stop() darf kein Sucher mehr da sein")
        check(c.capture() == CaptureResult.Failed(CaptureFailure.NOT_READY), "Aufnahme nach stop() muss NOT_READY liefern")
        c.start()
        check(c.state.value.status == CameraStatus.RUNNING, "Neustart muss funktionieren")
    }

    suspend fun stopTwiceIsHarmless(c: CameraBoundary) {
        c.stop(); c.stop()
        check(c.state.value.status == CameraStatus.IDLE, "Doppeltes stop() muss harmlos sein")
    }

    suspend fun modeSurvivesRestart(c: CameraBoundary) {
        c.start(); c.selectMode(PhotoMode.NORMAL); c.stop(); c.start()
        check(c.state.value.activeMode == PhotoMode.NORMAL, "Modus muss Neustart ueberstehen")
    }

    /** Alle Faelle in einer Liste, damit beide Seiten denselben Katalog laufen lassen. */
    val all: List<Pair<String, suspend (CameraBoundary) -> Unit>> = listOf(
        "Aufnahme vor Start" to ::captureBeforeStartIsNotReady,
        "Start bietet NORMAL" to ::startRunsAndOffersNormal,
        "Aufnahme speichert und loescht" to ::captureSavesAndDeletes,
        "Stopp gibt frei, Neustart klappt" to ::stopReleasesAndRestartWorks,
        "Doppeltes Stopp" to ::stopTwiceIsHarmless,
        "Modus uebersteht Neustart" to ::modeSurvivesRestart,
    )
}
