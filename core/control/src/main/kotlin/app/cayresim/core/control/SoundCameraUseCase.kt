package app.cayresim.core.control

import app.cayresim.core.boundary.AudioSourceKey
import app.cayresim.core.boundary.CameraBoundary
import app.cayresim.core.boundary.CameraError
import app.cayresim.core.boundary.CameraStatus
import app.cayresim.core.boundary.ComputeDispatcher
import app.cayresim.core.boundary.PhotoMode
import app.cayresim.core.pure.SoundCameraCheck
import app.cayresim.core.pure.ViewAngle
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * Eine Phase der Messung, nur Zahlen und Schluessel (R23). [stats]: null, wenn die Aufnahme scheiterte ([failure]).
 * [previewFps]: Bilder je Sekunde der Kamera am Ende der Phase; null ohne Kamera oder ohne Bilder. [lostChunks],
 * [computeMillis]: Messwerte des Schallbilds am Ende der Phase (R27).
 */
data class SoundCameraPhase(
    val source: AudioSourceKey, val cameraOn: Boolean, val stats: SoundCameraCheck.Phase?, val failure: SoundImageFailure?,
    val previewFps: Int?, val lostChunks: Long, val computeMillis: Double,
)

/** Urteil je Tonquelle. */
data class SoundCameraVerdict(val source: AudioSourceKey, val verdict: SoundCameraCheck.Verdict)

/**
 * Ergebnis. [cameraError]: Grund, falls die Kamera nicht startete; [cameraFrames]: false, wenn sie lief, aber ohne
 * Sucher keine Bilder lieferte. [viewWideDeg], [viewHighDeg]: Bildwinkel der Kamera; null, wenn unbekannt.
 * [motionAvailable]: der Lagesensor lieferte Werte.
 */
data class SoundCameraReport(
    val phases: List<SoundCameraPhase>, val verdicts: List<SoundCameraVerdict>, val cameraError: CameraError?, val cameraFrames: Boolean,
    val viewWideDeg: Double?, val viewHighDeg: Double?, val motionAvailable: Boolean,
)

sealed interface SoundCameraEvent {
    /** Phase [index] von [total] beginnt (ab 0). */
    data class Phase(val index: Int, val total: Int, val source: AudioSourceKey, val cameraOn: Boolean) : SoundCameraEvent
    data class Done(val report: SoundCameraReport) : SoundCameraEvent
}

/**
 * S-021 (Stufe 2 der akustischen Kamera F11): Liefert das Mikrofon bei laufender Kamera denselben Ton? Je Tonquelle
 * drei Phasen (ohne, mit, ohne Kamera) mit dem Schallbild; die Kamera laeuft ohne sichtbaren Sucher im normalen Modus.
 * Ergaenzung zu R28 (freigegeben 9.11.2026): Kamera und Tonaufnahme duerfen gleichzeitig laufen; nur dieser UseCase
 * steuert beide, die Adapter kennen einander nicht. Fehler enden als Werte (R24); bei Abbruch wird die Kamera in
 * genau einem NonCancellable-Block gestoppt (R17), Mikrofon und Sensor gibt das Schallbild selbst frei.
 */
class SoundCameraUseCase @Inject constructor(
    private val soundImage: SoundImageUseCase,
    private val camera: CameraBoundary,
    @ComputeDispatcher private val compute: CoroutineDispatcher,
) {
    /** [phaseMillis]: Dauer je Phase; Tests kuerzen sie, weil der lokale Lauf mit echter Zeit rechnet. */
    fun run(phaseMillis: Long = PHASE_MILLIS): Flow<SoundCameraEvent> = flow {
        val origin = TurnOrigin()
        val phases = mutableListOf<SoundCameraPhase>()
        val verdicts = mutableListOf<SoundCameraVerdict>()
        var cameraError: CameraError? = null
        var cameraFrames = true
        val total = SOURCES.size * CAMERA_PLAN.size
        for ((s, source) in SOURCES.withIndex()) {
            var cameraFailed = false
            for ((k, cameraOn) in CAMERA_PLAN.withIndex()) {
                emit(SoundCameraEvent.Phase(s * CAMERA_PLAN.size + k, total, source, cameraOn))
                if (!cameraOn) { phases += measure(source, origin, cameraOn = false, phaseMillis); continue }
                val previous = camera.state.value.requestedMode
                var started = false
                try {
                    started = true
                    camera.start()
                    val running = camera.state.value.status == CameraStatus.RUNNING
                    if (!running) { cameraError = camera.state.value.error ?: CameraError.UNKNOWN; cameraFailed = true }
                    else {
                        // Samsungs Modi melden keine Aufnahmeergebnisse und binden anders: gemessen wird der normale Modus
                        camera.selectMode(PhotoMode.NORMAL)
                        if (!camera.runWithoutViewfinder()) { cameraFrames = false; cameraFailed = true }
                    }
                    // der Ton laeuft auch dann zu Ende, wenn die Kamera scheitert: die Phase bleibt als Messwert
                    phases += measure(source, origin, cameraOn = true, phaseMillis)
                } finally {
                    if (started) withContext(NonCancellable) {
                        camera.stop()
                        if (camera.state.value.requestedMode != previous) camera.selectMode(previous)
                    }
                }
            }
            val (off1, on, off2) = phases.takeLast(CAMERA_PLAN.size)
            verdicts += SoundCameraVerdict(source, SoundCameraCheck.verdict(off1.stats, on.stats, off2.stats, cameraFailed))
        }
        val lens = camera.capabilities.value?.lens
        emit(SoundCameraEvent.Done(SoundCameraReport(
            phases, verdicts, cameraError, cameraFrames,
            ViewAngle.degrees(lens?.focalLengthMm?.toDouble(), lens?.sensorWidthMm?.toDouble()),
            ViewAngle.degrees(lens?.focalLengthMm?.toDouble(), lens?.sensorHeightMm?.toDouble()),
            motionAvailable = phases.any { it.stats?.movedShare != null },
        )))
    }.flowOn(compute)

    private suspend fun measure(source: AudioSourceKey, origin: TurnOrigin, cameraOn: Boolean, phaseMillis: Long): SoundCameraPhase {
        val acc = SoundCameraCheck.Accumulator()
        var failure: SoundImageFailure? = null
        var seen = 0
        var stats = SoundImageStats()
        withTimeoutOrNull(phaseMillis) {
            soundImage.run(source, origin = origin).collect { e ->
                when (e) {
                    is SoundImageEvent.Failed -> failure = e.reason
                    is SoundImageEvent.Frame -> {
                        stats = e.stats
                        // Einschwingen: die ersten Momente tragen noch keinen eingeschwungenen Mittelwert
                        if (seen++ >= SETTLE_FRAMES) {
                            val m = e.motion
                            acc.add(e.mainAngleDeg, e.mainShare, e.channelDbfs.getOrElse(0) { Double.NEGATIVE_INFINITY },
                                e.channelDbfs.getOrElse(1) { Double.NEGATIVE_INFINITY }, m?.moving, m?.yawDeg, m?.rollDeg, m?.pitchDeg)
                        }
                    }
                }
            }
        }
        val fps = if (cameraOn) camera.state.value.previewFps else null
        val phase = when (failure) {
            null -> acc.phase(stereo = true)
            // nur ein Mikrofon auf beiden Kanaelen: das ist ein Messergebnis, kein Ausfall
            SoundImageFailure.NO_STEREO -> acc.phase(stereo = false)
            else -> null
        }
        return SoundCameraPhase(source, cameraOn, phase, failure, fps, stats.lostChunks, stats.computeMillis)
    }

    companion object {
        val SOURCES = listOf(AudioSourceKey.MIC, AudioSourceKey.CAMCORDER)
        /** Ohne, mit, ohne Kamera: die dritte Phase zeigt, ob sich die Szene selbst geaendert hat. */
        val CAMERA_PLAN = listOf(false, true, false)
        const val PHASE_MILLIS = 4_000L
        /** Etwa die erste Sekunde (10 Momente) zaehlt nicht. */
        const val SETTLE_FRAMES = 10
    }
}
