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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
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
            owner.pause()
            pendingRequest = null
            // Den Sucher-Ersatz gibt CameraX ueber den Ergebnis-Rueckruf frei, sobald die Sitzung ihn losgelassen hat
            fallbackSink = null
            imageCapture = null
            _state.update { it.copy(status = CameraStatus.IDLE, preview = null, capturing = false) }
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
    }

    private fun tryBind(p: ProcessCameraProvider, em: ExtensionsManager, key: ModeKey): Boolean = try {
        p.unbindAll()
        val preview = Preview.Builder().build()
        val rawWanted = _manualState.value.raw && pipelineUsers == 0 && key == ModeKey.NORMAL
        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .apply { if (rawWanted) setOutputFormat(ImageCapture.OUTPUT_FORMAT_RAW_JPEG) }
            .build()
        preview.setSurfaceProvider { request ->
            pendingRequest = request
            _state.update { it.copy(preview = PreviewHandle(request)) }
        }
        val ext = if (pipelineUsers > 0) ExtensionMode.NONE else key.toExtensionMode()
        val useCases = mutableListOf(preview, capture)
        if (pipelineUsers > 0) useCases += buildAnalysis()
        val config: SessionConfig =
            if (ext == ExtensionMode.NONE) SessionConfig(useCases)
            else ExtensionSessionConfig(ext, em, useCases)
        if (ext == ExtensionMode.NONE && !p.getCameraInfo(selector).isSessionConfigSupported(config)) {
            false
        } else {
            boundCamera = p.bindToLifecycle(owner, selector, config)
            imageCapture = capture
            if (ext == ExtensionMode.NONE) applyManualOptions()
            true
        }
    } catch (e: Exception) {
        imageCapture = null
        false
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

    private suspend fun acquirePipeline() = withContext(dispatcher) {
        mutex.withLock {
            pipelineUsers++
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
        acquirePipeline()
        val received = Channel<Pair<ByteArray, IntArray>>(Channel.UNLIMITED)
        var taken = 0
        val listener: (ByteArray, Int, Int, Int) -> Unit = { bytes, w, h, rot ->
            // Kopie nur fuer die angeforderten Bilder; jede Kopie wird Teil der Serie
            if (taken < n) { taken++; received.trySend(bytes.copyOf() to intArrayOf(w, h, rot)) }
        }
        return try {
            withContext(dispatcher) { ensureSurface() }
            frameListeners += listener
            val frames = withTimeoutOrNull(5_000L + n * 400L) { List(n) { received.receive() } }
                ?: return BurstResult.Failed(BurstFailure.TIMEOUT)
            val (w, h, rot) = frames.first().second.let { Triple(it[0], it[1], it[2]) }
            BurstResult.Ok(FrameBurst(w, h, frames.filter { it.second[0] == w && it.second[1] == h }.map { it.first }, rot), count)
        } finally {
            frameListeners -= listener
            received.close()
            releasePipeline()
        }
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
        acquirePipeline()
        withContext(dispatcher) { ensureSurface() }
        frameListeners += listener
        awaitClose { frameListeners -= listener; launch(NonCancellable) { releasePipeline() } }
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

    override suspend fun focusBracket(steps: Int): BurstResult {
        if (_state.value.status != CameraStatus.RUNNING) return BurstResult.Failed(BurstFailure.NOT_READY)
        val e = manualEntity ?: return BurstResult.Failed(BurstFailure.NOT_READY)
        val distances = e.focusBracket(steps).ifEmpty { return BurstResult.Failed(BurstFailure.NOT_READY) }
        val previous = _manualState.value.focusDiopters
        acquirePipeline()
        val received = Channel<Pair<ByteArray, IntArray>>(Channel.CONFLATED)
        val listener: (ByteArray, Int, Int, Int) -> Unit = { bytes, w, h, rot -> received.trySend(bytes.copyOf() to intArrayOf(w, h, rot)) }
        return try {
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
            withContext(NonCancellable) { setFocus(previous) }
            releasePipeline()
        }
    }

    /**
     * Ohne Sucher (Selbsttest, Hintergrund) startet die Kamera-Sitzung nicht. Wartet kurz auf den Sucher
     * und stellt sonst eine unsichtbare Flaeche bereit, damit die Aufnahme nicht haengt.
     */
    private suspend fun ensureSurface() {
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
        val ANALYSIS_SIZE = Size(1440, 1080)
        const val FOCUS_SETTLE_MS = 350L

        internal fun mapError(code: Int): CaptureFailure = when (code) {
            ImageCapture.ERROR_FILE_IO -> CaptureFailure.STORAGE
            ImageCapture.ERROR_CAMERA_CLOSED -> CaptureFailure.CAMERA_CLOSED
            ImageCapture.ERROR_CAPTURE_FAILED -> CaptureFailure.UNKNOWN
            else -> CaptureFailure.UNKNOWN
        }
    }
}
