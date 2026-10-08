package app.cayresim.feature.camera.control

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.cayresim.core.boundary.CameraBoundary
import app.cayresim.core.boundary.CameraStateSnapshot
import app.cayresim.core.boundary.CameraStatus
import app.cayresim.core.boundary.CaptureFailure
import app.cayresim.core.boundary.Look
import app.cayresim.core.boundary.PhotoMode
import app.cayresim.core.boundary.ProcessingBoundary
import app.cayresim.core.boundary.SeriesBoundary
import app.cayresim.core.boundary.SeriesResult
import app.cayresim.core.boundary.SeriesSnapshot
import app.cayresim.core.control.CaptureOutcome
import app.cayresim.core.control.CaptureUseCase
import app.cayresim.core.control.StackOutcome
import app.cayresim.core.control.StackPhotoUseCase
import app.cayresim.core.control.FocusStackUseCase
import app.cayresim.core.control.AstroUseCase
import app.cayresim.core.control.NightUseCase
import app.cayresim.core.pure.NightPlan
import app.cayresim.core.boundary.ManualCameraBoundary
import app.cayresim.core.boundary.ManualCapabilitiesSnapshot
import app.cayresim.core.boundary.ManualStateSnapshot
import app.cayresim.core.boundary.FrameBoundary
import app.cayresim.core.boundary.StackMode
import app.cayresim.core.boundary.TriggerMode
import kotlinx.coroutines.Job
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CameraViewModel @Inject constructor(
    private val camera: CameraBoundary,
    private val capture: CaptureUseCase,
    private val seriesBoundary: SeriesBoundary,
    private val processing: ProcessingBoundary,
    private val stackPhoto: StackPhotoUseCase,
    private val frames: FrameBoundary,
    private val manual: ManualCameraBoundary,
    private val focusStack: FocusStackUseCase,
    private val astro: AstroUseCase,
    private val night: NightUseCase,
) : ViewModel() {

    private data class Local(
        val permission: PermissionStatus = PermissionStatus.UNKNOWN,
        val visible: Boolean = false,
        val lastPhotoUri: String? = null,
        val message: UserMessage? = null,
        val nextMessageId: Long = 1,
        val look: LookOption = LookOption.NONE,
        val selectedSeriesId: Long? = null,
        val overlayAlpha: Float = 0.4f,
        val special: SpecialOption = SpecialOption.NONE,
        val specialStatus: SpecialStatus = SpecialStatus.IDLE,
        /** Automatik (Standard): CayResim waehlt selbst, im Dunkeln den Nacht-Kern. */
        val auto: Boolean = true,
    )

    private var triggerJob: Job? = null
    /** Laufende Nachtaufnahme; wird beim Verlassen des Screens abgebrochen (Befund C2). */
    private var nightJob: Job? = null

    private val local = MutableStateFlow(Local())
    private val allSeries = seriesBoundary.series().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Juengstes Foto der gewaehlten Serie als Overlay, neu geladen nur wenn es sich aendert. */
    private val overlay = combine(local.map { it.selectedSeriesId }.distinctUntilChanged(), allSeries) { id, list ->
        list.firstOrNull { it.id == id }?.latestUri
    }.distinctUntilChanged().flatMapLatest { uri ->
        if (uri == null) flowOf(null) else flowOf(uri).map { u ->
            (processing.loadPreview(u, OVERLAY_PX)?.token as? Bitmap)?.asImageBitmap()
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val pro = combine(manual.manualCapabilities, manual.manualState) { c, m -> toPro(c, m) }

    val uiState: StateFlow<CameraUiState> = combine(camera.state, local, allSeries, overlay, pro) { s, l, series, ov, p -> toUi(s, l, series, ov).copy(pro = p) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, CameraUiState())

    /** Regler 0..1 logarithmisch auf die Belichtungszeit, linear auf ISO und Fokus. */
    private fun toPro(c: ManualCapabilitiesSnapshot?, m: ManualStateSnapshot): ProUi {
        if (c == null) return ProUi()
        val e = c.exposureRangeNanos; val i = c.isoRange
        val exp = m.exposureNanos; val iso = m.iso; val focus = m.focusDiopters
        return ProUi(
            canExpose = c.canExpose, canFocus = c.canFocus, canRaw = c.raw,
            exposure = if (e != null && exp != null) ProScale.toSlider(exp, e) else null,
            iso = if (i != null && iso != null) (iso - i.first).toFloat() / maxOf(1, i.last - i.first) else null,
            focus = c.maxFocusDiopters?.let { max -> focus?.let { 1f - it / max } },
            raw = m.raw,
            exposureLabel = exp?.let { ProScale.exposureText(it) } ?: "Auto",
            isoLabel = iso?.let { "ISO $it" } ?: "Auto",
        )
    }

    fun onExposure(slider: Float?) {
        val c = manual.manualCapabilities.value ?: return
        viewModelScope.launch {
            if (slider == null) { manual.setExposure(null, null); return@launch }
            val e = c.exposureRangeNanos ?: return@launch; val i = c.isoRange ?: return@launch
            val iso = manual.manualState.value.iso ?: i.first.coerceAtLeast(100).coerceAtMost(i.last)
            manual.setExposure(ProScale.fromSlider(slider, e), iso)
        }
    }

    fun onIso(slider: Float) {
        val c = manual.manualCapabilities.value ?: return
        val i = c.isoRange ?: return; val e = c.exposureRangeNanos ?: return
        viewModelScope.launch {
            val exp = manual.manualState.value.exposureNanos ?: ProScale.fromSlider(0.5f, e)
            manual.setExposure(exp, (i.first + slider.coerceIn(0f, 1f) * (i.last - i.first)).toInt())
        }
    }

    fun onFocus(slider: Float?) {
        val max = manual.manualCapabilities.value?.maxFocusDiopters ?: return
        viewModelScope.launch { manual.setFocus(slider?.let { (1f - it.coerceIn(0f, 1f)) * max }) }
    }

    /** Schnellwahl 0,6x, 1x, 3x. */
    fun onZoomPreset(ratio: Float) { viewModelScope.launch { camera.setZoom(ratio) } }

    /** Zwei-Finger-Zoom: [scale] ist der Spreizfaktor seit dem letzten Ereignis; die Kamera begrenzt. */
    fun onPinch(scale: Float) {
        if (!scale.isFinite() || scale <= 0f) return
        viewModelScope.launch { camera.setZoom(camera.state.value.zoom.ratio * scale) }
    }

    /** Antippen im Sucher: [x], [y] normiert auf das Kamerabild (0..1). */
    fun onTapFocus(x: Float, y: Float) { viewModelScope.launch { camera.focusAt(x, y) } }

    /** Lautstaerketaste: loest aus wie der runde Knopf, aber nur wenn gerade ausgeloest werden darf. */
    /**
     * Lautstaerketaste. true = Taste verbraucht. Laeuft die Kamera nicht (Fehler, keine Erlaubnis),
     * regelt die Taste wie gewohnt die Lautstaerke (Befund L1).
     */
    fun onHardwareShutter(): Boolean {
        val s = uiState.value
        if (s.status != ScreenStatus.RUNNING) return false
        if (s.canShoot) onShutter()
        return true
    }

    fun onRaw(enabled: Boolean) { viewModelScope.launch { manual.setRaw(enabled) } }

    fun onPermissionResult(granted: Boolean) {
        local.update { it.copy(permission = if (granted) PermissionStatus.GRANTED else PermissionStatus.DENIED) }
        if (granted && local.value.visible) viewModelScope.launch { syncAuto(); camera.start() }
    }

    fun onScreenStart() {
        local.update { it.copy(visible = true) }
        if (local.value.permission == PermissionStatus.GRANTED) viewModelScope.launch { syncAuto(); camera.start() }
    }

    /** Befund M8: die Automatik braucht den normalen Modus, auch wenn der Adapter noch einen anderen haelt. */
    private suspend fun syncAuto() { if (local.value.auto) camera.selectMode(PhotoMode.NORMAL) }

    fun onScreenStop() {
        disarm()
        nightJob?.cancel(); nightJob = null
        local.update { it.copy(visible = false, specialStatus = if (it.specialStatus == SpecialStatus.ARMED) SpecialStatus.IDLE else it.specialStatus) }
        viewModelScope.launch { camera.stop() }
    }

    fun onModeSelected(mode: ModeOption) {
        val auto = mode == ModeOption.AUTO
        local.update { it.copy(auto = auto) }
        // Die Automatik fotografiert im normalen Modus; nur so misst die Kamera laufend das Licht
        viewModelScope.launch { camera.selectMode(if (auto) PhotoMode.NORMAL else PhotoMode.valueOf(mode.name)) }
    }

    /** Naechster Look in fester Reihenfolge. */
    fun onNextLook() = local.update { it.copy(look = LookOption.entries[(it.look.ordinal + 1) % LookOption.entries.size]) }

    fun onSeriesSelected(id: Long?) = local.update { it.copy(selectedSeriesId = id) }

    fun onOverlayAlpha(alpha: Float) = local.update { it.copy(overlayAlpha = alpha.coerceIn(0f, 0.9f)) }

    fun onCreateSeries(name: String) {
        viewModelScope.launch {
            when (val r = seriesBoundary.create(name)) {
                is SeriesResult.Ok -> post(MessageKind.SERIES_CREATED) { it.copy(selectedSeriesId = r.series.id) }
                is SeriesResult.Failed -> post(MessageKind.SERIES_INVALID)
            }
        }
    }

    fun onShutter() {
        val l = local.value
        // Befund H2: waehrend eine Serie laeuft, loest ein weiterer Druck nichts aus (synchron geprueft, nicht ueber den UI-Zustand)
        if (l.specialStatus == SpecialStatus.COLLECTING || l.specialStatus == SpecialStatus.PROCESSING) return
        if (l.special != SpecialOption.NONE && l.special != SpecialOption.PRO) return onSpecialShutter(l.special)
        // "Nacht" im Dunkeln: eigener Nacht-Kern statt Samsungs schwacher Night-Extension
        if (l.special == SpecialOption.NONE && camera.state.value.requestedMode == PhotoMode.NIGHT && night.shouldUseOwn()) return onNightShutter()
        // Automatik: nur bei gemessener Dunkelheit (im normalen Modus misst die Kamera laufend)
        if (l.special == SpecialOption.NONE && l.auto && night.isDark()) return onNightShutter()
        viewModelScope.launch {
            when (val r = capture(Look.valueOf(l.look.name), l.selectedSeriesId)) {
                is CaptureOutcome.Saved -> {
                    val kind = when {
                        r.rawUri != null -> MessageKind.SAVED_WITH_RAW
                        r.lookFailed -> MessageKind.SAVED_WITHOUT_LOOK
                        r.seriesFailed -> MessageKind.SAVED_WITHOUT_SERIES
                        else -> MessageKind.SAVED
                    }
                    post(kind) { it.copy(lastPhotoUri = r.uri) }
                }
                is CaptureOutcome.Failed -> when (r.reason) {
                    CaptureFailure.BUSY -> Unit
                    CaptureFailure.STORAGE -> post(MessageKind.FAILED_STORAGE)
                    CaptureFailure.NOT_READY, CaptureFailure.CAMERA_CLOSED -> post(MessageKind.FAILED_CAMERA)
                    CaptureFailure.UNKNOWN -> post(MessageKind.FAILED_OTHER)
                }
            }
        }
    }

    private fun onNightShutter() {
        if (nightJob?.isActive == true) return // Befund H2: nie zwei Nachtaufnahmen zugleich
        local.update { it.copy(specialStatus = SpecialStatus.COLLECTING) }
        nightJob = viewModelScope.launch {
            val r = try { night() } finally { local.update { it.copy(specialStatus = SpecialStatus.IDLE) } }
            when (r) {
                is StackOutcome.Saved -> post(MessageKind.NIGHT_SAVED, r.info) { it.copy(lastPhotoUri = r.uri) }
                is StackOutcome.Failed -> post(MessageKind.NIGHT_FAILED, r.detail)
            }
        }
    }

    /** Naechste Spezialaufnahme; ein scharfer Ausloeser wird dabei entschaerft. */
    fun onNextSpecial() {
        disarm()
        local.update { it.copy(special = SpecialOption.entries[(it.special.ordinal + 1) % SpecialOption.entries.size], specialStatus = SpecialStatus.IDLE) }
    }

    private fun disarm() { triggerJob?.cancel(); triggerJob = null }

    /** Ausloeser im Spezialmodus: Serie stapeln oder Ausloeser scharf schalten bzw. entschaerfen. */
    private fun onSpecialShutter(special: SpecialOption) {
        when (special) {
            SpecialOption.CLEAN_PLATE, SpecialOption.LONG_EXPOSURE -> viewModelScope.launch {
                local.update { it.copy(specialStatus = SpecialStatus.COLLECTING) }
                val mode = if (special == SpecialOption.CLEAN_PLATE) StackMode.MEDIAN else StackMode.MEAN
                val r = stackPhoto(mode)
                local.update { it.copy(specialStatus = SpecialStatus.IDLE) }
                when (r) {
                    is StackOutcome.Saved -> post(if (r.shortened) MessageKind.STACK_SHORTENED else MessageKind.STACK_SAVED) { it.copy(lastPhotoUri = r.uri) }
                    is StackOutcome.Failed -> post(MessageKind.STACK_FAILED)
                }
            }
            SpecialOption.TRIGGER_MOTION, SpecialOption.TRIGGER_STILL -> {
                if (triggerJob != null) { disarm(); local.update { it.copy(specialStatus = SpecialStatus.IDLE) }; return }
                local.update { it.copy(specialStatus = SpecialStatus.ARMED) }
                val mode = if (special == SpecialOption.TRIGGER_MOTION) TriggerMode.MOTION else TriggerMode.STILLNESS
                triggerJob = viewModelScope.launch {
                    frames.trigger(mode).collect {
                        val l = local.value
                        when (val r = capture(Look.valueOf(l.look.name), l.selectedSeriesId)) {
                            is CaptureOutcome.Saved -> post(MessageKind.TRIGGER_FIRED) { it.copy(lastPhotoUri = r.uri) }
                            is CaptureOutcome.Failed -> Unit
                        }
                    }
                }
            }
            SpecialOption.FOCUS_STACK, SpecialOption.ASTRO -> viewModelScope.launch {
                local.update { it.copy(specialStatus = SpecialStatus.COLLECTING) }
                val r = if (special == SpecialOption.FOCUS_STACK) focusStack() else astro()
                local.update { it.copy(specialStatus = SpecialStatus.IDLE) }
                when (r) {
                    is StackOutcome.Saved -> post(if (r.shortened) MessageKind.STACK_SHORTENED else MessageKind.STACK_SAVED) { it.copy(lastPhotoUri = r.uri) }
                    is StackOutcome.Failed -> post(when (r.detail) { "FIXED_FOCUS" -> MessageKind.FIXED_FOCUS; "NO_MANUAL_EXPOSURE" -> MessageKind.NO_MANUAL; else -> MessageKind.STACK_FAILED })
                }
            }
            SpecialOption.PRO, SpecialOption.NONE -> Unit
        }
    }

    override fun onCleared() { disarm() }

    fun onMessageShown(id: Long) {
        local.update { if (it.message?.id == id) it.copy(message = null) else it }
    }

    private fun post(kind: MessageKind, detail: String? = null, extra: (Local) -> Local = { it }) {
        local.update { l -> extra(l).copy(message = UserMessage(l.nextMessageId, kind, detail), nextMessageId = l.nextMessageId + 1) }
    }

    private fun toUi(s: CameraStateSnapshot, l: Local, series: List<SeriesSnapshot>, ov: androidx.compose.ui.graphics.ImageBitmap?) = CameraUiState(
        permission = l.permission,
        status = when (s.status) {
            CameraStatus.IDLE -> ScreenStatus.IDLE
            CameraStatus.STARTING -> ScreenStatus.STARTING
            CameraStatus.RUNNING -> ScreenStatus.RUNNING
            CameraStatus.ERROR -> ScreenStatus.ERROR
        },
        modes = listOf(ModeOption.AUTO) + s.offeredModes.filter { it != PhotoMode.AUTO }.map { ModeOption.valueOf(it.name) },
        selected = if (l.auto) ModeOption.AUTO else ModeOption.valueOf(s.requestedMode.name),
        autoNight = l.auto && l.special == SpecialOption.NONE && NightPlan.isDark(s.light?.exposureNs, s.light?.iso) == true,
        active = ModeOption.valueOf(s.activeMode.name),
        fallbackFrom = s.fallbackFrom?.let { ModeOption.valueOf(it.name) },
        previewToken = s.preview?.token,
        capturing = s.capturing,
        lastPhotoUri = l.lastPhotoUri,
        message = l.message,
        look = l.look,
        series = series.map { SeriesOption(it.id, it.name, it.photoUris.size) },
        selectedSeriesId = l.selectedSeriesId?.takeIf { id -> series.any { it.id == id } },
        overlay = ov,
        overlayAlpha = l.overlayAlpha,
        special = l.special,
        specialStatus = l.specialStatus,
        zoomRatio = s.zoom.ratio,
        zoomPresets = s.zoom.presets.map { r ->
            // Hervorheben nur, wenn der Zoom nahe an der Stufe liegt (wie bei Samsung)
            ZoomPresetUi(r, zoomLabel(r), kotlin.math.abs(r - s.zoom.ratio) < 0.05f)
        },
    )

    companion object { const val OVERLAY_PX = 1440 }
}
