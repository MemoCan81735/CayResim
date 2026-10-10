package app.cayresim.feature.settings.control

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.cayresim.core.control.SweepEvent
import app.cayresim.core.control.SweepRunFailure
import app.cayresim.core.control.SweepRunReport
import app.cayresim.core.control.SweepUseCase
import app.cayresim.core.pure.SweepMath
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Spiegel der Control-Typen fuer die UI (R1); nur Zahlen und Schluessel, den Text baut die UI (R23). */
enum class SweepStepUi { IDLE, TAP, SWEEP, ANALYZING, DONE }
enum class SweepFailureUi { NO_PERMISSION, NO_SENSOR, RECORD_FAILED }
enum class SweepEstimateFailureUi { NO_STEREO, TOO_FEW_MEASUREMENTS, NO_POSE, ONE_SIDED, IMPLAUSIBLE, WEAK_SIGNAL }

@Immutable
data class SweepResultUi(
    val failure: SweepFailureUi?,
    val estimateFailure: SweepEstimateFailureUi? = null,
    val azimuthDeg: Double? = null,
    val elevationDeg: Double? = null,
    val spacingCm: Double? = null,
    val residualMs: Double? = null,
    val coverage: Double = 0.0,
    val framesUsed: Int = 0,
    val framesWithPeak: Int = 0,
    val framesTotal: Int = 0,
    /** S-012: Median der GCC-PHAT-Spitze (Signalstaerke, Grenze 0,10). */
    val peakMedian: Double = 0.0,
    /** Richtung berechnet, aber keine Startlage fuer "relativ zum Kamerablick". */
    val noStartPose: Boolean = false,
    val syncMs: Double? = null,
    val sensorRateHz: Double = 0.0,
    val timeExact: Boolean = false,
    val folder: String? = null,
    val storageFailed: Boolean = false,
)

@Immutable
data class SweepUiState(
    val running: Boolean = false,
    val step: SweepStepUi = SweepStepUi.IDLE,
    val seconds: Int = 0,
    val result: SweepResultUi? = null,
    val permissionDenied: Boolean = false,
    /** Unerwarteter Fehler im Lauf (R24). */
    val failed: Boolean = false,
)

/** Schwenk-Messung (S-010). Startet erst nach erteilter Berechtigung; Verlassen des Bildschirms bricht ab. */
@HiltViewModel
class SweepViewModel @Inject constructor(private val useCase: SweepUseCase) : ViewModel() {
    private val _state = MutableStateFlow(SweepUiState())
    val uiState: StateFlow<SweepUiState> = _state.asStateFlow()
    private var job: Job? = null

    fun onPermissionResult(granted: Boolean) {
        if (granted) { _state.update { it.copy(permissionDenied = false) }; onStart() }
        else _state.update { it.copy(permissionDenied = true, running = false) }
    }

    fun onStart() {
        if (_state.value.running) return
        _state.value = SweepUiState(running = true, step = SweepStepUi.TAP, seconds = SweepUseCase.TAP_SECONDS)
        job = viewModelScope.launch {
            useCase.run().catch {
                _state.update { s -> s.copy(running = false, step = SweepStepUi.DONE, failed = true) }
            }.collect { e ->
                when (e) {
                    is SweepEvent.Tap -> _state.update { it.copy(step = SweepStepUi.TAP, seconds = e.seconds) }
                    is SweepEvent.Sweep -> _state.update { it.copy(step = SweepStepUi.SWEEP, seconds = e.seconds) }
                    SweepEvent.Analyzing -> _state.update { it.copy(step = SweepStepUi.ANALYZING) }
                    is SweepEvent.Done -> _state.update {
                        it.copy(
                            running = false, step = SweepStepUi.DONE,
                            permissionDenied = e.report.failure == SweepRunFailure.NO_PERMISSION,
                            result = if (e.report.failure == SweepRunFailure.NO_PERMISSION) null else toUi(e.report),
                        )
                    }
                }
            }
        }
    }

    /** Abbruch durch Bildschirm verlassen oder App im Hintergrund. */
    fun onStop() {
        if (!_state.value.running) return
        job?.cancel(); job = null
        _state.update { it.copy(running = false, step = SweepStepUi.IDLE) }
    }

    internal companion object {
        fun toUi(r: SweepRunReport): SweepResultUi {
            val a = r.analysis
            val e = a?.estimate
            val ok = e as? SweepMath.Estimate.Ok
            return SweepResultUi(
                failure = r.failure?.let { SweepFailureUi.valueOf(it.name) },
                estimateFailure = (e as? SweepMath.Estimate.Failed)?.reason?.let { SweepEstimateFailureUi.valueOf(it.name) },
                azimuthDeg = a?.relAzimuthDeg,
                elevationDeg = a?.relElevationDeg,
                spacingCm = ok?.spacingMeters?.times(100),
                residualMs = ok?.residualSeconds?.times(1000),
                coverage = e?.coverage ?: 0.0,
                framesUsed = a?.framesUsed ?: 0,
                framesWithPeak = a?.framesWithPeak ?: 0,
                noStartPose = ok != null && a.relAzimuthDeg == null,
                framesTotal = a?.framesTotal ?: 0,
                peakMedian = a?.peakMedian ?: 0.0,
                syncMs = a?.syncSeconds?.times(1000),
                sensorRateHz = a?.sensorRateHz ?: 0.0,
                timeExact = r.timeExact,
                folder = r.folder,
                storageFailed = r.storageFailed,
            )
        }
    }
}
