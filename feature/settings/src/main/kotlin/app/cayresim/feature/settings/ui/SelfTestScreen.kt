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
    CheckKind.CLEANUP -> stringResource(R.string.check_cleanup)
}
