package app.cayresim.feature.settings.control

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.cayresim.core.boundary.AudioSourceKey
import app.cayresim.core.control.SoundCameraEvent
import app.cayresim.core.control.SoundCameraPhase
import app.cayresim.core.control.SoundCameraReport
import app.cayresim.core.control.SoundCameraUseCase
import app.cayresim.core.control.SoundHold
import app.cayresim.core.control.SoundImageBand
import app.cayresim.core.control.SoundImageEvent
import app.cayresim.core.control.SoundImageFailure
import app.cayresim.core.control.SoundImageUseCase
import app.cayresim.core.control.SoundMotion
import app.cayresim.core.pure.SoundCameraCheck
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

/** Spiegel der Control-Typen fuer die UI (R1); nur Zahlen und Schluessel, den Text baut die UI (R23). */
enum class SoundSourceUi { MIC, CAMCORDER }
enum class SoundHoldUi { TOP_LEFT, TOP_RIGHT, NOT_LANDSCAPE, UNKNOWN }
enum class SoundFailureUi { NO_STEREO, RECORD_FAILED }

/** Ein Band: [angleDeg] negativ links, positiv rechts, null ohne Richtung; [response] Kurve von links nach rechts. */
@Immutable
data class SoundBandUi(val lowHz: Int, val highHz: Int, val levelDbfs: Double, val share: Double, val angleDeg: Double?, val response: List<Float>)

/**
 * S-021: Lage des Handys. Winkel in Grad, null wenn unbekannt; [dotX], [dotY]: Punkt im Lage-Kreis aus Sicht des
 * Nutzers (x rechts, y oben, 1 = innerer Rand); [coverageMask]: besuchte Abschnitte des Rands, Abschnitt 0 rechts,
 * weiter gegen den Uhrzeigersinn.
 */
@Immutable
data class SoundMotionUi(
    val rollDeg: Double?, val pitchDeg: Double?, val yawDeg: Double?, val moving: Boolean, val dotX: Double, val dotY: Double, val coverageMask: Int,
)

/** S-021: Urteil des Vergleichs ohne und mit Kamera (Spiegel von SoundCameraCheck.Verdict). */
enum class SoundVerdictUi { SAME, CHANGED, ONE_CHANNEL, NO_SOUND, SCENE_UNSTEADY, MOVED, NO_SOURCE, CAMERA_FAILED }

/** S-021: warum die Kamera in der Messung nicht lief. */
enum class SoundCameraFailureUi { NOT_STARTED, NO_FRAMES }

/** S-021: laufende Messung, Phase [index] von [total] (ab 0). */
@Immutable
data class SoundCheckProgressUi(val index: Int, val total: Int, val source: SoundSourceUi, val cameraOn: Boolean)

/** S-021: eine Phase der Messung; [recorded] false: die Aufnahme scheiterte, dann gelten die Messwerte nicht. */
@Immutable
data class SoundCheckPhaseUi(
    val source: SoundSourceUi, val cameraOn: Boolean, val recorded: Boolean, val angleDeg: Double? = null, val levelADbfs: Double = 0.0,
    val levelBDbfs: Double = 0.0, val stereo: Boolean = true, val movedPercent: Int? = null, val turnDeg: Double? = null,
    val rollDeg: Double? = null, val pitchDeg: Double? = null, val previewFps: Int? = null, val lostChunks: Long = 0,
)

@Immutable
data class SoundCheckVerdictUi(val source: SoundSourceUi, val verdict: SoundVerdictUi)

@Immutable
data class SoundCheckResultUi(
    val phases: List<SoundCheckPhaseUi>, val verdicts: List<SoundCheckVerdictUi>, val cameraFailure: SoundCameraFailureUi?,
    val viewWideDeg: Double?, val viewHighDeg: Double?, val motionAvailable: Boolean,
)

@Immutable
data class SoundImageUiState(
    val running: Boolean = false,
    val source: SoundSourceUi = SoundSourceUi.MIC,
    /** null bis zur ersten Momentaufnahme. */
    val hold: SoundHoldUi? = null,
    val bands: List<SoundBandUi> = emptyList(),
    val mainAngleDeg: Double? = null,
    val mainShare: Double = 0.0,
    /** Messwerte des Laufs (R27): Bilder und mittlere Rechenzeit der letzten Sekunde, verworfene Tonstuecke seit dem Start. */
    val framesPerSecond: Int = 0,
    val computeMillis: Double = 0.0,
    val lostChunks: Long = 0,
    /** S-021: Lage; null ohne Lagesensor. Hauptrichtung im Raum; null ohne Richtung oder Drehung. */
    val motion: SoundMotionUi? = null,
    val worldAngleDeg: Double? = null,
    /** S-021: Messung mit Kamera laeuft ([checkProgress]) oder ist fertig ([checkResult]); abgelehnte Kamera-Berechtigung. */
    val checkProgress: SoundCheckProgressUi? = null,
    val checkResult: SoundCheckResultUi? = null,
    val cameraDenied: Boolean = false,
    val failure: SoundFailureUi? = null,
    val permissionDenied: Boolean = false,
    /** Unerwarteter Fehler im Lauf (R24). */
    val failed: Boolean = false,
)

/**
 * Schallbild (S-018). Startet erst nach erteilter Berechtigung; Verlassen des Bildschirms haelt an und gibt Mikrofon
 * und Sensor frei. Ein Wechsel der Tonquelle startet neu, sobald der alte Lauf beendet ist (eine Aufnahme zur Zeit).
 * S-021: Die Messung mit Kamera ersetzt den laufenden Lauf (weiter nur eine Aufnahme zur Zeit) und zeigt am Ende ihr
 * Ergebnis; "Neu ansetzen" setzt Startlage und Ring des Lage-Kreises zurueck.
 */
@HiltViewModel
class SoundImageViewModel @Inject constructor(
    private val useCase: SoundImageUseCase,
    private val cameraCheck: SoundCameraUseCase,
) : ViewModel() {
    private val _state = MutableStateFlow(SoundImageUiState())
    val uiState: StateFlow<SoundImageUiState> = _state.asStateFlow()
    private var job: Job? = null
    private val resets = MutableStateFlow(0)

    fun onPermissionResult(granted: Boolean) {
        if (granted) start() else _state.update { it.copy(permissionDenied = true, running = false) }
    }

    /** Anhalten durch den Knopf, Bildschirm verlassen oder App im Hintergrund; bricht auch die Messung mit Kamera ab. */
    fun onStop() {
        val s = _state.value
        if (!s.running && s.checkProgress == null) return
        job?.cancel()
        _state.update { SoundImageUiState(source = it.source, checkResult = it.checkResult) }
    }

    fun onSourceSelected(source: SoundSourceUi) {
        val was = _state.value
        if (was.source == source || was.checkProgress != null) return
        _state.update { it.copy(source = source) }
        if (was.running) start()
    }

    /** Lage-Kreis: Startlage und Ring neu. */
    fun onResetCircle() { resets.update { it + 1 } }

    /** Ergebnis der Kamera-Berechtigung fuer die Messung; ohne sie bleibt das Schallbild, wie es ist. */
    fun onCameraPermissionResult(granted: Boolean) {
        if (!granted) { _state.update { it.copy(cameraDenied = true) }; return }
        if (_state.value.checkProgress != null) return
        val previous = job
        _state.update {
            SoundImageUiState(source = it.source, checkProgress = SoundCheckProgressUi(0, SoundCameraUseCase.SOURCES.size * SoundCameraUseCase.CAMERA_PLAN.size, SoundSourceUi.MIC, false))
        }
        job = viewModelScope.launch {
            previous?.cancelAndJoin()
            cameraCheck.run().catch {
                _state.update { s -> SoundImageUiState(source = s.source, failed = true) }
            }.collect { e ->
                when (e) {
                    is SoundCameraEvent.Phase -> _state.update { it.copy(checkProgress = SoundCheckProgressUi(e.index, e.total, sourceUi(e.source), e.cameraOn)) }
                    is SoundCameraEvent.Done -> _state.update { SoundImageUiState(source = it.source, checkResult = resultUi(e.report)) }
                }
            }
        }
    }

    private fun start() {
        val previous = job
        // das Ergebnis der letzten Messung bleibt stehen, bis eine neue beginnt
        _state.update { SoundImageUiState(running = true, source = it.source, checkResult = it.checkResult) }
        val source = _state.value.source
        job = viewModelScope.launch {
            previous?.cancelAndJoin()
            useCase.run(sourceKey(source), resets).catch {
                _state.update { s -> SoundImageUiState(source = s.source, failed = true) }
            }.collect { e ->
                when (e) {
                    is SoundImageEvent.Frame -> _state.update {
                        it.copy(hold = holdUi(e.hold), bands = e.bands.map(::bandUi), mainAngleDeg = e.mainAngleDeg, mainShare = e.mainShare,
                            framesPerSecond = e.stats.framesPerSecond, computeMillis = e.stats.computeMillis, lostChunks = e.stats.lostChunks,
                            motion = e.motion?.let(::motionUi), worldAngleDeg = e.worldAngleDeg)
                    }
                    is SoundImageEvent.Failed -> _state.update { s ->
                        SoundImageUiState(source = s.source, failure = failureUi(e.reason), permissionDenied = e.reason == SoundImageFailure.NO_PERMISSION)
                    }
                }
            }
        }
    }

    internal companion object {
        fun sourceKey(s: SoundSourceUi) = when (s) {
            SoundSourceUi.MIC -> AudioSourceKey.MIC
            SoundSourceUi.CAMCORDER -> AudioSourceKey.CAMCORDER
        }

        fun holdUi(h: SoundHold) = when (h) {
            SoundHold.TOP_LEFT -> SoundHoldUi.TOP_LEFT
            SoundHold.TOP_RIGHT -> SoundHoldUi.TOP_RIGHT
            SoundHold.NOT_LANDSCAPE -> SoundHoldUi.NOT_LANDSCAPE
            SoundHold.UNKNOWN -> SoundHoldUi.UNKNOWN
        }

        /** Keine Berechtigung steht als eigener Merker im Zustand, nicht als Fehler. */
        fun failureUi(f: SoundImageFailure) = when (f) {
            SoundImageFailure.NO_PERMISSION -> null
            SoundImageFailure.NO_STEREO -> SoundFailureUi.NO_STEREO
            SoundImageFailure.RECORD_FAILED -> SoundFailureUi.RECORD_FAILED
        }

        /** Nur Quellen des Schallbilds kommen vor; alles andere zaehlt als Mikrofon. */
        fun sourceUi(s: AudioSourceKey) = if (s == AudioSourceKey.CAMCORDER) SoundSourceUi.CAMCORDER else SoundSourceUi.MIC

        fun motionUi(m: SoundMotion) = SoundMotionUi(m.rollDeg, m.pitchDeg, m.yawDeg, m.moving, m.dotX, m.dotY, m.coverageMask)

        /** Erschoepfend statt valueOf: ein neues Urteil faellt beim Kompilieren auf. */
        fun verdictUi(v: SoundCameraCheck.Verdict) = when (v) {
            SoundCameraCheck.Verdict.SAME -> SoundVerdictUi.SAME
            SoundCameraCheck.Verdict.CHANGED -> SoundVerdictUi.CHANGED
            SoundCameraCheck.Verdict.ONE_CHANNEL -> SoundVerdictUi.ONE_CHANNEL
            SoundCameraCheck.Verdict.NO_SOUND -> SoundVerdictUi.NO_SOUND
            SoundCameraCheck.Verdict.SCENE_UNSTEADY -> SoundVerdictUi.SCENE_UNSTEADY
            SoundCameraCheck.Verdict.MOVED -> SoundVerdictUi.MOVED
            SoundCameraCheck.Verdict.NO_SOURCE -> SoundVerdictUi.NO_SOURCE
            SoundCameraCheck.Verdict.CAMERA_FAILED -> SoundVerdictUi.CAMERA_FAILED
        }

        fun phaseUi(p: SoundCameraPhase): SoundCheckPhaseUi {
            val s = p.stats ?: return SoundCheckPhaseUi(sourceUi(p.source), p.cameraOn, recorded = false, previewFps = p.previewFps)
            return SoundCheckPhaseUi(
                sourceUi(p.source), p.cameraOn, recorded = true, angleDeg = s.angleDeg, levelADbfs = s.levelADbfs, levelBDbfs = s.levelBDbfs,
                stereo = s.stereo, movedPercent = s.movedShare?.let { (it * 100).roundToInt() }, turnDeg = s.turnDeg, rollDeg = s.rollDeg,
                pitchDeg = s.pitchDeg, previewFps = p.previewFps, lostChunks = p.lostChunks,
            )
        }

        fun resultUi(r: SoundCameraReport) = SoundCheckResultUi(
            r.phases.map(::phaseUi), r.verdicts.map { SoundCheckVerdictUi(sourceUi(it.source), verdictUi(it.verdict)) },
            when { r.cameraError != null -> SoundCameraFailureUi.NOT_STARTED; !r.cameraFrames -> SoundCameraFailureUi.NO_FRAMES; else -> null },
            r.viewWideDeg, r.viewHighDeg, r.motionAvailable,
        )

        fun bandUi(b: SoundImageBand) = SoundBandUi(b.lowHz, b.highHz, b.levelDbfs, b.share, b.angleDeg, b.response)
    }
}
