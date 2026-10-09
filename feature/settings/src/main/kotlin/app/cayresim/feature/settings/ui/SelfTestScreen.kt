package app.cayresim.feature.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.cayresim.feature.settings.R
import app.cayresim.feature.settings.control.CheckKind
import app.cayresim.feature.settings.control.CheckRow
import app.cayresim.feature.settings.control.DeviceInfoUi
import app.cayresim.feature.settings.control.RawProbeUi
import app.cayresim.feature.settings.control.SelfTestUiState
import app.cayresim.feature.settings.control.SelfTestViewModel

@Composable
fun SelfTestRoute(onBack: () -> Unit, viewModel: SelfTestViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SelfTestContent(state, onStart = viewModel::onStart, onBack = onBack)
}

private val Green = Color(0xFF2E7D32)
private val Red = Color(0xFFC62828)

@Composable
fun SelfTestContent(state: SelfTestUiState, onStart: () -> Unit, onBack: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onBack, modifier = Modifier.testTag("back")) { Text(stringResource(R.string.back)) }
            Text(stringResource(R.string.selftest_title), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.selftest_intro), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onStart, enabled = !state.running, modifier = Modifier.testTag("selftest_start")) {
                Text(stringResource(if (state.running) R.string.selftest_running else R.string.selftest_start))
            }
            if (state.finished) {
                Text(
                    stringResource(if (state.allPassed) R.string.selftest_all_ok else R.string.selftest_some_failed),
                    color = if (state.allPassed) Green else Red,
                    modifier = Modifier.testTag("selftest_summary"),
                )
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(state.rows) { i, row -> CheckLine(row, Modifier.testTag("row_$i").semantics(mergeDescendants = true) {}) }
            }
        }
    }
}

@Composable
private fun CheckLine(row: CheckRow, modifier: Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (row.passed) "●" else "✕", color = if (row.passed) Green else Red)
        Column(Modifier.weight(1f)) {
            Text(label(row))
            val extra = listOfNotNull(row.detail.ifBlank { null }, if (row.durationMillis > 0) "${row.durationMillis} ms" else null)
            if (extra.isNotEmpty()) Text(extra.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            row.device?.let { DeviceLines(it) }
            row.raw?.let { RawLines(it) }
        }
    }
}

@Composable
private fun label(row: CheckRow): String = when (row.kind) {
    CheckKind.LAST_RUN -> stringResource(R.string.check_last_run)
    CheckKind.CAMERA_START -> stringResource(R.string.check_camera_start)
    CheckKind.CAPABILITIES -> stringResource(R.string.check_capabilities)
    CheckKind.DEVICE -> stringResource(R.string.check_device)
    CheckKind.MODE_CAPTURE -> stringResource(R.string.check_mode_capture, row.modeName ?: "")
    CheckKind.LOW_LIGHT_BOOST -> stringResource(R.string.check_low_light_boost)
    CheckKind.ULTRA_HDR -> stringResource(R.string.check_ultra_hdr)
    CheckKind.RAW -> stringResource(R.string.check_raw)
    CheckKind.RAW_SERIES -> stringResource(R.string.check_raw_series)
    CheckKind.CLEANUP -> stringResource(R.string.check_cleanup)
}

/** Belichtungszeit lesbar: unter 1 s als Bruch (1/x s), sonst in Sekunden. */
internal fun exposureText(ns: Long): String =
    if (ns >= 1_000_000_000L) "%.1f s".format(ns / 1e9) else "1/${Math.round(1e9 / ns)} s"

@Composable
private fun DeviceLines(d: DeviceInfoUi) {
    val yes = stringResource(R.string.device_yes); val no = stringResource(R.string.device_no)
    fun yn(b: Boolean) = if (b) yes else no
    val lines = buildList {
        add(stringResource(R.string.device_level, d.hardwareLevel))
        if (d.exposureMinNs != null && d.exposureMaxNs != null)
            add(stringResource(R.string.device_exposure, exposureText(d.exposureMinNs), exposureText(d.exposureMaxNs)))
        if (d.isoMin != null && d.isoMax != null) add(stringResource(R.string.device_iso, d.isoMin, d.isoMax))
        d.maxFrameNs?.let { add(stringResource(R.string.device_max_frame, exposureText(it))) }
        if (d.slowestFpsMin != null && d.slowestFpsMax != null) add(stringResource(R.string.device_fps, d.slowestFpsMin, d.slowestFpsMax))
        add(stringResource(R.string.device_raw_burst, yn(d.raw), yn(d.burst)))
        if (d.sensorWidth != null && d.sensorHeight != null)
            add(stringResource(R.string.device_sensor, d.sensorWidth, d.sensorHeight, "%.1f".format(d.sensorWidth.toLong() * d.sensorHeight / 1e6)))
        d.zsl?.let { add(stringResource(R.string.device_zsl, yn(it))) }
        if (d.zoomMin != null && d.zoomMax != null) add(stringResource(R.string.device_zoom, "%.1f".format(d.zoomMin), "%.1f".format(d.zoomMax)))
        d.physicalCameras?.let { add(stringResource(R.string.device_cameras, it)) }
        if (d.chip.isNotBlank()) add(stringResource(R.string.device_chip, d.chip))
        if (d.system.isNotBlank()) add(stringResource(R.string.device_system, d.system))
    }
    lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
}

/** Messwerte der RAW-Serie, z. B. "Bilder: 8 von 8, je 180 ms (hoechstens 240 ms)". */
@Composable
private fun RawLines(r: RawProbeUi) {
    val yes = stringResource(R.string.device_yes); val no = stringResource(R.string.device_no)
    fun yn(b: Boolean) = if (b) yes else no
    fun dec(v: Float) = String.format(java.util.Locale.GERMANY, "%.1f", v)
    val lines = buildList {
        add(stringResource(R.string.raw_frames, r.frames, r.requested, r.avgFrameMs, r.maxFrameMs))
        add(stringResource(R.string.raw_size, r.width, r.height))
        add(stringResource(R.string.raw_levels, r.blackLevel.joinToString("/"), r.whiteLevel?.toString() ?: "?", r.cfa))
        add(stringResource(R.string.raw_calibration, yn(r.colorMatrix), yn(r.forwardMatrix), yn(r.lensShading)))
        add(stringResource(R.string.raw_signal, dec(r.meanAboveBlack), r.noise?.let { dec(it) } ?: "?"))
        add(stringResource(R.string.raw_zero, dec(r.zeroShare * 100f)))
        add(if (r.streamFps == null) stringResource(R.string.raw_stream_none)
            else stringResource(R.string.raw_stream, dec(r.streamFps), r.streamMaxFps?.let { dec(it) } ?: "?"))
        if (r.streamBlack != null || r.streamWhite != null)
            add(stringResource(R.string.raw_stream_levels, r.streamBlack?.joinToString("/") { dec(it) } ?: "?", r.streamWhite?.toString() ?: "?"))
    }
    lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
}
