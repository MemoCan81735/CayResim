package app.cayresim.core.camera

import android.content.ContentValues
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import app.cayresim.core.boundary.ManualCameraBoundary
import app.cayresim.core.boundary.ManualCapabilitiesSnapshot
import app.cayresim.core.boundary.ManualStateSnapshot
import app.cayresim.core.boundary.CfaLayout
import app.cayresim.core.boundary.RawProbe
import app.cayresim.core.boundary.RawProbeFailure
import app.cayresim.core.boundary.RawProbeResult
import app.cayresim.core.entity.ManualLimits
import app.cayresim.core.entity.ManualSettingsEntity
import android.os.PowerManager
import android.os.SystemClock
import android.util.Size
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import app.cayresim.core.boundary.BurstFailure
import app.cayresim.core.boundary.BurstResult
import app.cayresim.core.boundary.FrameBoundary
import app.cayresim.core.boundary.FrameBurst
import app.cayresim.core.boundary.TriggerMode
import app.cayresim.core.entity.ThermalBudgetEntity
import app.cayresim.core.entity.TriggerEntity
import app.cayresim.core.entity.TriggerKind
import app.cayresim.core.pure.Stacking
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.SessionConfig
import androidx.camera.core.SurfaceRequest
import android.view.Surface
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionSessionConfig
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.core.content.ContextCompat
import app.cayresim.core.boundary.CameraBoundary
import app.cayresim.core.boundary.CameraCapabilitiesSnapshot
import app.cayresim.core.boundary.CameraDispatcher
import app.cayresim.core.boundary.CameraError
import app.cayresim.core.boundary.CameraStateSnapshot
import app.cayresim.core.boundary.ZoomSnapshot
import app.cayresim.core.boundary.DeviceReport
import app.cayresim.core.boundary.HardwareLevel
import app.cayresim.core.boundary.LightSnapshot
import app.cayresim.core.boundary.OisState
import app.cayresim.core.boundary.Frame
import app.cayresim.core.entity.ZoomEntity
import app.cayresim.core.boundary.CameraStatus
import app.cayresim.core.boundary.CaptureFailure
import app.cayresim.core.boundary.CaptureResult
import app.cayresim.core.boundary.PhotoMode
import app.cayresim.core.boundary.PreviewHandle
import app.cayresim.core.entity.ModeKey
import app.cayresim.core.entity.ModeSelectionEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * CameraX-Adapter: einziger Ort mit CameraX (R11). Jeder Modus ist genau eine SessionConfig (R12),
 * jeder Ausfall faellt auf NORMAL zurueck (R14), Fehler werden zu Werten (R24).
 * Laeuft auf dem CameraDispatcher (Main, weil CameraX auf dem Main-Thread bindet).
 */
@Singleton
class CameraXCameraAdapter @Inject constructor(
    @ApplicationContext private val context: Context,
    @CameraDispatcher private val dispatcher: CoroutineDispatcher,
) : CameraBoundary, FrameBoundary, ManualCameraBoundary {

    private val owner = AdapterLifecycleOwner()
    private val selection = ModeSelectionEntity()
    private val mutex = Mutex()
    private val _caps = MutableStateFlow<CameraCapabilitiesSnapshot?>(null)
    private val _state = MutableStateFlow(CameraStateSnapshot())
    override val capabilities: StateFlow<CameraCapabilitiesSnapshot?> = _caps
    override val state: StateFlow<CameraStateSnapshot> = _state

    private var provider: ProcessCameraProvider? = null
    private var extensions: ExtensionsManager? = null
    private var imageCapture: ImageCapture? = null
    private var pendingRequest: SurfaceRequest? = null
    private var bindGeneration = 0
    /** Belichtung waehrend einer Serie festgehalten (Menschen wegrechnen, Langzeit, Sterne). */
    private var aeLocked = false
    /** Letzter Zustand der Belichtungsautomatik (vom Kamera-Thread geschrieben). */
    @Volatile private var aeState: Int? = null
    /** S-003: optischer Stabilisator angeboten; dann fordert die App ihn an (eigene Pipeline, Pro, RAW-Sitzung). */
    @Volatile private var oisAvailable = false
    /** Fuer Tests: wie viele Stroeme gerade die eigene Pipeline brauchen (muss nach jedem Ende 0 sein). */
    @androidx.annotation.VisibleForTesting internal val pipelineUserCount: Int get() = pipelineUsers
    /** Ob die aktuelle Bindung Ultra HDR speichert (fuer Tests und Diagnose). */
    @androidx.annotation.VisibleForTesting internal var ultraHdrActive = false
    /** Wie oft die Selbstheilung noetig war; Tests verlangen 0 im normalen Ablauf. */
    @androidx.annotation.VisibleForTesting internal var selfHealCount = 0
    /** Unsichtbarer Sucher-Ersatz mit eigenem OpenGL-Abnehmer, siehe [FallbackPreviewSink]. */
    private var fallbackSink: FallbackPreviewSink? = null
    private val selector = CameraSelector.DEFAULT_BACK_CAMERA

    // ---------- Eigene Pipeline (Phase 3) ----------
    private val thermal = ThermalBudgetEntity()
    private var pipelineUsers = 0
    private val analysisExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "cayresim-analysis") }
    private val frameListeners = CopyOnWriteArrayList<(ByteArray, Int, Int, Int) -> Unit>()
    /** Wiederverwendeter Puffer des Analyse-Threads (R19): keine Allokation pro Frame. */
    private var analysisBuffer = ByteArray(0)

    // ---------- Manuelle Kamera (Phase 4) ----------
    private var boundCamera: Camera? = null
    private val _manualCaps = MutableStateFlow<ManualCapabilitiesSnapshot?>(null)
    private val _manualState = MutableStateFlow(ManualStateSnapshot())
    override val manualCapabilities: StateFlow<ManualCapabilitiesSnapshot?> = _manualCaps
    override val manualState: StateFlow<ManualStateSnapshot> = _manualState
    private var manualEntity: ManualSettingsEntity? = null

    override suspend fun start() = withContext(dispatcher) {
        mutex.withLock {
            _state.update { it.copy(status = CameraStatus.STARTING, error = null) }
            try {
                val p = provider ?: ProcessCameraProvider.awaitInstance(context).also { provider = it }
                if (!p.hasCamera(selector)) {
                    _state.update { it.copy(status = CameraStatus.ERROR, error = CameraError.NO_CAMERA) }
                    return@withLock
                }
                val em = extensions ?: ExtensionsManager.getInstance(context, p).also { extensions = it }
                if (_caps.value == null) _caps.value = readCapabilities(p, em)
                selection.resetFailures()
                owner.resume()
                bindCurrent(p, em)
            } catch (e: Exception) {
                owner.pause()
                _state.update { it.copy(status = CameraStatus.ERROR, error = CameraError.UNKNOWN, preview = null) }
            }
        }
    }

    override suspend fun stop() = withContext(dispatcher) {
        mutex.withLock {
            provider?.unbindAll()
            bindGeneration++
            owner.pause()
            pendingRequest = null
            // Den Sucher-Ersatz gibt CameraX ueber den Ergebnis-Rueckruf frei, sobald die Sitzung ihn losgelassen hat
            fallbackSink = null
            imageCapture = null
            // Befund H4: alte Lichtmessung gilt nach dem Stopp nicht mehr (z. B. Wiederoeffnen bei Tageslicht)
            _state.update { it.copy(status = CameraStatus.IDLE, preview = null, capturing = false, light = null, stabilization = null) }
        }
    }

    override suspend fun selectMode(mode: PhotoMode) = withContext(dispatcher) {
        mutex.withLock {
            selection.request(mode.toKey())
            val p = provider; val em = extensions
            if (owner.isActive && p != null && em != null) bindCurrent(p, em) else publishMode()
        }
    }

    private fun readCapabilities(p: ProcessCameraProvider, em: ExtensionsManager): CameraCapabilitiesSnapshot {
        val available = ModeKey.entries.filter {
            it != ModeKey.NORMAL && runCatching { em.isExtensionAvailable(selector, it.toExtensionMode()) }.getOrDefault(false)
        }.toSet()
        selection.updateAvailable(available)
        val info = p.getCameraInfo(selector)
        val formats = runCatching { ImageCapture.getImageCaptureCapabilities(info).supportedOutputFormats }.getOrDefault(emptySet())
        _manualCaps.value = readManualCapabilities(info, ImageCapture.OUTPUT_FORMAT_RAW_JPEG in formats)
        manualEntity = _manualCaps.value?.let { c -> ManualSettingsEntity(ManualLimits(c.exposureRangeNanos, c.isoRange, c.maxFocusDiopters)) }
        return CameraCapabilitiesSnapshot(
            modes = selection.offeredModes.map { it.toPhotoMode() },
            lowLightBoost = runCatching { info.isLowLightBoostSupported }.getOrDefault(false),
            ultraHdr = ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR in formats,
            raw = ImageCapture.OUTPUT_FORMAT_RAW in formats,
            device = readDeviceReport(info).also { oisAvailable = it.ois == true },
        )
    }

    /** Bindet den wirksamen Modus; scheitert eine Extension, wird sie markiert und NORMAL gebunden (R14). */
    private fun bindCurrent(p: ProcessCameraProvider, em: ExtensionsManager) {
        val wanted = selection.effectiveMode
        if (!tryBind(p, em, wanted)) {
            selection.markFailed(wanted)
            if (wanted == ModeKey.NORMAL || !tryBind(p, em, ModeKey.NORMAL)) {
                owner.pause()
                _state.update { it.copy(status = CameraStatus.ERROR, error = CameraError.BIND_FAILED, preview = null) }
                return
            }
        }
        _state.update { it.copy(status = CameraStatus.RUNNING, error = null) }
        publishMode()
        restoreZoom()
    }

    // ---------- Zoom und Fokus ----------

    /** Wunsch-Zoom des Nutzers; CameraX setzt den Zoom bei jedem Neubinden auf 1x zurueck. */
    private var wantedZoom = 1f
    private var currentPreview: Preview? = null

    private fun zoomEntity(): ZoomEntity? {
        val zs = boundCamera?.cameraInfo?.zoomState?.value ?: return null
        return ZoomEntity(zs.minZoomRatio, zs.maxZoomRatio)
    }

    private fun restoreZoom() {
        val z = zoomEntity() ?: return
        val ratio = z.clamp(wantedZoom)
        runCatching { boundCamera?.cameraControl?.setZoomRatio(ratio) }
        _state.update { it.copy(zoom = ZoomSnapshot(ratio, z.min, z.max, z.presets)) }
    }

    override suspend fun setZoom(ratio: Float): Boolean = withContext(dispatcher) {
        if (_state.value.status != CameraStatus.RUNNING || !ratio.isFinite()) return@withContext false
        val z = zoomEntity() ?: return@withContext false
        val target = z.clamp(ratio)
        wantedZoom = target
        // Sofort im Zustand, damit die Anzeige der Geste folgt; die Kamera zieht nach
        _state.update { it.copy(zoom = ZoomSnapshot(target, z.min, z.max, z.presets)) }
        runCatching { boundCamera?.cameraControl?.setZoomRatio(target) }.isSuccess
    }

    override suspend fun focusAt(x: Float, y: Float): Boolean = withContext(dispatcher) {
        if (_state.value.status != CameraStatus.RUNNING || x !in 0f..1f || y !in 0f..1f) return@withContext false
        val cam = boundCamera ?: return@withContext false
        val preview = currentPreview ?: return@withContext false
        runCatching {
            val point = androidx.camera.core.SurfaceOrientedMeteringPointFactory(1f, 1f, preview).createPoint(x, y)
            val action = androidx.camera.core.FocusMeteringAction.Builder(point,
                androidx.camera.core.FocusMeteringAction.FLAG_AF or androidx.camera.core.FocusMeteringAction.FLAG_AE)
                .setAutoCancelDuration(FOCUS_HOLD_S, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            withTimeoutOrNull(FOCUS_TIMEOUT_MS) { cam.cameraControl.startFocusAndMetering(action).await().isFocusSuccessful }
        }.getOrNull() == true
    }

    private fun tryBind(p: ProcessCameraProvider, em: ExtensionsManager, key: ModeKey, allowUltraHdr: Boolean = true): Boolean {
        var ultraTried = false
        return try {
        p.unbindAll()
        val ext = if (pipelineUsers > 0) ExtensionMode.NONE else key.toExtensionMode()
        // S-003: Samsungs Modi melden keine Aufnahmeergebnisse; ein alter Wert waere irrefuehrend
        if (ext != ExtensionMode.NONE) _state.update { it.copy(stabilization = null) }
        // Belichtungsautomatik mitlesen (nur ohne Extension; Extensions erlauben keine eigenen Rueckrufe)
        val preview = Preview.Builder().apply {
            if (ext == ExtensionMode.NONE) androidx.camera.camera2.interop.Camera2Interop.Extender(this).setSessionCaptureCallback(lightMeter)
        }.build()
        val probe = rawProbeActive && pipelineUsers == 0 && ext == ExtensionMode.NONE
        val rawWanted = !probe && _manualState.value.raw && pipelineUsers == 0 && key == ModeKey.NORMAL
        // Ultra HDR (JPEG mit Gain Map): hellere Lichter auf HDR-Bildschirmen. Nur im normalen Modus ohne
        // eigene Pipeline; Extensions behalten ihr JPEG, damit kein Modus deswegen ausfaellt.
        val ultraHdr = allowUltraHdr && !probe && !rawWanted && pipelineUsers == 0 && ext == ExtensionMode.NONE && _caps.value?.ultraHdr == true
        ultraTried = ultraHdr
        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .apply {
                if (probe) setOutputFormat(ImageCapture.OUTPUT_FORMAT_RAW)
                else if (rawWanted) setOutputFormat(ImageCapture.OUTPUT_FORMAT_RAW_JPEG)
                else if (ultraHdr) setOutputFormat(ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR)
            }
            .build()
        // Alte Anfrage verwerfen: sie gehoert zur vorigen Bindung und wird nie mehr bedient.
        // Jede Bindung bekommt eine Nummer; spaet eintreffende Anfragen frueherer Bindungen werden ignoriert.
        pendingRequest = null
        val generation = ++bindGeneration
        preview.setSurfaceProvider { request ->
            if (generation != bindGeneration) { request.willNotProvideSurface(); return@setSurfaceProvider }
            request.addRequestCancellationListener(ContextCompat.getMainExecutor(context)) {
                if (pendingRequest === request) pendingRequest = null
            }
            pendingRequest = request
            _state.update { it.copy(preview = PreviewHandle(request)) }
        }
        val useCases = mutableListOf(preview, capture)
        if (pipelineUsers > 0) useCases += buildAnalysis()
        val config: SessionConfig =
            if (ext == ExtensionMode.NONE) SessionConfig(useCases)
            else ExtensionSessionConfig(ext, em, useCases)
        if (ext == ExtensionMode.NONE && !p.getCameraInfo(selector).isSessionConfigSupported(config)) {
            // Ultra HDR passt nicht in diese Kombination: ohne erneut versuchen statt den Modus zu verlieren
            if (ultraHdr) tryBind(p, em, key, allowUltraHdr = false) else false
        } else {
            boundCamera = p.bindToLifecycle(owner, selector, config)
            imageCapture = capture
            currentPreview = preview
            ultraHdrActive = ultraHdr
            if (ext == ExtensionMode.NONE) applyManualOptions()
            true
        }
    } catch (e: Exception) {
        imageCapture = null
        // Befund M3: scheitert das Binden mit Ultra HDR, erst ohne Ultra HDR versuchen, bevor der Modus als defekt gilt
        if (ultraTried) tryBind(p, em, key, allowUltraHdr = false) else false
    }
    }

    private fun buildAnalysis(): ImageAnalysis {
        val analysis = ImageAnalysis.Builder()
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy(ANALYSIS_SIZE, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                    .build(),
            ).build()
        analysis.setAnalyzer(analysisExecutor) { image -> image.use { onFrame(it) } }
        return analysis
    }

    /** RGBA der Analyse in den wiederverwendeten RGB-Puffer, dann an alle Zuhoerer. */
    private fun onFrame(image: ImageProxy) {
        if (frameListeners.isEmpty()) return
        val w = image.width; val h = image.height
        val plane = image.planes[0]; val buf = plane.buffer; val row = plane.rowStride; val pix = plane.pixelStride
        if (analysisBuffer.size != w * h * 3) analysisBuffer = ByteArray(w * h * 3)
        val out = analysisBuffer
        var o = 0
        for (y in 0 until h) {
            var i = y * row
            for (x in 0 until w) {
                out[o] = buf.get(i); out[o + 1] = buf.get(i + 1); out[o + 2] = buf.get(i + 2)
                o += 3; i += pix
            }
        }
        val rot = image.imageInfo.rotationDegrees
        frameListeners.forEach { it(out, w, h, rot) }
    }

    /**
     * Nicht abbrechbar, und [onAcquired] meldet die Erhoehung noch im Lock (Befund H1): withContext wirft nach dem
     * Ende trotzdem, wenn der Aufrufer inzwischen abgebrochen wurde. Deshalb steht der Aufruf im try und das
     * finally gibt nur frei, was wirklich belegt wurde.
     */
    private suspend fun acquirePipeline(onAcquired: () -> Unit) = withContext(NonCancellable + dispatcher) {
        mutex.withLock {
            pipelineUsers++
            onAcquired()
            if (pipelineUsers == 1) { val p = provider; val em = extensions; if (owner.isActive && p != null && em != null) bindCurrent(p, em) }
        }
    }

    private suspend fun releasePipeline() = withContext(NonCancellable + dispatcher) {
        mutex.withLock {
            pipelineUsers = (pipelineUsers - 1).coerceAtLeast(0)
            if (pipelineUsers == 0) { val p = provider; val em = extensions; if (owner.isActive && p != null && em != null) bindCurrent(p, em) }
        }
    }

    private fun headroom(): Float? = runCatching {
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager).getThermalHeadroom(10).takeUnless { it.isNaN() }
    }.getOrNull()

    override suspend fun collect(count: Int): BurstResult {
        if (_state.value.status != CameraStatus.RUNNING) return BurstResult.Failed(BurstFailure.NOT_READY)
        val n = thermal.framesFor(count, headroom())
        var acquired = false
        val received = Channel<Pair<ByteArray, IntArray>>(Channel.UNLIMITED)
        var taken = 0
        val listener: (ByteArray, Int, Int, Int) -> Unit = { bytes, w, h, rot ->
            // Kopie nur fuer die angeforderten Bilder; jede Kopie wird Teil der Serie
            if (taken < n) { taken++; received.trySend(bytes.copyOf() to intArrayOf(w, h, rot)) }
        }
        return try {
            acquirePipeline { acquired = true }
            withContext(dispatcher) {
                ensureSurface()
                // Befund M2: erst festhalten, wenn die Automatik eingeschwungen ist (hoechstens 1,5 s warten)
                aeState = null
                delay(AE_SETTLE_MS)
                withTimeoutOrNull(AE_CONVERGE_TIMEOUT_MS) {
                    while (aeState != CaptureRequest.CONTROL_AE_STATE_CONVERGED && aeState != CaptureRequest.CONTROL_AE_STATE_FLASH_REQUIRED) delay(50)
                }
                aeLocked = true; applyManualOptions()
            }
            frameListeners += listener
            val frames = withTimeoutOrNull(5_000L + n * 400L) { List(n) { received.receive() } }
                ?: return BurstResult.Failed(BurstFailure.TIMEOUT)
            val (w, h, rot) = frames.first().second.let { Triple(it[0], it[1], it[2]) }
            BurstResult.Ok(FrameBurst(w, h, frames.filter { it.second[0] == w && it.second[1] == h }.map { it.first }, rot), count)
        } finally {
            frameListeners -= listener
            received.close()
            // Ein Block fuer alles: ein zweites withContext koennte nach Abbruch werfen und die Freigabe ueberspringen
            withContext(NonCancellable + dispatcher) {
                aeLocked = false; applyManualOptions()
                if (acquired) releasePipeline()
            }
        }
    }

    /** Liest Belichtungszeit und ISO aus jedem 10. Kamerabild; Grundlage fuer "ist es dunkel?" (Nacht-Kern). */
    private val lightMeter = object : android.hardware.camera2.CameraCaptureSession.CaptureCallback() {
        private var n = 0
        override fun onCaptureCompleted(
            session: android.hardware.camera2.CameraCaptureSession,
            request: CaptureRequest,
            result: android.hardware.camera2.TotalCaptureResult,
        ) {
            val ae = result.get(android.hardware.camera2.CaptureResult.CONTROL_AE_STATE)
            aeState = ae
            // S-003: ob der Stabilisator laeuft, sagt nur das Aufnahmeergebnis
            // S-007: fehlt der Wert im Ergebnis, ist das ein eigener Befund (Selbsttest S24+ 10.10.: "unbekannt")
            val ois = oisStateOf(result.get(android.hardware.camera2.CaptureResult.LENS_OPTICAL_STABILIZATION_MODE)
                ?.let { it == CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON }, _state.value.stabilization)
            if (_state.value.stabilization != ois) _state.update { it.copy(stabilization = ois) }
            // Befund H4: nur echte Messungen der Automatik zaehlen, keine manuellen oder festgehaltenen Werte
            if (result.get(android.hardware.camera2.CaptureResult.CONTROL_AE_MODE) == CaptureRequest.CONTROL_AE_MODE_OFF) return
            if (result.get(android.hardware.camera2.CaptureResult.CONTROL_AE_LOCK) == true) return
            if (ae != null && ae != CaptureRequest.CONTROL_AE_STATE_CONVERGED && ae != CaptureRequest.CONTROL_AE_STATE_FLASH_REQUIRED) return
            if (_state.value.light != null && n++ % 10 != 0) return
            val exp = result.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME) ?: return
            val iso = result.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY) ?: return
            val l = LightSnapshot(exp, iso)
            if (_state.value.light != l) _state.update { it.copy(light = l) }
        }
    }

    override fun frames(maxCount: Int): Flow<Frame> = kotlinx.coroutines.flow.flow {
        // Je Sammlung ein eigener Zaehler der Bilder in der Warteschlange (Befund M7)
        val inFlight = java.util.concurrent.atomic.AtomicInteger(0)
        emitAll(frameStream(maxCount, inFlight).buffer(STREAM_BUFFER).onEach { inFlight.decrementAndGet() })
    }

    private fun frameStream(maxCount: Int, inFlight: java.util.concurrent.atomic.AtomicInteger): Flow<Frame> = callbackFlow {
        if (_state.value.status != CameraStatus.RUNNING || maxCount <= 0) { close(); return@callbackFlow }
        // Gefunden im Nachttest auf dem S24+: framesFor begrenzte auf 30, die Nachtserie bekam 23 statt 36 Bilder
        val n = thermal.streamFramesFor(maxCount, headroom())
        val sent = java.util.concurrent.atomic.AtomicInteger(0)
        val lastFrame = java.util.concurrent.atomic.AtomicLong(SystemClock.elapsedRealtime())
        val listener: (ByteArray, Int, Int, Int) -> Unit = { bytes, w, h, rot ->
            lastFrame.set(SystemClock.elapsedRealtime())
            // Nur kopieren, wenn Platz ist (Befund M7): sonst Bild auslassen statt Speicher zu verschwenden
            if (sent.get() < n && inFlight.get() < STREAM_BUFFER) {
                inFlight.incrementAndGet()
                if (trySend(Frame(w, h, bytes.copyOf(), rot)).isSuccess) sent.incrementAndGet() else inFlight.decrementAndGet()
            }
            if (sent.get() >= n) channel.close()
        }
        val watchers = mutableListOf<kotlinx.coroutines.Job>()
        var acquired = false
        try {
            acquirePipeline { acquired = true }
            withContext(dispatcher) { ensureSurface() }
            frameListeners += listener
            // Befund C2: Strom endet, wenn die Kamera stoppt oder zu lange kein Bild kommt
            watchers += launch { _state.first { it.status != CameraStatus.RUNNING }; channel.close() }
            watchers += launch {
                while (true) {
                    delay(STREAM_FRAME_TIMEOUT_MS / 2)
                    if (SystemClock.elapsedRealtime() - lastFrame.get() > STREAM_FRAME_TIMEOUT_MS) { channel.close(); break }
                }
            }
            awaitClose { frameListeners -= listener }
        } finally {
            // Waechter beenden, sonst wartet der Strom ewig auf seine Kinder
            watchers.forEach { it.cancel() }
            frameListeners -= listener
            if (acquired) releasePipeline()
        }
    }

    // ---------- RAW-Nachtweg (Ausnahme A3: eigene Camera2-Sitzung nur waehrend der Aufnahme) ----------

    override fun rawFrames(maxCount: Int, exposureNs: Long, iso: Int): Flow<app.cayresim.core.boundary.RawFrame> = kotlinx.coroutines.flow.flow {
        // Je Sammlung ein eigener Zaehler; hoechstens ein Rohbild (24 MB) unterwegs (R19)
        val inFlight = java.util.concurrent.atomic.AtomicInteger(0)
        emitAll(rawStream(maxCount, exposureNs, iso, inFlight).buffer(1).onEach { inFlight.decrementAndGet() })
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun rawStream(maxCount: Int, exposureNs: Long, iso: Int, inFlight: java.util.concurrent.atomic.AtomicInteger) =
        callbackFlow<app.cayresim.core.boundary.RawFrame> {
            if (_state.value.status != CameraStatus.RUNNING || maxCount <= 0 || _manualCaps.value?.raw != true) { close(); return@callbackFlow }
            val info = boundCamera?.cameraInfo
            val c2 = info?.let { runCatching { Camera2CameraInfo.from(it) }.getOrNull() }
            if (c2 == null) { close(); return@callbackFlow }
            fun <T> ch(k: CameraCharacteristics.Key<T>): T? = runCatching { c2.getCameraCharacteristic(k) }.getOrNull()
            val size = ch(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(android.graphics.ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width.toLong() * it.height }
            val pattern = ch(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
            val staticBlack = FloatArray(4) { i -> (pattern?.getOffsetForIndex(i % 2, i / 2) ?: 0).toFloat() }
            val white = (ch(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023).toFloat()
            val cfa = when (ch(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)) {
                CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB -> CfaLayout.RGGB
                CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG -> CfaLayout.GRBG
                CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG -> CfaLayout.GBRG
                CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR -> CfaLayout.BGGR
                else -> null
            }
            if (size == null || cfa == null) { close(); return@callbackFlow }
            val rotation = rawRotation(ch(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90)
            val n = thermal.streamFramesFor(maxCount, headroom())
            val cameraId = c2.cameraId
            val thread = android.os.HandlerThread("raw-night").apply { start() }
            val handler = android.os.Handler(thread.looper)
            val executor = java.util.concurrent.Executor { handler.post(it) }
            val reader = android.media.ImageReader.newInstance(size.width, size.height, android.graphics.ImageFormat.RAW_SENSOR, 3)
            var device: android.hardware.camera2.CameraDevice? = null
            var session: android.hardware.camera2.CameraCaptureSession? = null
            val watchers = mutableListOf<kotlinx.coroutines.Job>()
            // Sperre mit Merker im try (Befund H1): withContext kann nach dem Ende werfen, die Freigabe darf nicht fehlen
            var locked = false
            try {
                withContext(NonCancellable + dispatcher) { mutex.lock(); locked = true }
                withContext(dispatcher) { provider?.unbindAll(); boundCamera = null; imageCapture = null }
                device = openCameraWithin(context.getSystemService(android.hardware.camera2.CameraManager::class.java), cameraId, executor)
                val dev = device ?: run { close(); return@callbackFlow }
                // Kalibrierung der letzten Aufnahme: Weissabgleich, Farbmatrix, Schwarzwert
                val gains = java.util.concurrent.atomic.AtomicReference(floatArrayOf(1f, 1f, 1f))
                val matrix = java.util.concurrent.atomic.AtomicReference(app.cayresim.core.pure.RawDevelop.IDENTITY)
                val black = java.util.concurrent.atomic.AtomicReference(staticBlack)
                val sent = java.util.concurrent.atomic.AtomicInteger(0)
                val lastFrame = java.util.concurrent.atomic.AtomicLong(SystemClock.elapsedRealtime())
                reader.setOnImageAvailableListener({ r ->
                    val img = runCatching { r.acquireNextImage() }.getOrNull() ?: return@setOnImageAvailableListener
                    img.use {
                        lastFrame.set(SystemClock.elapsedRealtime())
                        if (sent.get() < n && inFlight.get() < 1) {
                            val plane = it.planes[0]
                            val stride = plane.rowStride / 2
                            val sb = plane.buffer.duplicate().order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            val data = ShortArray(minOf(sb.remaining(), stride * it.height)); sb.get(data)
                            val frame = app.cayresim.core.boundary.RawFrame(it.width, it.height, stride, data, cfa,
                                black.get().copyOf(), white, gains.get().copyOf(), matrix.get().copyOf(), rotation)
                            inFlight.incrementAndGet()
                            if (trySend(frame).isSuccess) sent.incrementAndGet() else inFlight.decrementAndGet()
                        }
                    }
                    if (sent.get() >= n) channel.close()
                }, handler)
                val s = createSession(dev, reader.surface, executor) ?: run { close(); return@callbackFlow }
                session = s
                val minFrame = runCatching {
                    ch(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputMinFrameDuration(android.graphics.ImageFormat.RAW_SENSOR, size)
                }.getOrNull() ?: 0L
                val req = dev.createCaptureRequest(android.hardware.camera2.CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(reader.surface)
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                    set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs)
                    set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                    set(CaptureRequest.SENSOR_FRAME_DURATION, maxOf(exposureNs, minFrame))
                    if (oisAvailable) set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
                }.build()
                s.setRepeatingRequest(req, object : android.hardware.camera2.CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: android.hardware.camera2.CameraCaptureSession,
                        request: CaptureRequest,
                        result: android.hardware.camera2.TotalCaptureResult,
                    ) {
                        result.get(android.hardware.camera2.CaptureResult.COLOR_CORRECTION_GAINS)?.let { g ->
                            gains.set(floatArrayOf(g.red, (g.greenEven + g.greenOdd) / 2f, g.blue))
                        }
                        result.get(android.hardware.camera2.CaptureResult.COLOR_CORRECTION_TRANSFORM)?.let { t ->
                            matrix.set(FloatArray(9) { i -> t.getElement(i % 3, i / 3).toFloat() })
                        }
                        result.get(android.hardware.camera2.CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)?.takeIf { it.size == 4 }?.let { black.set(it.copyOf()) }
                    }
                }, handler)
                // Strom endet, wenn zu lange kein Bild kommt (wie beim 8-Bit-Strom, Befund C2)
                watchers += launch {
                    while (true) {
                        delay(STREAM_FRAME_TIMEOUT_MS / 2)
                        if (SystemClock.elapsedRealtime() - lastFrame.get() > STREAM_FRAME_TIMEOUT_MS) { channel.close(); break }
                    }
                }
                awaitClose { }
            } finally {
                watchers.forEach { it.cancel() }
                withContext(NonCancellable + dispatcher) {
                    runCatching { session?.stopRepeating() }
                    runCatching { session?.close() }
                    runCatching { device?.close() }
                    runCatching { reader.close() }
                    thread.quitSafely()
                    // CameraX wieder binden; die Sperre gehoert noch uns, deshalb direkt
                    if (locked) {
                        val p = provider; val em = extensions
                        if (owner.isActive && p != null && em != null) runCatching { bindCurrent(p, em) }
                        mutex.unlock()
                    }
                }
            }
        }

    /** Drehung des Rohbilds fuer die Anzeige: Sensorlage minus Bildschirmdrehung (Rueckkamera). */
    private fun rawRotation(sensorOrientation: Int): Int {
        val display = runCatching { (context.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager).getDisplay(android.view.Display.DEFAULT_DISPLAY).rotation }.getOrNull() ?: Surface.ROTATION_0
        val deg = when (display) { Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0 }
        return (sensorOrientation - deg + 360) % 360
    }

    override fun trigger(mode: TriggerMode): Flow<Unit> = callbackFlow {
        val entity = TriggerEntity(if (mode == TriggerMode.MOTION) TriggerKind.MOTION else TriggerKind.STILLNESS)
        var previous = ByteArray(0)
        val listener: (ByteArray, Int, Int, Int) -> Unit = { bytes, w, h, _ ->
            if (previous.size == bytes.size) {
                val score = Stacking.motionScore(previous, bytes, w * h)
                if (entity.onScore(score, SystemClock.elapsedRealtime())) trySend(Unit)
                System.arraycopy(bytes, 0, previous, 0, bytes.size)
            } else previous = bytes.copyOf()
        }
        var acquired = false
        try {
            acquirePipeline { acquired = true }
            withContext(dispatcher) { ensureSurface() }
            frameListeners += listener
            awaitClose { frameListeners -= listener }
        } finally {
            frameListeners -= listener
            if (acquired) releasePipeline()
        }
    }.buffer(Channel.CONFLATED)

    private fun publishMode() {
        _state.update {
            it.copy(
                requestedMode = selection.requestedMode.toPhotoMode(),
                activeMode = if (pipelineUsers > 0) PhotoMode.NORMAL else selection.effectiveMode.toPhotoMode(),
                fallbackFrom = selection.fallbackFrom?.toPhotoMode(),
                offeredModes = selection.offeredModes.map { m -> m.toPhotoMode() },
            )
        }
        _caps.update { c -> c?.copy(modes = selection.offeredModes.map { it.toPhotoMode() }) }
    }

    override suspend fun capture(): CaptureResult = withContext(dispatcher) {
        if (imageCapture == null || _state.value.status != CameraStatus.RUNNING) return@withContext CaptureResult.Failed(CaptureFailure.NOT_READY)
        if (_state.value.capturing) return@withContext CaptureResult.Failed(CaptureFailure.BUSY)
        _state.update { it.copy(capturing = true) }
        try {
            ensureSurface()
            takeOnce() ?: run {
                // Selbstheilung: Sitzung haengt (z. B. Sucher-Flaeche beim Screenwechsel verloren). Neu binden, genau einmal wiederholen.
                val p = provider; val em = extensions
                if (p == null || em == null) return@run CaptureResult.Failed(CaptureFailure.CAMERA_CLOSED)
                selfHealCount++
                pendingRequest = null
                mutex.withLock { bindCurrent(p, em) }
                withTimeoutOrNull(3_000) { while (pendingRequest == null) delay(20) }
                ensureSurface()
                takeOnce() ?: CaptureResult.Failed(CaptureFailure.CAMERA_CLOSED)
            }
        } catch (e: Exception) {
            CaptureResult.Failed(CaptureFailure.UNKNOWN)
        } finally {
            _state.update { it.copy(capturing = false) }
        }
    }

    /** Eine Aufnahme mit Zeitgrenze; null heisst: keine Antwort der Kamera. */
    private suspend fun takeOnce(): CaptureResult? {
        val ic = imageCapture ?: return CaptureResult.Failed(CaptureFailure.NOT_READY)
        val name = "CAY_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.ROOT).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, PHOTO_DIR)
        }
        val options = ImageCapture.OutputFileOptions.Builder(
            context.contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values,
        ).build()
        if (ic.outputFormat == ImageCapture.OUTPUT_FORMAT_RAW_JPEG) return takeRawAndJpeg(ic, name, options)
        return withTimeoutOrNull(CAPTURE_TIMEOUT_MS) {
            suspendCancellableCoroutine<CaptureResult> { cont ->
                ic.takePicture(options, ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        val uri = output.savedUri
                        if (cont.isActive) cont.resume(if (uri != null) CaptureResult.Saved(uri.toString()) else CaptureResult.Failed(CaptureFailure.STORAGE))
                    }
                    override fun onError(exception: ImageCaptureException) {
                        if (cont.isActive) cont.resume(CaptureResult.Failed(mapError(exception.imageCaptureError)))
                    }
                })
            }
        }
    }

    /** RAW (DNG) und JPEG in einem Ausloesen; beide landen in Pictures/CayResim. */
    private suspend fun takeRawAndJpeg(ic: ImageCapture, name: String, jpegOptions: ImageCapture.OutputFileOptions): CaptureResult? {
        val rawValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.dng")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/x-adobe-dng")
            put(MediaStore.MediaColumns.RELATIVE_PATH, PHOTO_DIR)
        }
        val rawOptions = ImageCapture.OutputFileOptions.Builder(context.contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, rawValues).build()
        var raw: String? = null; var jpeg: String? = null
        return withTimeoutOrNull(CAPTURE_TIMEOUT_MS * 2) {
            suspendCancellableCoroutine<CaptureResult> { cont ->
                ic.takePicture(rawOptions, jpegOptions, ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        val uri = output.savedUri?.toString() ?: return
                        if (output.imageFormat == android.graphics.ImageFormat.RAW_SENSOR) raw = uri else jpeg = uri
                        val j = jpeg
                        if (j != null && raw != null && cont.isActive) cont.resume(CaptureResult.Saved(j, raw))
                    }
                    override fun onError(exception: ImageCaptureException) {
                        if (cont.isActive) cont.resume(jpeg?.let { CaptureResult.Saved(it, raw) } ?: CaptureResult.Failed(mapError(exception.imageCaptureError)))
                    }
                })
            }
        }
    }

    /**
     * Was das Geraet Drittanbieter-Apps wirklich erlaubt (Bildqualitaets-Dossier, P0). Grundlage fuer die
     * Nachtpipeline: Samsung begrenzt z. B. die Belichtungszeit fuer andere Apps.
     */
    private fun readDeviceReport(info: androidx.camera.core.CameraInfo): DeviceReport {
        val c2 = runCatching { Camera2CameraInfo.from(info) }.getOrNull()
        fun <T> ch(k: CameraCharacteristics.Key<T>): T? = runCatching { c2?.getCameraCharacteristic(k) }.getOrNull()
        val caps = ch(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        val pixels = ch(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val zoom = runCatching { info.zoomState.value }.getOrNull()
        return DeviceReport(
            hardwareLevel = when (ch(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> HardwareLevel.LEVEL_3
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> HardwareLevel.FULL
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> HardwareLevel.LIMITED
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> HardwareLevel.LEGACY
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> HardwareLevel.EXTERNAL
                else -> HardwareLevel.UNKNOWN
            },
            exposureNs = ch(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let { it.lower..it.upper },
            iso = ch(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let { it.lower..it.upper },
            maxFrameNs = ch(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION),
            slowestFps = ch(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.minByOrNull { it.lower }?.let { it.lower..it.upper },
            raw = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW in caps,
            burst = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE in caps,
            sensorWidth = pixels?.width, sensorHeight = pixels?.height,
            zsl = runCatching { info.isZslSupported }.getOrNull(),
            zoomMin = zoom?.minZoomRatio, zoomMax = zoom?.maxZoomRatio,
            physicalCameras = runCatching { info.physicalCameraInfos.size }.getOrNull(),
            ois = ch(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                ?.contains(CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_ON),
            chip = "${android.os.Build.SOC_MANUFACTURER} ${android.os.Build.SOC_MODEL}",
            system = "Android ${android.os.Build.VERSION.RELEASE}, ${android.os.Build.DISPLAY}",
        )
    }

    private fun readManualCapabilities(info: androidx.camera.core.CameraInfo, raw: Boolean): ManualCapabilitiesSnapshot? = runCatching {
        val c2 = Camera2CameraInfo.from(info)
        val caps = c2.getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        val manualSensor = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in caps
        val exp = if (manualSensor) c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) else null
        val iso = if (manualSensor) c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) else null
        val focus = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
        ManualCapabilitiesSnapshot(
            exposureRangeNanos = exp?.let { it.lower..it.upper },
            isoRange = iso?.let { it.lower..it.upper },
            maxFocusDiopters = focus?.takeIf { it > 0f },
            raw = raw,
        )
    }.getOrNull()

    /** Wendet die manuellen Werte ueber Camera2-Interop an; nur im normalen Modus (R11, R12). */
    private fun applyManualOptions() {
        val cam = boundCamera ?: return
        val st = _manualState.value
        val b = CaptureRequestOptions.Builder()
        val exp = st.exposureNanos; val iso = st.iso; val focus = st.focusDiopters
        // S-003: optischen Stabilisator ausdruecklich anfordern, statt auf die Voreinstellung zu hoffen
        if (oisAvailable) b.setCaptureRequestOption(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
        if (exp == null && aeLocked) b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true)
        if (exp != null && iso != null) {
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            b.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, exp)
            b.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
        }
        if (focus != null) {
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            b.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, focus)
        }
        runCatching { Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(b.build()) }
    }

    override suspend fun setExposure(nanos: Long?, iso: Int?): Boolean = withContext(dispatcher) {
        val e = manualEntity
        val ok = when {
            nanos == null || iso == null -> { _manualState.update { it.copy(exposureNanos = null, iso = null) }; true }
            e == null || !e.setExposure(nanos, iso) -> false
            else -> { _manualState.update { it.copy(exposureNanos = e.exposureNanos, iso = e.iso) }; true }
        }
        if (ok) applyManualOptions()
        ok
    }

    override suspend fun setFocus(diopters: Float?): Boolean = withContext(dispatcher) {
        val e = manualEntity
        val ok = when {
            diopters == null -> { _manualState.update { it.copy(focusDiopters = null) }; true }
            e == null || !e.setFocus(diopters) -> false
            else -> { _manualState.update { it.copy(focusDiopters = e.focusDiopters) }; true }
        }
        if (ok) applyManualOptions()
        ok
    }

    override suspend fun setRaw(enabled: Boolean): Boolean = withContext(dispatcher) {
        if (enabled && _manualCaps.value?.raw != true) return@withContext false
        mutex.withLock {
            _manualState.update { it.copy(raw = enabled) }
            val p = provider; val em = extensions
            if (owner.isActive && p != null && em != null) bindCurrent(p, em)
        }
        true
    }

    // ---------- RAW-Messung (Schritt 0 des RAW-Wegs) ----------

    /** Solange gesetzt, bindet tryBind eine reine RAW-Aufnahme (nur im normalen Modus ohne eigene Pipeline). */
    @Volatile private var rawProbeActive = false

    override suspend fun probeRaw(count: Int, exposureNanos: Long, iso: Int): RawProbeResult {
        if (_manualCaps.value?.raw != true) return RawProbeResult.Failed(RawProbeFailure.NOT_SUPPORTED)
        if (_state.value.status != CameraStatus.RUNNING || count <= 0) return RawProbeResult.Failed(RawProbeFailure.NOT_READY)
        val before = _manualState.value
        var switched = false
        try {
            withContext(NonCancellable + dispatcher) {
                mutex.withLock {
                    rawProbeActive = true; switched = true
                    val p = provider; val em = extensions
                    if (owner.isActive && p != null && em != null) bindCurrent(p, em)
                }
            }
            // Ohne sichtbaren Sucher braucht die Vorschau eine Ersatz-Flaeche, sonst oeffnet CameraX die Kamera nie
            // (Emulatorlauf: "useCaseCamera is null", jede RAW-Aufnahme lief in die Zeitgrenze)
            withContext(dispatcher) { ensureSurface() }
            val ic = imageCapture ?: return RawProbeResult.Failed(RawProbeFailure.CAMERA)
            if (ic.outputFormat != ImageCapture.OUTPUT_FORMAT_RAW) return RawProbeResult.Failed(RawProbeFailure.NOT_SUPPORTED)
            val chars = boundCamera?.cameraInfo?.let { info -> runCatching { Camera2CameraInfo.from(info) }.getOrNull() }
            fun <T> ch(k: CameraCharacteristics.Key<T>): T? = runCatching { chars?.getCameraCharacteristic(k) }.getOrNull()
            val pattern = ch(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
            val black = IntArray(4) { i -> pattern?.getOffsetForIndex(i % 2, i / 2) ?: 0 }
            setExposure(exposureNanos, iso)
            delay(AE_SETTLE_MS)
            val times = mutableListOf<Long>()
            var last: RawSample? = null
            var noise: Float? = null
            repeat(count) {
                val t0 = SystemClock.elapsedRealtime()
                // R24: Kamerafehler werden zum Ergebnistyp; Abbruch bleibt Abbruch
                val s = try {
                    withTimeoutOrNull(RAW_FRAME_TIMEOUT_MS) { takeRawSample(ic, black) } ?: return RawProbeResult.Failed(RawProbeFailure.TIMEOUT)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return RawProbeResult.Failed(RawProbeFailure.CAMERA)
                }
                times += SystemClock.elapsedRealtime() - t0
                last?.let { prev -> noise = s.noiseAgainst(prev) }
                last = s
            }
            val s = last!!
            val cameraId = chars?.cameraId
            val map = ch(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val minFrameNs = runCatching { map?.getOutputMinFrameDuration(android.graphics.ImageFormat.RAW_SENSOR, Size(s.width, s.height)) }.getOrNull()
            // Echter RAW-Bildstrom: CameraX kann RAW nur als Einzelfoto, deshalb kurz eine eigene Camera2-Sitzung
            val stream = if (cameraId != null) mutex.withLock { probeRawStream(cameraId, Size(s.width, s.height), exposureNanos, iso) } else null
            return RawProbeResult.Ok(RawProbe(
                frames = times.size, requested = count,
                avgFrameMs = times.average().toLong(), maxFrameMs = times.max(),
                width = s.width, height = s.height,
                blackLevel = black.toList(),
                whiteLevel = ch(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL),
                cfa = when (ch(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)) {
                    CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB -> CfaLayout.RGGB
                    CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG -> CfaLayout.GRBG
                    CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG -> CfaLayout.GBRG
                    CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR -> CfaLayout.BGGR
                    CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGB -> CfaLayout.RGB
                    CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_MONO -> CfaLayout.MONO
                    else -> CfaLayout.UNKNOWN
                },
                colorMatrix = ch(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1) != null,
                forwardMatrix = ch(CameraCharacteristics.SENSOR_FORWARD_MATRIX1) != null,
                lensShading = ch(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_LENS_SHADING_MAP_MODES)
                    ?.contains(CameraCharacteristics.STATISTICS_LENS_SHADING_MAP_MODE_ON) == true,
                meanAboveBlack = s.mean, noise = noise,
                zeroShare = s.zeroShare,
                streamFps = stream?.fps,
                streamMaxFps = minFrameNs?.takeIf { it > 0 }?.let { 1e9f / it },
                streamBlack = stream?.black?.toList(),
                streamWhite = stream?.white,
            ))
        } finally {
            if (switched) withContext(NonCancellable + dispatcher) {
                mutex.withLock {
                    rawProbeActive = false
                    val p = provider; val em = extensions
                    if (owner.isActive && p != null && em != null) bindCurrent(p, em)
                }
                setExposure(before.exposureNanos, before.iso)
            }
        }
    }

    /** Stichprobe eines RAW-Bildes (jedes 7. Pixel und jede 7. Zeile, damit alle vier Farbpositionen vorkommen). */
    private class RawSample(val width: Int, val height: Int, val values: IntArray, val mean: Float, val zeroShare: Float) {
        fun noiseAgainst(prev: RawSample): Float? {
            if (prev.values.size != values.size || values.isEmpty()) return null
            var s = 0.0; var q = 0.0
            for (i in values.indices) { val d = (values[i] - prev.values[i]).toDouble(); s += d; q += d * d }
            val n = values.size; val m = s / n
            return (kotlin.math.sqrt((q / n - m * m).coerceAtLeast(0.0)) / kotlin.math.sqrt(2.0)).toFloat()
        }
    }

    private suspend fun takeRawSample(ic: ImageCapture, black: IntArray): RawSample =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            ic.takePicture(rawExecutor, object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    // R19: das Bild gehoert nur diesem Rueckruf und wird sofort geschlossen
                    val r = runCatching { image.use { sampleRaw(it, black) } }
                    if (cont.isActive) r.fold({ cont.resumeWith(Result.success(it)) }, { cont.resumeWith(Result.failure(it)) })
                }
                override fun onError(exception: ImageCaptureException) {
                    if (cont.isActive) cont.resumeWith(Result.failure(exception))
                }
            })
        }

    private fun sampleRaw(image: ImageProxy, black: IntArray): RawSample {
        check(image.format == android.graphics.ImageFormat.RAW_SENSOR) { "kein RAW: ${image.format}" }
        val plane = image.planes[0]
        val buf = plane.buffer.duplicate().order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val rs = plane.rowStride; val ps = plane.pixelStride
        val w = image.width; val h = image.height
        val values = IntArray(((h + 6) / 7) * ((w + 6) / 7))
        var n = 0; var sum = 0L; var zeros = 0
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val raw = buf.getShort(y * rs + x * ps).toInt() and 0xFFFF
                if (raw == 0) zeros++
                val v = raw - black[(y and 1) * 2 + (x and 1)]
                values[n++] = v; sum += v
                x += 7
            }
            y += 7
        }
        return RawSample(w, h, values.copyOf(n), if (n == 0) 0f else sum.toFloat() / n, if (n == 0) 0f else zeros.toFloat() / n)
    }

    private class RawStream(val fps: Float, val black: FloatArray?, val white: Int?)

    /**
     * Misst 2 s lang einen RAW-Bildstrom mit fester Belichtung in einer eigenen Camera2-Sitzung (nur Messung,
     * Schritt A des RAW-Plans). CameraX wird dafuer losgelassen; der Aufrufer bindet danach neu. null = nicht messbar.
     */
    @android.annotation.SuppressLint("MissingPermission")
    private suspend fun probeRawStream(cameraId: String, size: Size, exposureNs: Long, iso: Int): RawStream? = withContext(dispatcher) {
        provider?.unbindAll(); boundCamera = null; imageCapture = null
        val thread = android.os.HandlerThread("raw-stream").apply { start() }
        val handler = android.os.Handler(thread.looper)
        val executor = java.util.concurrent.Executor { handler.post(it) }
        val mgr = context.getSystemService(android.hardware.camera2.CameraManager::class.java)
        val reader = android.media.ImageReader.newInstance(size.width, size.height, android.graphics.ImageFormat.RAW_SENSOR, 3)
        var device: android.hardware.camera2.CameraDevice? = null
        var session: android.hardware.camera2.CameraCaptureSession? = null
        try {
            device = openCameraWithin(mgr, cameraId, executor)
            val dev = device ?: return@withContext null
            val frames = java.util.concurrent.atomic.AtomicInteger(0)
            reader.setOnImageAvailableListener({ r -> runCatching { r.acquireNextImage()?.close() }; frames.incrementAndGet() }, handler)
            val s = createSession(dev, reader.surface, executor) ?: return@withContext null
            session = s
            val req = dev.createCaptureRequest(android.hardware.camera2.CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(reader.surface)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs)
                set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                set(CaptureRequest.SENSOR_FRAME_DURATION, exposureNs)
            }.build()
            val black = java.util.concurrent.atomic.AtomicReference<FloatArray?>(null)
            val white = java.util.concurrent.atomic.AtomicInteger(-1)
            s.setRepeatingRequest(req, object : android.hardware.camera2.CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: android.hardware.camera2.CameraCaptureSession,
                    request: CaptureRequest,
                    result: android.hardware.camera2.TotalCaptureResult,
                ) {
                    result.get(android.hardware.camera2.CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)?.let { black.set(it) }
                    result.get(android.hardware.camera2.CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL)?.let { white.set(it) }
                }
            }, handler)
            delay(RAW_STREAM_WARMUP_MS)
            val f0 = frames.get(); val t0 = SystemClock.elapsedRealtime()
            delay(RAW_STREAM_MEASURE_MS)
            val f1 = frames.get(); val t1 = SystemClock.elapsedRealtime()
            RawStream((f1 - f0) * 1000f / (t1 - t0).coerceAtLeast(1), black.get(), white.get().takeIf { it >= 0 })
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } finally {
            withContext(NonCancellable) {
                runCatching { session?.stopRepeating() }
                runCatching { session?.close() }
                runCatching { device?.close() }
                runCatching { reader.close() }
                thread.quitSafely()
            }
        }
    }

    /**
     * CameraX schliesst die Kamera verzoegert: wiederholt versuchen, zusammen hoechstens [RAW_OPEN_BUDGET_MS]
     * (S-001 K6, vorher bis zu 8 x 3 s). Danach null, der Aufrufer faellt auf den 8-Bit-Weg zurueck (R14).
     * Das Ergebnis liegt ausserhalb des Zeitblocks, damit ein spaet geoeffnetes Geraet nicht verloren geht.
     */
    private suspend fun openCameraWithin(mgr: android.hardware.camera2.CameraManager, id: String, executor: java.util.concurrent.Executor): android.hardware.camera2.CameraDevice? {
        var dev: android.hardware.camera2.CameraDevice? = null
        withTimeoutOrNull(RAW_OPEN_BUDGET_MS) {
            while (dev == null) {
                dev = openCamera(mgr, id, executor)
                if (dev == null) delay(RAW_OPEN_RETRY_MS)
            }
        }
        return dev
    }

    /**
     * Ein Versuch, ohne eigene Zeitgrenze: die Grenze setzt [openCameraWithin]. Kein zweiter Zeitblock dazwischen,
     * sonst koennte ein geoeffnetes Geraet beim Abbruch zwischen Rueckgabe und Zuweisung verloren gehen.
     */
    @android.annotation.SuppressLint("MissingPermission")
    private suspend fun openCamera(mgr: android.hardware.camera2.CameraManager, id: String, executor: java.util.concurrent.Executor) =
        kotlinx.coroutines.suspendCancellableCoroutine<android.hardware.camera2.CameraDevice?> { cont ->
            try {
                mgr.openCamera(id, executor, object : android.hardware.camera2.CameraDevice.StateCallback() {
                    override fun onOpened(camera: android.hardware.camera2.CameraDevice) {
                        // Abbruch zwischen Oeffnen und Weitergabe: Geraet schliessen statt verlieren
                        if (cont.isActive) cont.resume(camera) { _, c, _ -> c?.close() } else camera.close()
                    }
                    override fun onDisconnected(camera: android.hardware.camera2.CameraDevice) {
                        camera.close(); if (cont.isActive) cont.resumeWith(Result.success(null))
                    }
                    override fun onError(camera: android.hardware.camera2.CameraDevice, error: Int) {
                        camera.close(); if (cont.isActive) cont.resumeWith(Result.success(null))
                    }
                })
            } catch (e: Exception) {
                if (cont.isActive) cont.resumeWith(Result.success(null))
            }
        }

    private suspend fun createSession(dev: android.hardware.camera2.CameraDevice, surface: Surface, executor: java.util.concurrent.Executor) =
        withTimeoutOrNull(3_000) {
            kotlinx.coroutines.suspendCancellableCoroutine<android.hardware.camera2.CameraCaptureSession?> { cont ->
                try {
                    dev.createCaptureSession(android.hardware.camera2.params.SessionConfiguration(
                        android.hardware.camera2.params.SessionConfiguration.SESSION_REGULAR,
                        listOf(android.hardware.camera2.params.OutputConfiguration(surface)), executor,
                        object : android.hardware.camera2.CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: android.hardware.camera2.CameraCaptureSession) {
                                if (cont.isActive) cont.resumeWith(Result.success(session)) else session.close()
                            }
                            override fun onConfigureFailed(session: android.hardware.camera2.CameraCaptureSession) {
                                if (cont.isActive) cont.resumeWith(Result.success(null))
                            }
                        }))
                } catch (e: Exception) {
                    if (cont.isActive) cont.resumeWith(Result.success(null))
                }
            }
        }

    private val rawExecutor = Executors.newSingleThreadExecutor()

    override suspend fun focusBracket(steps: Int): BurstResult {
        if (_state.value.status != CameraStatus.RUNNING) return BurstResult.Failed(BurstFailure.NOT_READY)
        val e = manualEntity ?: return BurstResult.Failed(BurstFailure.NOT_READY)
        val distances = e.focusBracket(steps).ifEmpty { return BurstResult.Failed(BurstFailure.NOT_READY) }
        val previous = _manualState.value.focusDiopters
        var acquired = false
        val received = Channel<Pair<ByteArray, IntArray>>(Channel.CONFLATED)
        val listener: (ByteArray, Int, Int, Int) -> Unit = { bytes, w, h, rot -> received.trySend(bytes.copyOf() to intArrayOf(w, h, rot)) }
        return try {
            acquirePipeline { acquired = true }
            withContext(dispatcher) { ensureSurface() }
            frameListeners += listener
            val frames = withTimeoutOrNull(5_000L + distances.size * 1_500L) {
                distances.map { d ->
                    setFocus(d)
                    delay(FOCUS_SETTLE_MS)
                    received.tryReceive() // veraltetes Bild verwerfen
                    received.receive()
                }
            } ?: return BurstResult.Failed(BurstFailure.TIMEOUT)
            val (w, h, rot) = frames.first().second.let { Triple(it[0], it[1], it[2]) }
            BurstResult.Ok(FrameBurst(w, h, frames.filter { it.second[0] == w && it.second[1] == h }.map { it.first }, rot), distances.size)
        } finally {
            frameListeners -= listener
            received.close()
            withContext(NonCancellable) {
                setFocus(previous)
                if (acquired) releasePipeline()
            }
        }
    }

    /**
     * Ohne Sucher (Selbsttest, Hintergrund) startet die Kamera-Sitzung nicht. Wartet kurz auf den Sucher
     * und stellt sonst eine unsichtbare Flaeche bereit, damit die Aufnahme nicht haengt.
     */
    private suspend fun ensureSurface() {
        // Nach einem Neubinden (z. B. Moduswechsel) kommt die neue Anfrage etwas spaeter an
        withTimeoutOrNull(3_000) { while (pendingRequest == null) delay(20) }
        val req = pendingRequest ?: return
        repeat(10) { if (req.isServiced) return; delay(50) }
        if (req.isServiced) return
        val sink = FallbackPreviewSink.create(req.resolution) ?: return
        fallbackSink = sink
        // Freigabe erst, wenn CameraX die Flaeche zurueckgibt
        req.provideSurface(sink.surface, ContextCompat.getMainExecutor(context)) {
            sink.release()
            if (fallbackSink === sink) fallbackSink = null
        }
    }

    override suspend fun delete(uri: String): Boolean = withContext(dispatcher) {
        runCatching { context.contentResolver.delete(Uri.parse(uri), null, null) > 0 }.getOrDefault(false)
    }

    companion object {
        const val PHOTO_DIR = "Pictures/CayResim"
        const val CAPTURE_TIMEOUT_MS = 8_000L
        /** Obergrenze je RAW-Bild in der Messung; das Tempo selbst bewertet der Selbsttest. */
        const val RAW_FRAME_TIMEOUT_MS = 5_000L
        /** S-001 K6: Gesamtzeit fuer das Oeffnen der Camera2-Sitzung, danach 8-Bit-Weg. */
        const val RAW_OPEN_BUDGET_MS = 3_000L
        private const val RAW_OPEN_RETRY_MS = 250L
        const val RAW_STREAM_WARMUP_MS = 500L
        const val RAW_STREAM_MEASURE_MS = 2_000L
        const val FOCUS_TIMEOUT_MS = 3_000L
        const val AE_SETTLE_MS = 300L
        const val AE_CONVERGE_TIMEOUT_MS = 1_500L
        /** Hoechstens so viele Bilder warten auf die Verarbeitung (Befund M7). */
        const val STREAM_BUFFER = 2
        /** Kommt so lange kein Bild, endet der Strom (Befund C2). */
        const val STREAM_FRAME_TIMEOUT_MS = 3_000L
        /** So lange bleibt der angetippte Punkt scharf, danach wieder Automatik. */
        const val FOCUS_HOLD_S = 5L
        val ANALYSIS_SIZE = Size(1440, 1080)
        const val FOCUS_SETTLE_MS = 350L

        /**
         * S-007: Stabilisator aus einem Aufnahmeergebnis ([on] null = Wert fehlt). Ein Ergebnis ohne Wert ueberschreibt
         * nie ein gemeldetes ON oder OFF (Zweitpruefung: sonst springt der Zustand, wenn Samsung ihn nur manchmal liefert).
         */
        internal fun oisStateOf(on: Boolean?, current: OisState?): OisState = when (on) {
            true -> OisState.ON
            false -> OisState.OFF
            null -> current ?: OisState.NOT_REPORTED
        }

        internal fun mapError(code: Int): CaptureFailure = when (code) {
            ImageCapture.ERROR_FILE_IO -> CaptureFailure.STORAGE
            ImageCapture.ERROR_CAMERA_CLOSED -> CaptureFailure.CAMERA_CLOSED
            ImageCapture.ERROR_CAPTURE_FAILED -> CaptureFailure.UNKNOWN
            else -> CaptureFailure.UNKNOWN
        }
    }
}
