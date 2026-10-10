package app.cayresim.feature.settings.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
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
import app.cayresim.feature.settings.control.SweepMoveUi
import app.cayresim.feature.settings.control.SweepStepUi
import app.cayresim.core.pure.SweepGuide
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
            // S-014: Waehrend der Messung nur die Anzeige des Schritts, damit sie auch quer ganz sichtbar ist
            // (Zweitpruefung S-014, W3); vorher Einleitung, Ablauf als Bilder und Startknopf
            if (state.running) item { Prompt(state) } else {
                item { Text(stringResource(R.string.sweep_title), style = MaterialTheme.typography.headlineMedium) }
                item { Text(stringResource(R.string.sweep_intro), style = MaterialTheme.typography.bodyMedium) }
                item { GuideList() }
                item {
                    Button(onClick = onStart, modifier = Modifier.testTag("sweep_start")) { Text(stringResource(R.string.sweep_start)) }
                }
            }
            if (state.permissionDenied) item {
                Text(stringResource(R.string.sweep_no_permission), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("sweep_permission"))
            }
            if (state.failed) item {
                Text(stringResource(R.string.sweep_error), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("sweep_error"))
            }
            state.result?.let { r -> item { SweepResult(r) } }
            item { Text("", Modifier.padding(bottom = 24.dp)) }
        }
    }
}

/** Ein Schritt der Anleitung: Bild, Zeit, Titel, Erklaerung. */
private data class GuideItem(val picture: SweepPicture, @StringRes val title: Int, @StringRes val how: Int)

private val guideItems = listOf(
    GuideItem(SweepPicture.START, R.string.sweep_move_start, R.string.sweep_move_start_how),
    GuideItem(SweepPicture.TAP, R.string.sweep_move_tap, R.string.sweep_move_tap_how),
    GuideItem(SweepPicture.YAW, R.string.sweep_move_yaw, R.string.sweep_move_yaw_how),
    GuideItem(SweepPicture.ROLL, R.string.sweep_move_roll, R.string.sweep_move_roll_how),
    GuideItem(SweepPicture.PITCH, R.string.sweep_move_pitch, R.string.sweep_move_pitch_how),
    GuideItem(SweepPicture.CIRCLE, R.string.sweep_move_circle, R.string.sweep_move_circle_how),
)

private fun picture(m: SweepMoveUi) = when (m) {
    SweepMoveUi.TAP -> SweepPicture.TAP
    SweepMoveUi.YAW -> SweepPicture.YAW
    SweepMoveUi.ROLL -> SweepPicture.ROLL
    SweepMoveUi.PITCH -> SweepPicture.PITCH
    SweepMoveUi.CIRCLE -> SweepPicture.CIRCLE
}

/** Erschoepfend statt ueber die Reihenfolge der Enums (Zweitpruefung S-014, H8). */
private fun guideItem(m: SweepMoveUi) = when (m) {
    SweepMoveUi.TAP -> guideItems[1]
    SweepMoveUi.YAW -> guideItems[2]
    SweepMoveUi.ROLL -> guideItems[3]
    SweepMoveUi.PITCH -> guideItems[4]
    SweepMoveUi.CIRCLE -> guideItems[5]
}

@Composable
private fun GuideList() {
    Column(Modifier.fillMaxWidth().testTag("sweep_guide"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        guideItems.forEachIndexed { i, g ->
            Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
                SweepPictogram(g.picture, 96.dp)
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    // Zeiten aus SweepGuide, damit Anleitung und Ablauf nie auseinanderlaufen
                    val time = if (i == 0) stringResource(R.string.sweep_time_before)
                        else SweepGuide.PHASES[i - 1].let { p -> stringResource(R.string.sweep_time_range, p.fromSeconds.toInt().toString(), p.toSeconds.toInt().toString()) }
                    Text(time, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(stringResource(g.title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(stringResource(g.how), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun Prompt(state: SweepUiState) {
    val move = state.move
    if (state.step == SweepStepUi.ANALYZING || move == null) {
        Text(stringResource(R.string.sweep_analyzing), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
            modifier = Modifier.testTag("sweep_prompt"))
    } else {
        // Bild links, Text rechts: passt hochkant und quer ohne Scrollen (Zweitpruefung S-014, W3)
        Row(Modifier.fillMaxWidth().testTag("sweep_prompt").semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
            SweepPictogram(picture(move), 150.dp)
            Column(Modifier.padding(start = 12.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(guideItem(move).title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(stringResource(guideItem(move).how), style = MaterialTheme.typography.bodyMedium)
                val next = state.next
                Text(
                    if (next != null) stringResource(R.string.sweep_left_next, state.seconds, stringResource(guideItem(next).title))
                    else stringResource(R.string.sweep_left_last, state.seconds),
                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                )
                if (move != SweepMoveUi.TAP) {
                    // Balken voll ab COVERAGE_GOOD. Er misst alle Drehlagen; die Auswertung zaehlt danach nur Fenster mit
                    // klarer Spitze, deshalb steht beides in meta.json (coverageLive, coverage)
                    LinearProgressIndicator(progress = { (state.coverage / COVERAGE_GOOD).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().testTag("sweep_coverage_bar"))
                    val level = when {
                        state.coverage >= COVERAGE_GOOD -> R.string.sweep_cov_ok
                        state.coverage >= COVERAGE_GOOD / 3 -> R.string.sweep_cov_mid
                        else -> R.string.sweep_cov_low
                    }
                    Text(stringResource(R.string.sweep_coverage_live, stringResource(level)), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
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

/** S-014: Abdeckung, ab der der Balken voll ist ("genug"); etwa zwei Drittel der echten Laeufe vom 10.10. (0,22, 0,23) */
private const val COVERAGE_GOOD = 0.15
