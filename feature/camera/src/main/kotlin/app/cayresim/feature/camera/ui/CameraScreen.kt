package app.cayresim.feature.camera.ui

import androidx.camera.viewfinder.compose.MutableCoordinateTransformer
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.delay
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import app.cayresim.feature.camera.control.SpecialOption
import app.cayresim.feature.camera.control.SpecialStatus
import app.cayresim.feature.camera.control.ProUi
import androidx.compose.material3.Switch
import app.cayresim.feature.camera.control.PermissionStatus
import app.cayresim.feature.camera.control.ScreenStatus

@Composable
fun CameraRoute(
    onOpenGallery: () -> Unit,
    onOpenSettings: () -> Unit,
    /** Meldet den Ausloeser fuer die Lautstaerketasten an (null = abmelden); die Shell faengt die Tasten ab. */
    setShutterKeyListener: ((() -> Unit)?) -> Unit = {},
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
        setShutterKeyListener(viewModel::onHardwareShutter)
        onStopOrDispose { setShutterKeyListener(null); viewModel.onScreenStop() }
    }
    CameraContent(
        state = state,
        onModeSelected = viewModel::onModeSelected,
        onNextLook = viewModel::onNextLook,
        onNextSpecial = viewModel::onNextSpecial,
        onExposure = viewModel::onExposure,
        onIso = viewModel::onIso,
        onFocus = viewModel::onFocus,
        onRaw = viewModel::onRaw,
        onSeriesSelected = viewModel::onSeriesSelected,
        onCreateSeries = viewModel::onCreateSeries,
        onOverlayAlpha = viewModel::onOverlayAlpha,
        onShutter = viewModel::onShutter,
        onZoomPreset = viewModel::onZoomPreset,
        onPinch = viewModel::onPinch,
        onTapFocus = viewModel::onTapFocus,
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
    onNextSpecial: () -> Unit,
    onExposure: (Float?) -> Unit = {},
    onIso: (Float) -> Unit = {},
    onFocus: (Float?) -> Unit = {},
    onRaw: (Boolean) -> Unit = {},
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
    onZoomPreset: (Float) -> Unit = {},
    onPinch: (Float) -> Unit = {},
    onTapFocus: (Float, Float) -> Unit = { _, _ -> },
    viewfinder: @Composable (Any, (Float, Float) -> Unit) -> Unit = { token, tap -> DefaultViewfinder(token, tap) },
) {
    val snackbar = remember { SnackbarHostState() }
    val message = state.message
    val text = message?.let { m -> stringResource(messageRes(m.kind)) + (m.detail?.let { "\n$it" } ?: "") }
    LaunchedEffect(message?.id) {
        if (message != null && text != null) {
            snackbar.showSnackbar(text)
            onMessageShown(message.id)
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black).testTag("camera_root")) {
        when {
            state.permission == PermissionStatus.DENIED -> CenterHint(stringResource(R.string.permission_needed), stringResource(R.string.permission_grant), onRequestPermission, "permission")
            state.status == ScreenStatus.ERROR -> CenterHint(stringResource(R.string.camera_error), stringResource(R.string.retry), onRetry, "camera_error")
            state.previewToken != null -> Box(
                Modifier.fillMaxSize().testTag("viewfinder")
                    .pointerInput(Unit) { detectTransformGestures { _, _, zoom, _ -> if (zoom != 1f) onPinch(zoom) } },
            ) { viewfinder(state.previewToken, onTapFocus) }
        }
        // Geister-Overlay: gleiche Beschneidung wie der Sucher (ContentScale.Crop entspricht fillCenter, R21)
        state.overlay?.let {
            if (state.previewToken != null) Image(it, contentDescription = null, contentScale = ContentScale.Crop,
                alpha = state.overlayAlpha, modifier = Modifier.fillMaxSize().testTag("overlay"))
        }
        var picker by remember { mutableStateOf(false) }
        // Bei grosser Schrift seitlich wischbar statt abgeschnitten
        Row(Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(top = 8.dp).padding(end = 56.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TopChip(stringResource(lookRes(state.look)), onNextLook, "look")
            TopChip(stringResource(specialRes(state.special)), onNextSpecial, "special")
            val sel = state.series.firstOrNull { it.id == state.selectedSeriesId }
            TopChip(if (sel == null) stringResource(R.string.series_none) else stringResource(R.string.series_label, sel.name, sel.photoCount), { picker = true }, "series")
        }
        // Immer sichtbar und nie abgeschnitten, auch nach dem ersten Foto (Selbsttest)
        val settingsLabel = stringResource(R.string.open_settings)
        Surface(onClick = onOpenSettings, color = Color.Black.copy(alpha = 0.55f), contentColor = Color.White, shape = CircleShape,
            modifier = Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(top = 8.dp, end = 8.dp).size(40.dp)
                .semantics { contentDescription = settingsLabel }.testTag("open_settings")) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Filled.Settings, contentDescription = null) }
        }
        if (state.overlay != null && state.previewToken != null) {
            Slider(value = state.overlayAlpha, onValueChange = onOverlayAlpha, valueRange = 0f..0.9f,
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp).fillMaxWidth(0.5f).testTag("overlay_alpha"))
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().safeDrawingPadding().padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            val hint = when (state.specialStatus) {
                SpecialStatus.COLLECTING, SpecialStatus.PROCESSING -> stringResource(R.string.special_collecting)
                SpecialStatus.ARMED -> stringResource(R.string.special_armed)
                SpecialStatus.IDLE -> if (state.autoNight) stringResource(R.string.auto_night) else null
            }
            hint?.let {
                Surface(color = Color.Black.copy(alpha = 0.7f), contentColor = Color.White, shape = MaterialTheme.shapes.small,
                    modifier = Modifier.padding(8.dp).testTag("special_hint")) { Text(it, Modifier.padding(12.dp)) }
            }
            if (state.special == SpecialOption.PRO) ProPanel(state.pro, onExposure, onIso, onFocus, onRaw)
            state.fallbackFrom?.let {
                Surface(color = Color.Black.copy(alpha = 0.7f), contentColor = Color.White, shape = MaterialTheme.shapes.small,
                    modifier = Modifier.padding(16.dp).testTag("fallback")) {
                    Text(stringResource(R.string.fallback, stringResource(modeRes(it))), Modifier.padding(12.dp))
                }
            }
            if (state.zoomPresets.isNotEmpty() && state.previewToken != null) ZoomRow(state, onZoomPreset)
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
        Text(text, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), maxLines = 1, softWrap = false)
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

@Composable
private fun ProPanel(pro: ProUi, onExposure: (Float?) -> Unit, onIso: (Float) -> Unit, onFocus: (Float?) -> Unit, onRaw: (Boolean) -> Unit) {
    Surface(color = Color.Black.copy(alpha = 0.7f), contentColor = Color.White, shape = MaterialTheme.shapes.medium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().testTag("pro_panel")) {
        Column(Modifier.padding(12.dp)) {
            if (!pro.canExpose && !pro.canFocus && !pro.canRaw) { Text(stringResource(R.string.pro_not_available)); return@Column }
            if (pro.canExpose) {
                ProRow(stringResource(R.string.pro_exposure), pro.exposureLabel, pro.exposure, { onExposure(it) }, { onExposure(null) }, "pro_exposure")
                ProRow(stringResource(R.string.pro_iso), pro.isoLabel, pro.iso, { onIso(it) }, { onExposure(null) }, "pro_iso")
            }
            if (pro.canFocus) ProRow(stringResource(R.string.pro_focus), if (pro.focus == null) stringResource(R.string.pro_auto) else "", pro.focus, { onFocus(it) }, { onFocus(null) }, "pro_focus")
            if (pro.canRaw) Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.pro_raw), Modifier.weight(1f))
                Switch(checked = pro.raw, onCheckedChange = onRaw, modifier = Modifier.testTag("pro_raw"))
            }
        }
    }
}

@Composable
private fun ProRow(label: String, value: String, slider: Float?, onChange: (Float) -> Unit, onAuto: () -> Unit, tag: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(0.28f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Slider(value = slider ?: 0.5f, onValueChange = onChange, modifier = Modifier.weight(0.5f).testTag(tag))
        TextButton(onClick = onAuto, modifier = Modifier.weight(0.22f).testTag("${tag}_auto")) {
            Text(if (slider == null) stringResource(R.string.pro_auto) else value, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

internal fun specialRes(o: SpecialOption): Int = when (o) {
    SpecialOption.NONE -> R.string.special_none
    SpecialOption.CLEAN_PLATE -> R.string.special_clean_plate
    SpecialOption.LONG_EXPOSURE -> R.string.special_long_exposure
    SpecialOption.TRIGGER_MOTION -> R.string.special_trigger_motion
    SpecialOption.TRIGGER_STILL -> R.string.special_trigger_still
    SpecialOption.PRO -> R.string.special_pro
    SpecialOption.FOCUS_STACK -> R.string.special_focus_stack
    SpecialOption.ASTRO -> R.string.special_astro
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
private fun DefaultViewfinder(token: Any, onTap: (Float, Float) -> Unit) {
    if (token !is SurfaceRequest) return
    // Wandelt Bildschirm-Koordinaten in Koordinaten des Kamerabilds um (beruecksichtigt Drehung und Beschnitt)
    val transformer = remember { MutableCoordinateTransformer() }
    var ring by remember { mutableStateOf<Offset?>(null) }
    LaunchedEffect(ring) { if (ring != null) { delay(FOCUS_RING_MS); ring = null } }
    Box(Modifier.fillMaxSize()) {
        CameraXViewfinder(
            surfaceRequest = token,
            coordinateTransformer = transformer,
            modifier = Modifier.fillMaxSize().pointerInput(token) {
                detectTapGestures { offset ->
                    ring = offset
                    val p = with(transformer) { offset.transform() }
                    val res = token.resolution
                    onTap(p.x / res.width, p.y / res.height)
                }
            },
        )
        ring?.let { FocusRing(it) }
    }
}

private const val FOCUS_RING_MS = 1_200L

/** Kreis an der angetippten Stelle als Rueckmeldung. */
@Composable
internal fun FocusRing(at: Offset) {
    val density = LocalDensity.current
    val r = 36.dp
    val px = with(density) { r.toPx() }
    Box(
        Modifier.offset { IntOffset((at.x - px).toInt(), (at.y - px).toInt()) }
            .size(r * 2).border(2.dp, Color.White, CircleShape).testTag("focus_ring"),
    )
}

/**
 * Zoom-Schnellwahl wie bei Samsung: die Stufe, in deren Bereich der Zoom gerade liegt, zeigt den
 * genauen Wert (z. B. "2,4x") und ist hervorgehoben.
 */
@Composable
private fun ZoomRow(state: CameraUiState, onZoomPreset: (Float) -> Unit) {
    val current = state.zoomPresets.lastOrNull { it.ratio <= state.zoomRatio + 0.05f } ?: state.zoomPresets.first()
    Row(
        Modifier.padding(bottom = 12.dp).background(Color.Black.copy(alpha = 0.45f), CircleShape).padding(4.dp).testTag("zoom_row"),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        state.zoomPresets.forEach { p ->
            val selected = p == current
            val label = if (selected) state.zoomLabel else p.label
            Surface(
                onClick = { onZoomPreset(p.ratio) },
                shape = CircleShape,
                color = if (selected) Color.White.copy(alpha = 0.25f) else Color.Transparent,
                contentColor = if (selected) Color(0xFFFFD54F) else Color.White,
                modifier = Modifier.size(if (selected) 48.dp else 40.dp).testTag("zoom_${p.label}"),
            ) {
                Box(contentAlignment = Alignment.Center) { Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1) }
            }
        }
    }
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
    MessageKind.STACK_SAVED -> R.string.msg_stack_saved
    MessageKind.STACK_SHORTENED -> R.string.msg_stack_shortened
    MessageKind.STACK_FAILED -> R.string.msg_stack_failed
    MessageKind.TRIGGER_FIRED -> R.string.msg_trigger_fired
    MessageKind.FIXED_FOCUS -> R.string.msg_fixed_focus
    MessageKind.NO_MANUAL -> R.string.msg_no_manual
    MessageKind.SAVED_WITH_RAW -> R.string.msg_saved_with_raw
    MessageKind.NIGHT_SAVED -> R.string.msg_night_saved
    MessageKind.NIGHT_FAILED -> R.string.msg_night_failed
}
