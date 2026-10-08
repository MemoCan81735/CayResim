package app.cayresim.feature.settings.control

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.cayresim.core.boundary.DeviceReport
import app.cayresim.core.control.SelfTestCheck
import app.cayresim.core.control.SelfTestUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class CheckKind { LAST_RUN, CAMERA_START, CAPABILITIES, DEVICE, MODE_CAPTURE, LOW_LIGHT_BOOST, ULTRA_HDR, RAW, CLEANUP }

@Immutable
data class CheckRow(
    val kind: CheckKind,
    val modeName: String?,
    val passed: Boolean,
    val durationMillis: Long,
    val detail: String,
    val device: DeviceInfoUi? = null,
)

/** Spiegel von DeviceReport fuer die UI (R1: die UI kennt keine Boundary-Typen); nur Zahlen, den Text baut die UI. */
@Immutable
data class DeviceInfoUi(
    val hardwareLevel: String,
    val exposureMinNs: Long?,
    val exposureMaxNs: Long?,
    val isoMin: Int?,
    val isoMax: Int?,
    val maxFrameNs: Long?,
    val slowestFpsMin: Int?,
    val slowestFpsMax: Int?,
    val raw: Boolean,
    val burst: Boolean,
    val sensorWidth: Int?,
    val sensorHeight: Int?,
    val zsl: Boolean?,
    val zoomMin: Float?,
    val zoomMax: Float?,
    val physicalCameras: Int?,
    val chip: String,
    val system: String,
)

internal fun DeviceReport.toUi() = DeviceInfoUi(
    hardwareLevel = hardwareLevel.name,
    exposureMinNs = exposureNs?.first, exposureMaxNs = exposureNs?.last,
    isoMin = iso?.first, isoMax = iso?.last,
    maxFrameNs = maxFrameNs,
    slowestFpsMin = slowestFps?.first, slowestFpsMax = slowestFps?.last,
    raw = raw, burst = burst,
    sensorWidth = sensorWidth, sensorHeight = sensorHeight,
    zsl = zsl, zoomMin = zoomMin, zoomMax = zoomMax,
    physicalCameras = physicalCameras, chip = chip, system = system,
)

@Immutable
data class SelfTestUiState(
    val running: Boolean = false,
    val rows: List<CheckRow> = emptyList(),
    val finished: Boolean = false,
) {
    val allPassed: Boolean get() = finished && rows.isNotEmpty() && rows.all { it.passed }
}

@HiltViewModel
class SelfTestViewModel @Inject constructor(private val selfTest: SelfTestUseCase) : ViewModel() {
    private val _state = MutableStateFlow(SelfTestUiState())
    val uiState: StateFlow<SelfTestUiState> = _state.asStateFlow()

    fun onStart() {
        if (_state.value.running) return
        _state.value = SelfTestUiState(running = true)
        viewModelScope.launch {
            val report = runCatching { selfTest() }.getOrNull()
            _state.update {
                SelfTestUiState(
                    running = false,
                    finished = true,
                    rows = report?.items?.map { i ->
                        CheckRow(CheckKind.valueOf(i.check.name), i.mode?.name, i.passed, i.durationMillis, i.detail, i.device?.toUi())
                    } ?: listOf(CheckRow(CheckKind.CAMERA_START, null, false, 0, "Abbruch")),
                )
            }
        }
    }

    internal companion object { val kinds = SelfTestCheck.entries.map { CheckKind.valueOf(it.name) } }
}
