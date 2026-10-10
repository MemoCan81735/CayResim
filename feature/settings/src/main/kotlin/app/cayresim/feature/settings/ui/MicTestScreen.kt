package app.cayresim.feature.settings.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.cayresim.feature.settings.R
import app.cayresim.feature.settings.control.CalibrationFailureUi
import app.cayresim.feature.settings.control.ClapPhaseUi
import app.cayresim.feature.settings.control.ClapRowUi
import app.cayresim.feature.settings.control.DirectionUi
import app.cayresim.feature.settings.control.MicFailureUi
import app.cayresim.feature.settings.control.MicStepUi
import app.cayresim.feature.settings.control.MicTestResultUi
import app.cayresim.feature.settings.control.MicTestUiState
import app.cayresim.feature.settings.control.MicTestViewModel
import app.cayresim.feature.settings.control.SourceRowUi
import app.cayresim.feature.settings.control.SourceUi
import java.util.Locale

@Composable
fun MicTestRoute(onBack: () -> Unit, viewModel: MicTestViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.onPermissionResult(it) }
    // Hintergrund bricht ab, das Mikrofon wird freigegeben (R28). Eine Drehung ist kein Abbruch (Zweitpruefung S-008);
    // das Verlassen des Bildschirms beendet den Lauf ueber das ViewModel.
    val activity = LocalActivity.current
    LifecycleStartEffect(Unit) { onStopOrDispose { if (activity?.isChangingConfigurations != true) viewModel.onStop() } }
    // Bildschirm bleibt waehrend des Laufs an (Lauf etwa 50 s mit Klatsch-Probe und Pausen, Samsung schaltet oft nach 30 s ab)
    val view = LocalView.current
    DisposableEffect(state.running) {
        view.keepScreenOn = state.running
        onDispose { view.keepScreenOn = false }
    }
    MicTestContent(
        state = state,
        onStart = {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (granted) viewModel.onPermissionResult(true) else launcher.launch(Manifest.permission.RECORD_AUDIO)
        },
        onBack = onBack,
    )
}

@Composable
fun MicTestContent(state: MicTestUiState, onStart: () -> Unit, onBack: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp).testTag("mictest"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { TextButton(onClick = onBack, modifier = Modifier.padding(top = 16.dp).testTag("back")) { Text(stringResource(R.string.back)) } }
            item { Text(stringResource(R.string.mictest_title), style = MaterialTheme.typography.headlineMedium) }
            item { Text(stringResource(R.string.mictest_intro), style = MaterialTheme.typography.bodyMedium) }
            item {
                Button(onClick = onStart, enabled = !state.running, modifier = Modifier.testTag("mictest_start")) {
                    Text(stringResource(if (state.running) R.string.mictest_running else R.string.mictest_start))
                }
            }
            if (state.permissionDenied) item {
                Text(stringResource(R.string.mictest_no_permission), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("mictest_permission"))
            }
            if (state.running) item { Progress(state) }
            if (state.failed) item {
                Text(stringResource(R.string.mictest_error), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("mictest_error"))
            }
            state.result?.let { r -> results(r) }
            item { Text("", Modifier.padding(bottom = 24.dp)) }
        }
    }
}

@Composable
private fun Progress(state: MicTestUiState) {
    Column(Modifier.fillMaxWidth().testTag("mictest_progress").semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (state.step) {
            MicStepUi.INVENTORY, MicStepUi.IDLE, MicStepUi.DONE -> Text(stringResource(R.string.mictest_step_inventory))
            MicStepUi.SOURCES -> {
                Text(stringResource(R.string.mictest_step_sources, state.sourceIndex + 1, state.sourceTotal, state.currentSource?.let { sourceLabel(it) } ?: ""))
                Text(stringResource(R.string.mictest_keep_quiet), style = MaterialTheme.typography.bodySmall)
            }
            MicStepUi.PREPARE -> {
                Text(stringResource(preparePrompt(state.clapPhase)), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.mictest_prepare_how, state.prepareSeconds), style = MaterialTheme.typography.bodyMedium)
            }
            MicStepUi.CLAP -> {
                Text(stringResource(clapPrompt(state.clapPhase)), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.mictest_clap_how, state.clapSeconds), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.results(r: MicTestResultUi) {
    item { Summary(r) }
    item { Heading(R.string.mictest_mics_title) }
    if (r.mics.isEmpty()) item { Text(stringResource(R.string.mictest_mics_none), style = MaterialTheme.typography.bodySmall) }
    itemsIndexed(r.mics) { i, m ->
        Text(
            stringResource(R.string.mictest_mic_line, m.id, m.address.ifBlank { "?" }, m.location, m.pattern,
                m.position?.let { p -> p.joinToString(" / ") { cm(it.toDouble()) } } ?: "?",
                m.sensitivityDb?.let { dec1(it.toDouble()) } ?: "?"),
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("mic_$i"),
        )
    }
    item { Heading(R.string.mictest_devices_title) }
    itemsIndexed(r.devices) { _, d ->
        Text(stringResource(R.string.mictest_device_line, d.id, d.kind, d.address.ifBlank { "?" }, d.channels.joinToString(", ").ifBlank { "?" }),
            style = MaterialTheme.typography.bodySmall)
    }
    item { Text(stringResource(R.string.mictest_unprocessed, yesNoText(r.unprocessedSupported)), style = MaterialTheme.typography.bodySmall) }
    item { Heading(R.string.mictest_sources_title) }
    itemsIndexed(r.sources) { i, s -> SourceLine(s, Modifier.testTag("source_$i").semantics(mergeDescendants = true) {}) }
    if (r.claps.isNotEmpty()) {
        item { Heading(R.string.mictest_claps_title) }
        item { CalibrationLine(r) }
        itemsIndexed(r.claps) { i, c -> ClapLines(c, Modifier.testTag("clap_$i").semantics(mergeDescendants = true) {}) }
    }
    item {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // mit Eichung steht der wirksame Abstand bei der Klatsch-Probe; ohne gilt der Abstand laut Geraet oder angenommen
            if (r.calibration == null) Text(
                stringResource(if (r.spacingMeasured) R.string.mictest_spacing_measured else R.string.mictest_spacing_assumed, dec1(r.spacingCm)),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(stringResource(R.string.mictest_duration, dec1(r.durationSeconds)), style = MaterialTheme.typography.bodySmall)
            Text(r.folder?.let { stringResource(R.string.mictest_folder, it) } ?: stringResource(R.string.mictest_folder_none),
                style = MaterialTheme.typography.bodySmall)
            if (r.storageFailed) Text(stringResource(R.string.mictest_storage_failed), color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.mictest_send), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun CalibrationLine(r: MicTestResultUi) {
    val c = r.calibration
    val text = when {
        c != null -> stringResource(R.string.mictest_calibrated, dec1(c.spacingCm), signed2(c.centerMs))
        r.calibrationFailure != null -> stringResource(calibrationFailureText(r.calibrationFailure))
        else -> return
    }
    Text(
        text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("mictest_calibration"),
        color = if (c == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
    )
}

private fun calibrationFailureText(f: CalibrationFailureUi) = when (f) {
    CalibrationFailureUi.TOO_FEW_CLAPS -> R.string.mictest_calibration_too_few
    CalibrationFailureUi.SIDES_NOT_DISTINCT -> R.string.mictest_calibration_upright
    CalibrationFailureUi.IMPLAUSIBLE -> R.string.mictest_calibration_implausible
}

@Composable
private fun Summary(r: MicTestResultUi) {
    val text = r.clapSource?.let { stringResource(R.string.mictest_summary_stereo, sourceName(it)) } ?: stringResource(R.string.mictest_summary_no_stereo)
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp).testTag("mictest_summary"))
}

@Composable
private fun Heading(@StringRes id: Int) = Text(stringResource(id), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))

@Composable
private fun SourceLine(s: SourceRowUi, modifier: Modifier) {
    Column(modifier.fillMaxWidth()) {
        Text(sourceLabel(s), fontWeight = FontWeight.Medium)
        val detail = if (s.failure != null) stringResource(failureText(s.failure)) else stringResource(
            R.string.mictest_source_detail,
            s.channels,
            yesNoText(s.distinct),
            s.levelDb.joinToString(" / ") { db(it) },
            dec1(s.identicalShare * 100),
            dec2(noNegativeZero(s.correlation)),
            dec2(noNegativeZero(s.diffCorrelation)),
        )
        Text(detail, style = MaterialTheme.typography.bodySmall)
        if (s.failure == null) Text(
            stringResource(R.string.mictest_source_route, s.routedDeviceId?.toString() ?: "?", s.activeMics.joinToString(", ").ifBlank { "?" },
                yesNoText(s.saved)),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ClapLines(c: ClapRowUi, modifier: Modifier) {
    Column(modifier.fillMaxWidth()) {
        Text(stringResource(phaseName(c.phase)), fontWeight = FontWeight.Medium)
        when {
            c.failure != null -> Text(stringResource(failureText(c.failure)), style = MaterialTheme.typography.bodySmall)
            c.claps.isEmpty() -> Text(stringResource(R.string.mictest_clap_none), style = MaterialTheme.typography.bodySmall)
            else -> c.claps.forEach { k ->
                Text(
                    stringResource(R.string.mictest_clap_line, dec2(k.timeSeconds), signed2(k.delayMs),
                        k.angleDegrees?.let { signed0(it) } ?: stringResource(R.string.mictest_angle_implausible),
                        dec2(k.similarity), signed1(k.levelDiffDb)),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun sourceLabel(s: SourceRowUi): String {
    val base = sourceName(s.source)
    return when {
        s.deviceId != null -> stringResource(R.string.mictest_source_device, base, s.deviceId)
        s.direction == DirectionUi.TOWARDS_USER -> stringResource(R.string.mictest_source_towards, base)
        s.direction == DirectionUi.AWAY_FROM_USER -> stringResource(R.string.mictest_source_away, base)
        else -> base
    }
}

@Composable
private fun sourceName(s: SourceUi) = stringResource(
    when (s) {
        SourceUi.MIC -> R.string.mictest_src_mic
        SourceUi.CAMCORDER -> R.string.mictest_src_camcorder
        SourceUi.VOICE_RECOGNITION -> R.string.mictest_src_voice
        SourceUi.UNPROCESSED -> R.string.mictest_src_unprocessed
    },
)

@Composable
private fun yesNoText(b: Boolean) = stringResource(if (b) R.string.device_yes else R.string.device_no)

private fun failureText(f: MicFailureUi) = when (f) {
    MicFailureUi.NO_PERMISSION -> R.string.mictest_fail_permission
    MicFailureUi.NOT_OFFERED -> R.string.mictest_fail_not_offered
    MicFailureUi.INIT_FAILED -> R.string.mictest_fail_init
    MicFailureUi.READ_FAILED -> R.string.mictest_fail_read
}

private fun phaseName(p: ClapPhaseUi) = when (p) {
    ClapPhaseUi.LEFT -> R.string.mictest_phase_left
    ClapPhaseUi.RIGHT -> R.string.mictest_phase_right
    ClapPhaseUi.FRONT -> R.string.mictest_phase_front
}

private fun preparePrompt(p: ClapPhaseUi?) = when (p) {
    ClapPhaseUi.LEFT, null -> R.string.mictest_prepare_left
    ClapPhaseUi.RIGHT -> R.string.mictest_prepare_right
    ClapPhaseUi.FRONT -> R.string.mictest_prepare_front
}

private fun clapPrompt(p: ClapPhaseUi?) = when (p) {
    ClapPhaseUi.LEFT, null -> R.string.mictest_clap_left
    ClapPhaseUi.RIGHT -> R.string.mictest_clap_right
    ClapPhaseUi.FRONT -> R.string.mictest_clap_front
}

/** Zahlen deutsch formatiert (Komma), unabhaengig von der Systemsprache der Tests. */
internal fun dec1(v: Double): String = String.format(Locale.GERMANY, "%.1f", v)
internal fun dec2(v: Double): String = String.format(Locale.GERMANY, "%.2f", v)
internal fun signed0(v: Double): String = String.format(Locale.GERMANY, "%+.0f", if (kotlin.math.abs(v) < 0.5) 0.0 else v)
internal fun signed1(v: Double): String = String.format(Locale.GERMANY, "%+.1f", if (kotlin.math.abs(v) < 0.05) 0.0 else v)
internal fun signed2(v: Double): String = String.format(Locale.GERMANY, "%+.2f", if (kotlin.math.abs(v) < 0.005) 0.0 else v)
/** -0,00 bei Werten knapp unter null vermeiden. */
internal fun noNegativeZero(v: Double): Double = if (kotlin.math.abs(v) < 0.005) 0.0 else v
internal fun cm(meters: Double): String = String.format(Locale.GERMANY, "%.1f", meters * 100)

/** Pegel in dBFS; Stille als "stumm" ist Sache der Anzeige, hier "-inf". */
internal fun db(v: Double): String = if (v == Double.NEGATIVE_INFINITY) "-inf" else String.format(Locale.GERMANY, "%.1f", v)
