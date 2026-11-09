package app.cayresim.feature.settings.control

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.cayresim.core.boundary.AudioSourceKey
import app.cayresim.core.control.SoundHold
import app.cayresim.core.control.SoundImageBand
import app.cayresim.core.control.SoundImageEvent
import app.cayresim.core.control.SoundImageFailure
import app.cayresim.core.control.SoundImageUseCase
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

/** Spiegel der Control-Typen fuer die UI (R1); nur Zahlen und Schluessel, den Text baut die UI (R23). */
enum class SoundSourceUi { MIC, CAMCORDER }
enum class SoundHoldUi { TOP_LEFT, TOP_RIGHT, NOT_LANDSCAPE, UNKNOWN }
enum class SoundFailureUi { NO_STEREO, RECORD_FAILED }

/** Ein Band: [angleDeg] negativ links, positiv rechts, null ohne Richtung; [response] Kurve von links nach rechts. */
@Immutable
data class SoundBandUi(val lowHz: Int, val highHz: Int, val levelDbfs: Double, val share: Double, val angleDeg: Double?, val response: List<Float>)

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
    val failure: SoundFailureUi? = null,
    val permissionDenied: Boolean = false,
    /** Unerwarteter Fehler im Lauf (R24). */
    val failed: Boolean = false,
)

/**
 * Schallbild (S-018). Startet erst nach erteilter Berechtigung; Verlassen des Bildschirms haelt an und gibt Mikrofon
 * und Sensor frei. Ein Wechsel der Tonquelle startet neu, sobald der alte Lauf beendet ist (eine Aufnahme zur Zeit).
 */
@HiltViewModel
class SoundImageViewModel @Inject constructor(private val useCase: SoundImageUseCase) : ViewModel() {
    private val _state = MutableStateFlow(SoundImageUiState())
    val uiState: StateFlow<SoundImageUiState> = _state.asStateFlow()
    private var job: Job? = null

    fun onPermissionResult(granted: Boolean) {
        if (granted) start() else _state.update { it.copy(permissionDenied = true, running = false) }
    }

    /** Anhalten durch den Knopf, Bildschirm verlassen oder App im Hintergrund. */
    fun onStop() {
        if (!_state.value.running) return
        job?.cancel()
        _state.update { SoundImageUiState(source = it.source) }
    }

    fun onSourceSelected(source: SoundSourceUi) {
        val was = _state.value
        if (was.source == source) return
        _state.update { it.copy(source = source) }
        if (was.running) start()
    }

    private fun start() {
        val previous = job
        _state.update { SoundImageUiState(running = true, source = it.source) }
        val source = _state.value.source
        job = viewModelScope.launch {
            previous?.cancelAndJoin()
            useCase.run(sourceKey(source)).catch {
                _state.update { s -> SoundImageUiState(source = s.source, failed = true) }
            }.collect { e ->
                when (e) {
                    is SoundImageEvent.Frame -> _state.update {
                        it.copy(hold = holdUi(e.hold), bands = e.bands.map(::bandUi), mainAngleDeg = e.mainAngleDeg, mainShare = e.mainShare,
                            framesPerSecond = e.stats.framesPerSecond, computeMillis = e.stats.computeMillis, lostChunks = e.stats.lostChunks)
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

        fun bandUi(b: SoundImageBand) = SoundBandUi(b.lowHz, b.highHz, b.levelDbfs, b.share, b.angleDeg, b.response)
    }
}
