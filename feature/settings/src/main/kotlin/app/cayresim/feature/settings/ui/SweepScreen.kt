package app.cayresim.feature.settings.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
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
import app.cayresim.feature.settings.control.SweepEstimateFailureUi
import app.cayresim.feature.settings.control.SweepFailureUi
import app.cayresim.feature.settings.control.SweepResultUi
import app.cayresim.feature.settings.control.SweepStepUi
import app.cayresim.feature.settings.control.SweepUiState
import app.cayresim.feature.settings.control.SweepViewModel

@Composable
fun SweepRoute(onBack: () -> Unit, viewModel: SweepViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.onPermissionResult(it) }
    // Hintergrund bricht ab, Mikrofon und Sensor werden frei (R28, R29); Drehen ist hier Teil der Messung, kein Abbruch
    val activity = LocalActivity.current
    LifecycleStartEffect(Unit) { onStopOrDispose { if (activity?.isChangingConfigurations != true) viewModel.onStop() } }
    val view = LocalView.current
    DisposableEffect(state.running) {
        view.keepScreenOn = state.running
        onDispose { view.keepScreenOn = false }
    }
    SweepContent(
        state = state,
        onStart = {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (granted) viewModel.onPermissionResult(true) else launcher.launch(Manifest.permission.RECORD_AUDIO)
        },
        onBack = onBack,
    )
}

@Composable
fun SweepContent(state: SweepUiState, onStart: () -> Unit, onBack: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp).testTag("sweep"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { TextButton(onClick = onBack, modifier = Modifier.padding(top = 16.dp).testTag("back")) { Text(stringResource(R.string.back)) } }
            item { Text(stringResource(R.string.sweep_title), style = MaterialTheme.typography.headlineMedium) }
            item { Text(stringResource(R.string.sweep_intro), style = MaterialTheme.typography.bodyMedium) }
            item { Text(stringResource(R.string.sweep_steps), style = MaterialTheme.typography.bodyMedium) }
            item {
                Button(onClick = onStart, enabled = !state.running, modifier = Modifier.testTag("sweep_start")) {
                    Text(stringResource(if (state.running) R.string.sweep_running else R.string.sweep_start))
                }
            }
            if (state.permissionDenied) item {
                Text(stringResource(R.string.sweep_no_permission), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("sweep_permission"))
            }
            if (state.running) item { Prompt(state) }
            if (state.failed) item {
                Text(stringResource(R.string.sweep_error), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("sweep_error"))
            }
            state.result?.let { r -> item { SweepResult(r) } }
            item { Text("", Modifier.padding(bottom = 24.dp)) }
        }
    }
}

@Composable
private fun Prompt(state: SweepUiState) {
    Column(Modifier.fillMaxWidth().testTag("sweep_prompt").semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val (title, how) = when (state.step) {
            SweepStepUi.TAP -> R.string.sweep_tap to R.string.sweep_tap_how
            SweepStepUi.SWEEP -> R.string.sweep_sweep to R.string.sweep_sweep_how
            else -> R.string.sweep_analyzing to null
        }
        Text(stringResource(title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        how?.let { Text(stringResource(it, state.seconds), style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun SweepResult(r: SweepResultUi) {
    Column(Modifier.fillMaxWidth().testTag("sweep_result"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.sweep_result_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        val failure = r.failure
        if (failure != null) Text(stringResource(failureText(failure)), color = MaterialTheme.colorScheme.error)
        else Measured(r)
    }
}

@Composable
private fun Measured(r: SweepResultUi) {
    val az = r.azimuthDeg; val el = r.elevationDeg; val estimateFailure = r.estimateFailure
    when {
        estimateFailure != null -> Text(stringResource(estimateFailureText(estimateFailure)), color = MaterialTheme.colorScheme.error)
        az != null && el != null -> Text(stringResource(R.string.sweep_direction, signed0(az), signed0(el)), fontWeight = FontWeight.Medium)
        r.noStartPose -> Text(stringResource(R.string.sweep_no_start), color = MaterialTheme.colorScheme.error)
    }
    val spacing = r.spacingCm; val residual = r.residualMs
    if (spacing != null && residual != null) Text(stringResource(R.string.sweep_spacing, dec1(spacing), dec2(residual)), style = MaterialTheme.typography.bodySmall)
    Text(stringResource(R.string.sweep_coverage, dec2(r.coverage), r.framesWithPeak, r.framesUsed, r.framesTotal), style = MaterialTheme.typography.bodySmall)
    // S-012: Signalstaerke als Zahl, damit Geraetetests sie mitliefern
    Text(stringResource(R.string.sweep_signal, dec2(r.peakMedian)), style = MaterialTheme.typography.bodySmall)
    val sync = r.syncMs
    Text(sync?.let { stringResource(R.string.sweep_sync, signed1(it)) } ?: stringResource(R.string.sweep_sync_none), style = MaterialTheme.typography.bodySmall)
    if (!r.timeExact) Text(stringResource(R.string.sweep_time_rough), style = MaterialTheme.typography.bodySmall)
    Text(stringResource(R.string.sweep_rate, dec1(r.sensorRateHz)), style = MaterialTheme.typography.bodySmall)
    Text(r.folder?.let { stringResource(R.string.sweep_folder, it) } ?: stringResource(R.string.sweep_folder_none), style = MaterialTheme.typography.bodySmall)
    if (r.storageFailed) Text(stringResource(R.string.sweep_storage_failed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    Text(stringResource(R.string.sweep_send), style = MaterialTheme.typography.bodySmall)
}

private fun failureText(f: SweepFailureUi) = when (f) {
    SweepFailureUi.NO_PERMISSION -> R.string.sweep_no_permission
    SweepFailureUi.NO_SENSOR -> R.string.sweep_no_sensor
    SweepFailureUi.RECORD_FAILED -> R.string.sweep_record_failed
}

private fun estimateFailureText(f: SweepEstimateFailureUi) = when (f) {
    SweepEstimateFailureUi.NO_STEREO -> R.string.sweep_fail_no_stereo
    SweepEstimateFailureUi.TOO_FEW_MEASUREMENTS -> R.string.sweep_fail_too_few
    SweepEstimateFailureUi.NO_POSE -> R.string.sweep_fail_no_pose
    SweepEstimateFailureUi.ONE_SIDED -> R.string.sweep_fail_one_sided
    SweepEstimateFailureUi.IMPLAUSIBLE -> R.string.sweep_fail_implausible
    SweepEstimateFailureUi.WEAK_SIGNAL -> R.string.sweep_fail_weak
}
