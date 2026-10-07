package app.cayresim.feature.camera.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.SurfaceRequest
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.cayresim.feature.camera.R
import app.cayresim.feature.camera.control.CameraUiState
import app.cayresim.feature.camera.control.CameraViewModel
import app.cayresim.feature.camera.control.MessageKind
import app.cayresim.feature.camera.control.ModeOption
import app.cayresim.feature.camera.control.PermissionStatus
import app.cayresim.feature.camera.control.ScreenStatus

@Composable
fun CameraRoute(
    onOpenGallery: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: CameraViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.onPermissionResult(it)
    }
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (granted) viewModel.onPermissionResult(true) else launcher.launch(Manifest.permission.CAMERA)
    }
    LifecycleStartEffect(Unit) {
        viewModel.onScreenStart()
        onStopOrDispose { viewModel.onScreenStop() }
    }
    CameraContent(
        state = state,
        onModeSelected = viewModel::onModeSelected,
        onShutter = viewModel::onShutter,
        onMessageShown = viewModel::onMessageShown,
        onRequestPermission = { launcher.launch(Manifest.permission.CAMERA) },
        onRetry = viewModel::onScreenStart,
        onOpenGallery = onOpenGallery,
        onOpenSettings = onOpenSettings,
        onOpenLast = { uri ->
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
        },
    )
}

/** Zustandslose Oberflaeche; [viewfinder] ist austauschbar, damit Screenshot-Tests ohne Kamera laufen. */
@Composable
fun CameraContent(
    state: CameraUiState,
    onModeSelected: (ModeOption) -> Unit,
    onShutter: () -> Unit,
    onMessageShown: (Long) -> Unit,
    onRequestPermission: () -> Unit,
    onRetry: () -> Unit,
    onOpenGallery: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLast: (String) -> Unit,
    viewfinder: @Composable (Any) -> Unit = { DefaultViewfinder(it) },
) {
    val snackbar = remember { SnackbarHostState() }
    val message = state.message
    val text = message?.let { stringResource(messageRes(it.kind)) }
    LaunchedEffect(message?.id) {
        if (message != null && text != null) {
            snackbar.showSnackbar(text)
            onMessageShown(message.id)
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when {
            state.permission == PermissionStatus.DENIED -> CenterHint(stringResource(R.string.permission_needed), stringResource(R.string.permission_grant), onRequestPermission, "permission")
            state.status == ScreenStatus.ERROR -> CenterHint(stringResource(R.string.camera_error), stringResource(R.string.retry), onRetry, "camera_error")
            state.previewToken != null -> Box(Modifier.fillMaxSize().testTag("viewfinder")) { viewfinder(state.previewToken) }
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().safeDrawingPadding().padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            state.fallbackFrom?.let {
                Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), shape = MaterialTheme.shapes.small,
                    modifier = Modifier.padding(16.dp).testTag("fallback")) {
                    Text(stringResource(R.string.fallback, stringResource(modeRes(it))), Modifier.padding(12.dp))
                }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 16.dp)) {
                items(state.modes) { m ->
                    FilterChip(selected = m == state.selected, onClick = { onModeSelected(m) },
                        label = { Text(stringResource(modeRes(m))) }, modifier = Modifier.testTag("mode_${m.name}"))
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onOpenGallery, modifier = Modifier.testTag("open_gallery")) { Text(stringResource(R.string.open_gallery), color = Color.White) }
                val shutterLabel = stringResource(R.string.shutter)
                Box(
                    Modifier.size(80.dp).border(4.dp, Color.White, CircleShape).padding(8.dp)
                        .background(if (state.canShoot) Color.White else Color.Gray, CircleShape)
                        .semantics { contentDescription = shutterLabel }
                        .testTag("shutter")
                        .then(if (state.canShoot) Modifier.clickableNoRipple(onShutter) else Modifier),
                )
                val last = state.lastPhotoUri
                if (last != null) TextButton(onClick = { onOpenLast(last) }, modifier = Modifier.testTag("open_last")) { Text(stringResource(R.string.open_last), color = Color.White) }
                else TextButton(onClick = onOpenSettings, modifier = Modifier.testTag("open_settings")) { Text(stringResource(R.string.open_settings), color = Color.White) }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.TopCenter).safeDrawingPadding().testTag("message"))
    }
}

@Composable
private fun DefaultViewfinder(token: Any) {
    if (token is SurfaceRequest) CameraXViewfinder(surfaceRequest = token, modifier = Modifier.fillMaxSize())
}

@Composable
private fun CenterHint(text: String, action: String, onAction: () -> Unit, tag: String) {
    Column(Modifier.fillMaxSize().padding(32.dp).testTag(tag), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, color = Color.White, style = MaterialTheme.typography.bodyLarge)
        Button(onClick = onAction, modifier = Modifier.padding(top = 16.dp)) { Text(action) }
    }
}

internal fun modeRes(m: ModeOption): Int = when (m) {
    ModeOption.NORMAL -> R.string.mode_normal
    ModeOption.AUTO -> R.string.mode_auto
    ModeOption.NIGHT -> R.string.mode_night
    ModeOption.HDR -> R.string.mode_hdr
    ModeOption.BOKEH -> R.string.mode_bokeh
    ModeOption.FACE_RETOUCH -> R.string.mode_face_retouch
}

internal fun messageRes(k: MessageKind): Int = when (k) {
    MessageKind.SAVED -> R.string.msg_saved
    MessageKind.FAILED_STORAGE -> R.string.msg_failed_storage
    MessageKind.FAILED_CAMERA -> R.string.msg_failed_camera
    MessageKind.FAILED_OTHER -> R.string.msg_failed_other
}
