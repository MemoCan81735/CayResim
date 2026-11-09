package app.cayresim.feature.settings.ui

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.cayresim.core.pure.DeviceTilt
import app.cayresim.core.pure.SoundBands
import app.cayresim.core.pure.SoundCameraCheck
import app.cayresim.feature.settings.R
import app.cayresim.feature.settings.control.SoundBandUi
import app.cayresim.feature.settings.control.SoundCameraFailureUi
import app.cayresim.feature.settings.control.SoundCheckPhaseUi
import app.cayresim.feature.settings.control.SoundCheckProgressUi
import app.cayresim.feature.settings.control.SoundCheckResultUi
import app.cayresim.feature.settings.control.SoundFailureUi
import app.cayresim.feature.settings.control.SoundHoldUi
import app.cayresim.feature.settings.control.SoundImageUiState
import app.cayresim.feature.settings.control.SoundImageViewModel
import app.cayresim.feature.settings.control.SoundMotionUi
import app.cayresim.feature.settings.control.SoundSourceUi
import app.cayresim.feature.settings.control.SoundVerdictUi
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.roundToInt

/** Wohin "rechts vom Nutzer" auf dem Bildschirm zeigt. */
internal enum class PanelAxis { RIGHT, LEFT, DOWN, UP }

/**
 * Die Streifen folgen der Haltung des Handys, nicht der Drehung der Oberflaeche: Ist "automatisch drehen" aus oder
 * dreht die Oberflaeche gerade erst nach, stehen links und rechts trotzdem richtig. [rotationDegrees]: Drehung der
 * Anzeige (0, 90, 180, 270); 90 heisst, das Handy wurde mit der Oberkante nach links gedreht. Ohne bekannte Haltung
 * gilt die Oberkante links.
 */
internal fun panelAxis(hold: SoundHoldUi?, rotationDegrees: Int): PanelAxis {
    val topRight = hold == SoundHoldUi.TOP_RIGHT
    return when (((rotationDegrees % 360) + 360) % 360) {
        90 -> if (topRight) PanelAxis.LEFT else PanelAxis.RIGHT
        270 -> if (topRight) PanelAxis.RIGHT else PanelAxis.LEFT
        180 -> if (topRight) PanelAxis.DOWN else PanelAxis.UP
        else -> if (topRight) PanelAxis.UP else PanelAxis.DOWN
    }
}

/**
 * S-021: Punkt des Lage-Kreises auf dem Bildschirm. [x], [y] aus Sicht des Nutzers (rechts, oben); zurueck kommt der
 * Versatz nach rechts und nach unten auf der Oberflaeche, je nachdem, wie sie gegenueber dem Nutzer gedreht ist.
 */
internal fun dotOnScreen(x: Double, y: Double, axis: PanelAxis): Pair<Float, Float> = when (axis) {
    PanelAxis.RIGHT -> x.toFloat() to -y.toFloat()
    PanelAxis.LEFT -> -x.toFloat() to y.toFloat()
    PanelAxis.DOWN -> y.toFloat() to x.toFloat()
    PanelAxis.UP -> -y.toFloat() to -x.toFloat()
}

@Composable
fun SoundImageRoute(onBack: () -> Unit, viewModel: SoundImageViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.onPermissionResult(it) }
    // S-021: die Kamera-Berechtigung wird hier nur fuer die Messung mit Kamera erfragt
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.onCameraPermissionResult(it) }
    val activity = LocalActivity.current
    // Hintergrund haelt an, Mikrofon und Sensor werden frei (R28, R29); Drehen der Oberflaeche ist kein Abbruch
    LifecycleStartEffect(Unit) { onStopOrDispose { if (activity?.isChangingConfigurations != true) viewModel.onStop() } }
    // Das Schallbild wird quer benutzt: die Oberflaeche dreht mit, auch wenn "automatisch drehen" aus ist. Der Wert von
    // vorher ueberlebt den Neustart der Activity beim Drehen und wird beim Verlassen zurueckgesetzt
    val before = rememberSaveable { activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    DisposableEffect(activity) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose { if (activity?.isChangingConfigurations == false) activity?.requestedOrientation = before }
    }
    val view = LocalView.current
    val busy = state.running || state.checkProgress != null
    DisposableEffect(busy) {
        view.keepScreenOn = busy
        onDispose { view.keepScreenOn = false }
    }
    SoundImageContent(
        state = state,
        // bei jeder Momentaufnahme neu gelesen: ein Drehen von quer nach quer (180 Grad) startet die Activity nicht neu
        rotationDegrees = (view.display?.rotation ?: 0) * 90,
        onStart = {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (granted) viewModel.onPermissionResult(true) else launcher.launch(Manifest.permission.RECORD_AUDIO)
        },
        onStop = viewModel::onStop,
        onSource = viewModel::onSourceSelected,
        onBack = onBack,
        onResetCircle = viewModel::onResetCircle,
        onCheck = {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            if (granted) viewModel.onCameraPermissionResult(true) else cameraLauncher.launch(Manifest.permission.CAMERA)
        },
    )
}

@Composable
fun SoundImageContent(
    state: SoundImageUiState, rotationDegrees: Int, onStart: () -> Unit, onStop: () -> Unit, onSource: (SoundSourceUi) -> Unit, onBack: () -> Unit,
    onResetCircle: () -> Unit = {}, onCheck: () -> Unit = {},
) {
    val busy = state.running || state.checkProgress != null
    Surface(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp).testTag("soundimage"),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onBack, modifier = Modifier.testTag("back")) { Text(stringResource(R.string.back)) }
                    Text(stringResource(R.string.soundimage_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(start = 8.dp))
                    Button(onClick = if (busy) onStop else onStart, modifier = Modifier.testTag("soundimage_start")) {
                        Text(stringResource(if (busy) R.string.soundimage_stop else R.string.soundimage_start))
                    }
                }
            }
            if (state.permissionDenied) item {
                Text(stringResource(R.string.soundimage_no_permission), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("soundimage_permission"))
            }
            state.failure?.let { f -> item { Text(stringResource(failureText(f)), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("soundimage_failure")) } }
            if (state.failed) item {
                Text(stringResource(R.string.soundimage_error), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("soundimage_error"))
            }
            if (state.cameraDenied) item {
                Text(stringResource(R.string.soundimage_camera_denied), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("soundimage_camera_denied"))
            }
            state.checkProgress?.let { p -> item { CheckProgress(p) } }
            state.checkResult?.let { r ->
                item { Text(stringResource(R.string.soundimage_check_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("soundimage_check_result")) }
                items(r.verdicts.size) { n -> VerdictLine(n, r) }
                items(r.phases.size) { n -> PhaseLine(n, r.phases[n]) }
                item { CheckFooter(r) }
            }
            if (!busy && state.checkResult == null) item { Text(stringResource(R.string.soundimage_intro), style = MaterialTheme.typography.bodyMedium) }
            if (state.running) {
                val axis = panelAxis(state.hold, rotationDegrees)
                item { Panel(state, axis) }
                item { Status(state) }
                item { MotionBlock(state, axis, onResetCircle) }
                items(state.bands.size) { n -> BandLine(n, state.bands[n]) }
            }
            if (state.checkProgress == null) item { Sources(state.source, onSource) }
            if (state.running) item {
                Column {
                    OutlinedButton(onClick = onCheck, modifier = Modifier.testTag("soundimage_check")) { Text(stringResource(R.string.soundimage_check)) }
                    Text(stringResource(R.string.soundimage_check_hint), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (state.running) item {
                Column {
                    // R27: Takt, Rechenzeit und Verluste als Zahlen, damit der Geraetetest sie mitliefert
                    Text(stringResource(R.string.soundimage_stats, state.framesPerSecond.toString(), dec1(state.computeMillis), state.lostChunks.toString()),
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("soundimage_stats"))
                    Text(stringResource(R.string.soundimage_send), style = MaterialTheme.typography.bodySmall)
                }
            }
            item { Text("", Modifier.padding(bottom = 16.dp)) }
        }
    }
}

@Composable
private fun Panel(state: SoundImageUiState, axis: PanelAxis) {
    val description = mainText(state)
    val horizontal = axis == PanelAxis.RIGHT || axis == PanelAxis.LEFT
    Box(Modifier.fillMaxWidth().height(if (horizontal) 150.dp else 320.dp).background(PANEL_BACKGROUND)) {
        Canvas(Modifier.fillMaxSize().testTag("soundimage_panel").semantics { contentDescription = description }) {
            drawGrid(axis)
            state.bands.forEachIndexed { n, b -> drawBand(b, BAND_COLORS[n % BAND_COLORS.size], axis) }
            state.mainAngleDeg?.let { drawMarker(it, axis) }
        }
        // Beschriftung der Enden, aus Sicht des Nutzers
        val left = stringResource(R.string.soundimage_label_left); val right = stringResource(R.string.soundimage_label_right)
        val (startAlign, endAlign) = if (horizontal) Alignment.TopStart to Alignment.TopEnd else Alignment.TopCenter to Alignment.BottomCenter
        val forward = axis == PanelAxis.RIGHT || axis == PanelAxis.DOWN
        Text(if (forward) left else right, color = PANEL_LABEL, style = MaterialTheme.typography.labelMedium, modifier = Modifier.align(startAlign).padding(6.dp))
        Text(if (forward) right else left, color = PANEL_LABEL, style = MaterialTheme.typography.labelMedium, modifier = Modifier.align(endAlign).padding(6.dp))
    }
}

/** Ort eines Winkels (-90 links bis +90 rechts) entlang der Achse, in Pixeln. */
private fun DrawScope.along(angleDeg: Double, axis: PanelAxis): Float {
    val p = ((angleDeg + 90.0) / 180.0).toFloat().coerceIn(0f, 1f)
    val length = if (axis == PanelAxis.RIGHT || axis == PanelAxis.LEFT) size.width else size.height
    return (if (axis == PanelAxis.RIGHT || axis == PanelAxis.DOWN) p else 1f - p) * length
}

/** Balken quer zur Achse zwischen zwei Orten. */
private fun DrawScope.bar(from: Float, to: Float, axis: PanelAxis, color: Color, alpha: Float, blend: BlendMode = BlendMode.SrcOver) {
    val a = minOf(from, to); val w = abs(to - from).coerceAtLeast(1f)
    if (axis == PanelAxis.RIGHT || axis == PanelAxis.LEFT) drawRect(color, Offset(a, 0f), Size(w, size.height), alpha = alpha, blendMode = blend)
    else drawRect(color, Offset(0f, a), Size(size.width, w), alpha = alpha, blendMode = blend)
}

private fun DrawScope.drawGrid(axis: PanelAxis) {
    for (angle in GRID_ANGLES) {
        val at = along(angle, axis)
        bar(at - 0.5f, at + 0.5f, axis, PANEL_LABEL, if (angle == 0.0) 0.5f else 0.2f)
    }
}

/**
 * Ein Band als Streifen: ueberall, wo die Kurve mindestens [STRIPE_FLOOR] ihres Maximums erreicht (ein reiner Ton passt
 * zu mehreren Richtungen), mit der Lautstaerke als Helligkeit. Farben addieren sich: Wo alle Baender dieselbe Richtung
 * zeigen, wird der Streifen weiss.
 */
private fun DrawScope.drawBand(b: SoundBandUi, color: Color, axis: PanelAxis) {
    if (b.angleDeg == null || b.share <= 0.0 || b.response.size != SoundBands.GRID) return
    val bright = MIN_STRIPE_ALPHA + (1f - MIN_STRIPE_ALPHA) * SoundBands.brightness(b.levelDbfs)
    for (i in 0 until SoundBands.GRID) {
        val t = ((b.response[i] / b.share.toFloat()) - STRIPE_FLOOR) / (1f - STRIPE_FLOOR)
        if (t <= 0f) continue
        bar(along(gridAngle(i - 0.5), axis), along(gridAngle(i + 0.5), axis), axis, color, (bright * t).coerceIn(0f, 1f), BlendMode.Plus)
    }
}

/** Dreieck am Rand an der Hauptrichtung: weiss mit dunklem Rand, damit es auch auf einem weissen Streifen sichtbar bleibt. */
private fun DrawScope.drawMarker(angleDeg: Double, axis: PanelAxis) {
    val at = along(angleDeg, axis)
    fun triangle(h: Float): Path {
        val path = Path()
        if (axis == PanelAxis.RIGHT || axis == PanelAxis.LEFT) {
            path.moveTo(at - h, size.height); path.lineTo(at + h, size.height); path.lineTo(at, size.height - h * 1.4f)
        } else {
            path.moveTo(0f, at - h); path.lineTo(0f, at + h); path.lineTo(h * 1.4f, at)
        }
        path.close()
        return path
    }
    drawPath(triangle(13.dp.toPx()), Color.Black)
    drawPath(triangle(9.dp.toPx()), Color.White)
}

/** Winkel der Stuetzstelle [index] (auch halbe Schritte fuer die Raender eines Streifens). */
private fun gridAngle(index: Double): Double =
    SoundBands.angleDegrees(-1.0 + 2.0 * index.coerceIn(0.0, (SoundBands.GRID - 1).toDouble()) / (SoundBands.GRID - 1))

@Composable
private fun mainText(state: SoundImageUiState): String = when {
    state.hold == null -> stringResource(R.string.soundimage_listening)
    state.hold == SoundHoldUi.NOT_LANDSCAPE -> stringResource(R.string.soundimage_hold_not)
    else -> state.mainAngleDeg?.let { stringResource(R.string.soundimage_main, angleText(it)) } ?: stringResource(R.string.soundimage_main_none)
}

@Composable
private fun Status(state: SoundImageUiState) {
    Column(Modifier.fillMaxWidth()) {
        Text(mainText(state), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("soundimage_main"))
        if (state.hold == SoundHoldUi.UNKNOWN) Text(stringResource(R.string.soundimage_hold_unknown), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("soundimage_hold"))
    }
}

@Composable
private fun angleText(angleDeg: Double): String {
    val whole = angleDeg.roundToInt()
    return when {
        whole > 0 -> stringResource(R.string.soundimage_angle_right, whole.toString())
        whole < 0 -> stringResource(R.string.soundimage_angle_left, (-whole).toString())
        else -> stringResource(R.string.soundimage_angle_center)
    }
}

@Composable
private fun BandLine(index: Int, b: SoundBandUi) {
    Row(Modifier.fillMaxWidth().testTag("soundimage_band_$index").semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).background(BAND_COLORS[index % BAND_COLORS.size]))
        val direction = b.angleDeg?.let { angleText(it) } ?: stringResource(R.string.soundimage_band_none)
        Text(
            stringResource(R.string.soundimage_band, b.lowHz.toString(), b.highHz.toString(), direction, dec2(b.share), levelText(b.levelDbfs)),
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/** S-021: Lagezeile, Richtung im Raum und Lage-Kreis. */
@Composable
private fun MotionBlock(state: SoundImageUiState, axis: PanelAxis, onResetCircle: () -> Unit) {
    val m = state.motion
    if (m == null) {
        Text(stringResource(R.string.soundimage_motion_none), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("soundimage_motion"))
        return
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TiltCircle(m, axis)
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.soundimage_motion, signedDeg(m.rollDeg), signedDeg(m.pitchDeg), signedDeg(m.yawDeg),
                    stringResource(if (m.moving) R.string.soundimage_motion_moving else R.string.soundimage_motion_calm)),
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, modifier = Modifier.testTag("soundimage_motion"),
            )
            state.worldAngleDeg?.let { Text(stringResource(R.string.soundimage_world, angleText(it)), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("soundimage_world")) }
            val visited = Integer.bitCount(m.coverageMask)
            Text(
                if (visited >= DeviceTilt.SECTORS) stringResource(R.string.soundimage_circle_done)
                else stringResource(R.string.soundimage_circle, visited.toString(), DeviceTilt.SECTORS.toString(), dec0(DeviceTilt.RIM_DEG)),
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("soundimage_circle_text"),
            )
            TextButton(onClick = onResetCircle, modifier = Modifier.testTag("soundimage_reset")) { Text(stringResource(R.string.soundimage_reset)) }
        }
    }
}

/**
 * Lage-Kreis: innerer Rand bei [DeviceTilt.RIM_DEG], aussen der Ring mit den besuchten Abschnitten, der Punkt zeigt
 * Drehung (waagerecht) und Schraegstellen (senkrecht) seit dem Start. Am Rand wird der Punkt gruen.
 */
@Composable
private fun TiltCircle(m: SoundMotionUi, axis: PanelAxis) {
    val description = stringResource(R.string.soundimage_circle_description)
    Canvas(Modifier.size(132.dp).background(PANEL_BACKGROUND).testTag("soundimage_circle").semantics { contentDescription = description }) {
        val center = Offset(size.width / 2, size.height / 2)
        // 1 = innerer Rand; das Feld reicht bis RIM_MAX_DEG, der Ring liegt knapp ausserhalb davon
        val unit = (size.minDimension / 2 / RING_OUTER).toFloat()
        fun at(x: Double, y: Double): Offset {
            val (dx, dy) = dotOnScreen(x, y, axis)
            return Offset(center.x + dx * unit, center.y + dy * unit)
        }
        drawCircle(PANEL_LABEL, radius = unit, center = center, alpha = 0.7f, style = Stroke(1.5.dp.toPx()))
        drawCircle(PANEL_LABEL, radius = 2.dp.toPx(), center = center, alpha = 0.5f)
        val step = 360.0 / DeviceTilt.SECTORS
        for (n in 0 until DeviceTilt.SECTORS) {
            val visited = m.coverageMask and (1 shl n) != 0
            val path = Path()
            for (k in 0..6) {
                val a = Math.toRadians(n * step + GAP_DEG + k * (step - 2 * GAP_DEG) / 6)
                val p = at(RING_MIDDLE * cos(a), RING_MIDDLE * sin(a))
                if (k == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            }
            drawPath(path, if (visited) DOT_AT_RIM else PANEL_LABEL, alpha = if (visited) 1f else 0.25f, style = Stroke(5.dp.toPx()))
        }
        val radius = hypot(m.dotX, m.dotY) * DeviceTilt.RIM_DEG
        val atRim = radius >= DeviceTilt.RIM_MIN_DEG && radius <= DeviceTilt.RIM_MAX_DEG
        val dot = at(m.dotX, m.dotY)
        drawCircle(Color.Black, radius = 9.dp.toPx(), center = dot)
        drawCircle(if (atRim) DOT_AT_RIM else Color.White, radius = 7.dp.toPx(), center = dot)
    }
}

@Composable
private fun CheckProgress(p: SoundCheckProgressUi) {
    Text(
        stringResource(R.string.soundimage_check_progress, (p.index + 1).toString(), p.total.toString(), sourceText(p.source), cameraText(p.cameraOn)),
        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("soundimage_check_progress"),
    )
}

@Composable private fun sourceText(s: SoundSourceUi) = stringResource(if (s == SoundSourceUi.MIC) R.string.soundimage_source_mic else R.string.soundimage_source_camcorder)
@Composable private fun cameraText(on: Boolean) = stringResource(if (on) R.string.soundimage_camera_on else R.string.soundimage_camera_off)

@Composable
private fun VerdictLine(index: Int, r: SoundCheckResultUi) {
    val v = r.verdicts[index]
    val good = v.verdict == SoundVerdictUi.SAME
    Text(
        stringResource(R.string.soundimage_check_verdict, sourceText(v.source), stringResource(verdictText(v.verdict))),
        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold,
        color = if (good) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error, modifier = Modifier.testTag("soundimage_verdict_$index"),
    )
}

@Composable
private fun PhaseLine(index: Int, p: SoundCheckPhaseUi) {
    val head = stringResource(R.string.soundimage_check_phase_head, sourceText(p.source), cameraText(p.cameraOn))
    val text = if (!p.recorded) stringResource(R.string.soundimage_check_phase_failed, head) else {
        val direction = p.angleDeg?.let { angleText(it) } ?: stringResource(R.string.soundimage_band_none)
        val base = stringResource(R.string.soundimage_check_phase, head, direction, levelText(p.levelADbfs), levelText(p.levelBDbfs))
        val mono = if (p.stereo) "" else stringResource(R.string.soundimage_check_phase_mono)
        val motion = if (p.movedPercent == null) "" else stringResource(R.string.soundimage_check_phase_motion,
            p.movedPercent.toString(), dec1(p.turnDeg ?: 0.0), signedDeg(p.rollDeg), signedDeg(p.pitchDeg))
        val camera = if (!p.cameraOn) "" else stringResource(R.string.soundimage_check_phase_camera, p.previewFps?.toString() ?: "?")
        val lost = if (p.lostChunks == 0L) "" else stringResource(R.string.soundimage_check_phase_lost, p.lostChunks.toString())
        base + mono + motion + camera + lost
    }
    Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("soundimage_phase_$index"))
}

@Composable
private fun CheckFooter(r: SoundCheckResultUi) {
    Column(Modifier.testTag("soundimage_check_footer")) {
        val small = MaterialTheme.typography.bodySmall
        when (r.cameraFailure) {
            SoundCameraFailureUi.NOT_STARTED -> Text(stringResource(R.string.soundimage_check_camera_not_started), style = small, color = MaterialTheme.colorScheme.error)
            SoundCameraFailureUi.NO_FRAMES -> Text(stringResource(R.string.soundimage_check_camera_no_frames), style = small, color = MaterialTheme.colorScheme.error)
            null -> Unit
        }
        Text(
            if (r.viewWideDeg != null && r.viewHighDeg != null) stringResource(R.string.soundimage_check_view, dec1(r.viewWideDeg), dec1(r.viewHighDeg))
            else stringResource(R.string.soundimage_check_view_unknown), style = small,
        )
        if (!r.motionAvailable) Text(stringResource(R.string.soundimage_motion_none), style = small)
        Text(stringResource(R.string.soundimage_check_limits, dec0(SoundCameraCheck.ANGLE_LIMIT_DEG), dec0(SoundCameraCheck.LEVEL_LIMIT_DB)), style = small)
        Text(stringResource(R.string.soundimage_check_send), style = small)
    }
}

private fun verdictText(v: SoundVerdictUi) = when (v) {
    SoundVerdictUi.SAME -> R.string.soundimage_verdict_same
    SoundVerdictUi.CHANGED -> R.string.soundimage_verdict_changed
    SoundVerdictUi.ONE_CHANNEL -> R.string.soundimage_verdict_one_channel
    SoundVerdictUi.NO_SOUND -> R.string.soundimage_verdict_no_sound
    SoundVerdictUi.SCENE_UNSTEADY -> R.string.soundimage_verdict_unsteady
    SoundVerdictUi.MOVED -> R.string.soundimage_verdict_moved
    SoundVerdictUi.NO_SOURCE -> R.string.soundimage_verdict_no_source
    SoundVerdictUi.CAMERA_FAILED -> R.string.soundimage_verdict_camera_failed
}

/** Winkel mit Vorzeichen ohne Nachkommastelle, "?" wenn unbekannt; -0 wird 0. */
internal fun signedDeg(deg: Double?): String {
    if (deg == null || deg.isNaN()) return "?"
    val whole = deg.roundToInt()
    return if (whole > 0) "+$whole" else whole.toString()
}

internal fun dec0(v: Double): String = String.format(Locale.GERMANY, "%.0f", v)

/** Pegel ohne Nachkommastelle; Stille (minus unendlich) wird auf -99 begrenzt. */
internal fun levelText(levelDbfs: Double): String =
    String.format(Locale.GERMANY, "%.0f", if (levelDbfs.isNaN()) -99.0 else levelDbfs.coerceIn(-99.0, 0.0))

@Composable
private fun Sources(selected: SoundSourceUi, onSource: (SoundSourceUi) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.soundimage_source), style = MaterialTheme.typography.bodyMedium)
        for (s in SoundSourceUi.entries) {
            FilterChip(
                selected = s == selected, onClick = { onSource(s) },
                label = { Text(stringResource(if (s == SoundSourceUi.MIC) R.string.soundimage_source_mic else R.string.soundimage_source_camcorder)) },
                modifier = Modifier.testTag("soundimage_source_${s.name}"),
            )
        }
    }
}

private fun failureText(f: SoundFailureUi) = when (f) {
    SoundFailureUi.NO_STEREO -> R.string.soundimage_no_stereo
    SoundFailureUi.RECORD_FAILED -> R.string.soundimage_record_failed
}

private val PANEL_BACKGROUND = Color(0xFF101014)
private val PANEL_LABEL = Color(0xFFB8B8C0)
/** Tief nach hoch: rot, orange, gruen, blau, violett. */
private val BAND_COLORS = listOf(Color(0xFFFF3B30), Color(0xFFFF9500), Color(0xFF34C759), Color(0xFF32ADE6), Color(0xFFAF52DE))
private val GRID_ANGLES = listOf(-90.0, -45.0, 0.0, 45.0, 90.0)
/** Anteil am Maximum der Kurve, ab dem ein Streifen sichtbar wird. */
private const val STRIPE_FLOOR = 0.6f
/** Ein gueltiges Band ist immer sichtbar, auch wenn es leise ist. */
private const val MIN_STRIPE_ALPHA = 0.25f
/** Lage-Kreis in Einheiten des inneren Rands: Mitte und Aussenkante des Rings, Luecke zwischen den Abschnitten in Grad. */
private const val RING_MIDDLE = 1.5
private const val RING_OUTER = 1.65
private const val GAP_DEG = 3.0
private val DOT_AT_RIM = Color(0xFF34C759)
