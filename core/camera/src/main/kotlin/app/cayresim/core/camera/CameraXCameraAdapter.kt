package app.cayresim.core.camera

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.SessionConfig
import androidx.camera.core.SurfaceRequest
import android.graphics.SurfaceTexture
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
) : CameraBoundary {

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
    private var fallbackSurface: Pair<SurfaceTexture, Surface>? = null
    private val selector = CameraSelector.DEFAULT_BACK_CAMERA

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
            fallbackSurface?.let { (t, s) -> s.release(); t.release() }
            fallbackSurface = null
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
        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
        preview.setSurfaceProvider { request ->
            pendingRequest = request
            _state.update { it.copy(preview = PreviewHandle(request)) }
        }
        val ext = key.toExtensionMode()
        val config: SessionConfig =
            if (ext == ExtensionMode.NONE) SessionConfig(listOf(preview, capture))
            else ExtensionSessionConfig(ext, em, listOf(preview, capture))
        if (ext == ExtensionMode.NONE && !p.getCameraInfo(selector).isSessionConfigSupported(config)) {
            false
        } else {
            p.bindToLifecycle(owner, selector, config)
            imageCapture = capture
            true
        }
    } catch (e: Exception) {
        imageCapture = null
        false
    }

    private fun publishMode() {
        _state.update {
            it.copy(
                requestedMode = selection.requestedMode.toPhotoMode(),
                activeMode = selection.effectiveMode.toPhotoMode(),
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

    /**
     * Ohne Sucher (Selbsttest, Hintergrund) startet die Kamera-Sitzung nicht. Wartet kurz auf den Sucher
     * und stellt sonst eine unsichtbare Flaeche bereit, damit die Aufnahme nicht haengt.
     */
    private suspend fun ensureSurface() {
        val req = pendingRequest ?: return
        repeat(10) { if (req.isServiced) return; delay(50) }
        if (req.isServiced) return
        val texture = SurfaceTexture(0).apply { setDefaultBufferSize(req.resolution.width, req.resolution.height) }
        val surface = Surface(texture)
        fallbackSurface?.let { (t, s) -> s.release(); t.release() }
        fallbackSurface = texture to surface
        req.provideSurface(surface, ContextCompat.getMainExecutor(context)) { }
    }

    override suspend fun delete(uri: String): Boolean = withContext(dispatcher) {
        runCatching { context.contentResolver.delete(Uri.parse(uri), null, null) > 0 }.getOrDefault(false)
    }

    companion object {
        const val PHOTO_DIR = "Pictures/CayResim"
        const val CAPTURE_TIMEOUT_MS = 8_000L

        internal fun mapError(code: Int): CaptureFailure = when (code) {
            ImageCapture.ERROR_FILE_IO -> CaptureFailure.STORAGE
            ImageCapture.ERROR_CAMERA_CLOSED -> CaptureFailure.CAMERA_CLOSED
            ImageCapture.ERROR_CAPTURE_FAILED -> CaptureFailure.UNKNOWN
            else -> CaptureFailure.UNKNOWN
        }
    }
}
