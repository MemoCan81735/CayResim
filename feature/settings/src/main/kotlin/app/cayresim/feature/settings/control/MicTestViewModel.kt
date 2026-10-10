package app.cayresim.feature.settings.control

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.cayresim.core.boundary.MicFailure
import app.cayresim.core.boundary.MicRequest
import app.cayresim.core.control.MicTestEvent
import app.cayresim.core.control.MicTestReport
import app.cayresim.core.control.MicTestUseCase
import app.cayresim.core.control.SourceOutcome
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Spiegel der Boundary- und Control-Typen fuer die UI (R1); nur Schluessel und Zahlen, den Text baut die UI (R23). */
enum class SourceUi { MIC, CAMCORDER, VOICE_RECOGNITION, UNPROCESSED }
enum class DirectionUi { NONE, TOWARDS_USER, AWAY_FROM_USER }
enum class MicFailureUi { NO_PERMISSION, NOT_OFFERED, INIT_FAILED, READ_FAILED }
enum class ClapPhaseUi { LEFT, RIGHT, FRONT }
enum class MicStepUi { IDLE, INVENTORY, SOURCES, CLAP, DONE }

@Immutable data class MicUi(val id: Int, val address: String, val location: String, val pattern: String, val position: List<Float>?, val sensitivityDb: Float?)
@Immutable data class InputDeviceUi(val id: Int, val kind: String, val address: String, val channels: List<Int>)

@Immutable
data class SourceRowUi(
    val source: SourceUi,
    val direction: DirectionUi,
    val deviceId: Int?,
    val failure: MicFailureUi?,
    val channels: Int = 0,
    val distinct: Boolean = false,
    val levelDb: List<Double> = emptyList(),
    val identicalShare: Double = 0.0,
    val correlation: Double = 0.0,
    val routedDeviceId: Int? = null,
    val activeMics: List<Int> = emptyList(),
    val saved: Boolean = false,
)

@Immutable data class ClapUi(val timeSeconds: Double, val delayMs: Double, val angleDegrees: Double?, val similarity: Double, val levelDiffDb: Double)
@Immutable data class ClapRowUi(val phase: ClapPhaseUi, val claps: List<ClapUi>, val failure: MicFailureUi?)

@Immutable
data class MicTestResultUi(
    val mics: List<MicUi>,
    val devices: List<InputDeviceUi>,
    val unprocessedSupported: Boolean,
    val sources: List<SourceRowUi>,
    val clapSource: SourceUi?,
    val claps: List<ClapRowUi>,
    val spacingCm: Double,
    val spacingMeasured: Boolean,
    val durationSeconds: Double,
    val folder: String?,
    val storageFailed: Boolean,
)

@Immutable
data class MicTestUiState(
    val running: Boolean = false,
    val step: MicStepUi = MicStepUi.IDLE,
    val sourceIndex: Int = 0,
    val sourceTotal: Int = 0,
    val currentSource: SourceRowUi? = null,
    val clapPhase: ClapPhaseUi? = null,
    val clapSeconds: Int = 0,
    val result: MicTestResultUi? = null,
    val permissionDenied: Boolean = false,
    /** Unerwarteter Fehler im Lauf (R24): Anzeige statt Absturz. */
    val failed: Boolean = false,
)

/** Mikrofon-Test (S-008). Startet erst nach erteilter Berechtigung; Verlassen des Bildschirms bricht ab (R28). */
@HiltViewModel
class MicTestViewModel @Inject constructor(private val useCase: MicTestUseCase) : ViewModel() {
    private val _state = MutableStateFlow(MicTestUiState())
    val uiState: StateFlow<MicTestUiState> = _state.asStateFlow()
    private var job: Job? = null

    fun onPermissionResult(granted: Boolean) {
        if (granted) {
            _state.update { it.copy(permissionDenied = false) }
            onStart()
        } else {
            _state.update { it.copy(permissionDenied = true, running = false) }
        }
    }

    fun onStart() {
        if (_state.value.running) return
        _state.value = MicTestUiState(running = true, step = MicStepUi.INVENTORY)
        job = viewModelScope.launch {
            useCase.run().catch {
                // R24, Zweitpruefung S-008: ein unerwarteter Fehler beendet nur den Lauf, nicht die App
                _state.update { s -> s.copy(running = false, step = MicStepUi.DONE, failed = true, clapPhase = null, currentSource = null) }
            }.collect { e ->
                when (e) {
                    MicTestEvent.Inventory -> _state.update { it.copy(step = MicStepUi.INVENTORY) }
                    is MicTestEvent.Source -> _state.update {
                        it.copy(step = MicStepUi.SOURCES, sourceIndex = e.index, sourceTotal = e.total, currentSource = rowOf(e.request, null))
                    }
                    is MicTestEvent.ClapPrompt -> _state.update {
                        it.copy(step = MicStepUi.CLAP, clapPhase = ClapPhaseUi.valueOf(e.phase.name), clapSeconds = e.seconds)
                    }
                    is MicTestEvent.Done -> _state.update {
                        val r = e.report
                        it.copy(
                            running = false, step = MicStepUi.DONE, clapPhase = null, currentSource = null,
                            permissionDenied = r.failure == MicFailure.NO_PERMISSION,
                            result = if (r.failure == MicFailure.NO_PERMISSION) null else r.toUi(),
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
        _state.update { it.copy(running = false, step = MicStepUi.IDLE, clapPhase = null, currentSource = null) }
    }

    internal companion object {
        fun rowOf(r: MicRequest, failure: MicFailure?) = SourceRowUi(
            SourceUi.valueOf(r.source.name), DirectionUi.valueOf(r.direction.name), r.deviceId, failure?.let { MicFailureUi.valueOf(it.name) },
        )

        fun MicTestReport.toUi() = MicTestResultUi(
            mics = inventory?.microphones.orEmpty().map { MicUi(it.id, it.address, it.location.name, it.pattern.name, it.positionMeters, it.sensitivityDb) },
            devices = inventory?.devices.orEmpty().map { InputDeviceUi(it.id, it.kind.name, it.address, it.channelCounts) },
            unprocessedSupported = inventory?.unprocessedSupported == true,
            sources = sources.map { s ->
                when (val o = s.outcome) {
                    is SourceOutcome.Failed -> rowOf(s.request, o.reason)
                    is SourceOutcome.Recorded -> rowOf(s.request, null).copy(
                        channels = o.stats.channels, distinct = o.stats.distinctChannels, levelDb = o.stats.levelDbfs,
                        identicalShare = o.stats.identicalShare, correlation = o.stats.correlation,
                        routedDeviceId = o.routedDeviceId, activeMics = o.activeMicIds, saved = o.fileUri != null,
                    )
                }
            },
            clapSource = clapSource?.let { SourceUi.valueOf(it.source.name) },
            claps = claps.map { c ->
                ClapRowUi(
                    ClapPhaseUi.valueOf(c.phase.name),
                    c.claps.map { k -> ClapUi(k.sample.toDouble() / (clapSource?.sampleRate ?: 48_000), k.delaySeconds * 1000, k.angleDegrees, k.similarity, k.levelDiffDb) },
                    c.failure?.let { MicFailureUi.valueOf(it.name) },
                )
            },
            spacingCm = spacingMeters * 100,
            spacingMeasured = spacingMeasured,
            durationSeconds = sourcesMillis / 1000.0,
            folder = folder,
            storageFailed = storageFailed,
        )
    }
}
