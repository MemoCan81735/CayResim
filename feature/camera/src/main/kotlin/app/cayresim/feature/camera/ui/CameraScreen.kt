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
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material3.Slider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
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
import app.cayresim.feature.camera.control.LookOption
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
        onNextLook = viewModel::onNextLook,
        onSeriesSelected = viewModel::onSeriesSelected,
        onCreateSeries = viewModel::onCreateSeries,
        onOverlayAlpha = viewModel::onOverlayAlpha,
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
    onNextLook: () -> Unit,
    onSeriesSelected: (Long?) -> Unit,
    onCreateSeries: (String) -> Unit,
    onOverlayAlpha: (Float) -> Unit,
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
        // Geister-Overlay: gleiche Beschneidung wie der Sucher (ContentScale.Crop entspricht fillCenter, R21)
        state.overlay?.let {
            if (state.previewToken != null) Image(it, contentDescription = null, contentScale = ContentScale.Crop,
                alpha = state.overlayAlpha, modifier = Modifier.fillMaxSize().testTag("overlay"))
        }
        var picker by remember { mutableStateOf(false) }
        Row(Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TopChip(stringResource(lookRes(state.look)), onNextLook, "look")
            val sel = state.series.firstOrNull { it.id == state.selectedSeriesId }
            TopChip(if (sel == null) stringResource(R.string.series_none) else stringResource(R.string.series_label, sel.name, sel.photoCount), { picker = true }, "series")
        }
        if (state.overlay != null && state.previewToken != null) {
            Slider(value = state.overlayAlpha, onValueChange = onOverlayAlpha, valueRange = 0f..0.9f,
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp).fillMaxWidth(0.5f).testTag("overlay_alpha"))
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().safeDrawingPadding().padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            state.fallbackFrom?.let {
                Surface(color = Color.Black.copy(alpha = 0.7f), contentColor = Color.White, shape = MaterialTheme.shapes.small,
                    modifier = Modifier.padding(16.dp).testTag("fallback")) {
                    Text(stringResource(R.string.fallback, stringResource(modeRes(it))), Modifier.padding(12.dp))
                }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 16.dp)) {
                items(state.modes) { m ->
                    FilterChip(selected = m == state.selected, onClick = { onModeSelected(m) },
                        label = { Text(stringResource(modeRes(m)), maxLines = 1) }, modifier = Modifier.testTag("mode_${m.name}"),
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = Color.Black.copy(alpha = 0.55f), labelColor = Color.White,
                            selectedContainerColor = MaterialTheme.colorScheme.primary, selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        ))
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    TextButton(onClick = onOpenGallery, modifier = Modifier.testTag("open_gallery")) { SideLabel(stringResource(R.string.open_gallery)) }
                }
                val shutterLabel = stringResource(R.string.shutter)
                Box(
                    Modifier.size(80.dp).border(4.dp, Color.White, CircleShape).padding(8.dp)
                        .background(if (state.canShoot) Color.White else Color.Gray, CircleShape)
                        .semantics { contentDescription = shutterLabel }
                        .testTag("shutter")
                        .then(if (state.canShoot) Modifier.clickableNoRipple(onShutter) else Modifier),
                )
                val last = state.lastPhotoUri
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    if (last != null) TextButton(onClick = { onOpenLast(last) }, modifier = Modifier.testTag("open_last")) { SideLabel(stringResource(R.string.open_last)) }
                    else TextButton(onClick = onOpenSettings, modifier = Modifier.testTag("open_settings")) { SideLabel(stringResource(R.string.open_settings)) }
                }
            }
        }
        // Serienwahl zuoberst, damit nichts darunter bedienbar bleibt
        if (picker) SeriesPicker(state, onDismiss = { picker = false }, onSelect = { onSeriesSelected(it); picker = false },
            onCreate = { onCreateSeries(it); picker = false })
        SnackbarHost(snackbar, Modifier.align(Alignment.TopCenter).safeDrawingPadding().testTag("message"))
    }
}

@Composable
private fun TopChip(text: String, onClick: () -> Unit, tag: String) {
    Surface(color = Color.Black.copy(alpha = 0.55f), contentColor = Color.White, shape = MaterialTheme.shapes.small,
        modifier = Modifier.testTag(tag).clickableNoRipple(onClick)) {
        Text(text, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Serienwahl als Panel im Sucher statt Dialogfenster: bleibt im selben Fenster und ist so ohne Geraet testbar. */
@Composable
private fun BoxScope.SeriesPicker(state: CameraUiState, onDismiss: () -> Unit, onSelect: (Long?) -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)).clickableNoRipple(onDismiss))
    Surface(shape = MaterialTheme.shapes.large, tonalElevation = 6.dp,
        modifier = Modifier.align(Alignment.Center).fillMaxWidth(0.85f).testTag("series_picker")) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.series_title), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { onSelect(null) }, modifier = Modifier.testTag("series_none")) { Text(stringResource(R.string.series_none)) }
            state.series.forEach { s ->
                TextButton(onClick = { onSelect(s.id) }, modifier = Modifier.testTag("series_${s.id}")) { Text("${s.name} (${s.photoCount})") }
            }
            OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true,
                label = { Text(stringResource(R.string.series_new)) }, modifier = Modifier.fillMaxWidth().testTag("series_name"))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss, modifier = Modifier.testTag("series_cancel")) { Text(stringResource(R.string.series_cancel)) }
                TextButton(onClick = { onCreate(name) }, enabled = name.isNotBlank(), modifier = Modifier.testTag("series_create")) { Text(stringResource(R.string.series_create)) }
            }
        }
    }
}

internal fun lookRes(l: LookOption): Int = when (l) {
    LookOption.NONE -> R.string.look_none
    LookOption.WARM -> R.string.look_warm
    LookOption.COOL -> R.string.look_cool
    LookOption.FILM -> R.string.look_film
    LookOption.MONO -> R.string.look_mono
}

@Composable
private fun SideLabel(text: String) =
    Text(text, color = Color.White, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)

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
    MessageKind.SAVED_WITHOUT_LOOK -> R.string.msg_saved_without_look
    MessageKind.SAVED_WITHOUT_SERIES -> R.string.msg_saved_without_series
    MessageKind.SERIES_CREATED -> R.string.msg_series_created
    MessageKind.SERIES_INVALID -> R.string.msg_series_invalid
}
