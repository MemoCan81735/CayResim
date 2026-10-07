package app.cayresim.core.camera

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Unsichtbarer Sucher fuer Aufnahmen ohne Bildschirm-Sucher (Selbsttest).
 *
 * Verhaelt sich wie ein echter Sucher: eine SurfaceTexture mit eigenem OpenGL-Kontext, die jedes
 * Bild sofort abnimmt (updateTexImage). Dieser Weg entspricht TextureView und dem Compose-Sucher
 * und wird von allen Hersteller-Extensions unterstuetzt.
 *
 * Bewusst nicht verwendet:
 * - SurfaceTexture ohne Abnehmer: staut nach wenigen Bildern den Bildstrom, die Aufnahme haengt (v0.1.17).
 * - ImageReader im Format PRIVATE: brachte auf dem S24+ den Kamera-Treiber zum Absturz, das Geraet startete neu (v0.1.27).
 */
internal class FallbackPreviewSink private constructor(
    private val thread: HandlerThread,
    private val display: EGLDisplay,
    private val context: EGLContext,
    private val pbuffer: EGLSurface,
    private val textureId: Int,
    private val texture: SurfaceTexture,
    val surface: Surface,
) {
    private val handler = Handler(thread.looper)
    @Volatile private var released = false

    init {
        texture.setOnFrameAvailableListener({ if (!released) runCatching { it.updateTexImage() } }, handler)
    }

    /** Gibt alles frei, erst wenn CameraX die Flaeche nicht mehr nutzt. Mehrfacher Aufruf ist harmlos. */
    fun release() {
        if (released) return
        released = true
        handler.post {
            runCatching { texture.setOnFrameAvailableListener(null) }
            runCatching { surface.release() }
            runCatching { texture.release() }
            runCatching { GLES20.glDeleteTextures(1, intArrayOf(textureId), 0) }
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, pbuffer)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread()
            thread.quitSafely()
        }
    }

    companion object {
        /** Legt den Sucher-Ersatz an; null, wenn OpenGL nicht verfuegbar ist (dann wird ohne Ersatz aufgenommen). */
        fun create(size: Size): FallbackPreviewSink? {
            val thread = HandlerThread("cayresim-fallback-preview").apply { start() }
            var sink: FallbackPreviewSink? = null
            val done = CountDownLatch(1)
            Handler(thread.looper).post {
                sink = runCatching { build(thread, size) }.getOrNull()
                done.countDown()
            }
            if (!done.await(2, TimeUnit.SECONDS) || sink == null) {
                thread.quitSafely()
                return null
            }
            return sink
        }

        private fun build(thread: HandlerThread, size: Size): FallbackPreviewSink? {
            val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) return null
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            val attribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_NONE,
            )
            if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) || count[0] == 0) return null
            val config = configs[0] ?: return null
            val context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            if (context == EGL14.EGL_NO_CONTEXT) return null
            val pbuffer = EGL14.eglCreatePbufferSurface(display, config,
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            if (pbuffer == EGL14.EGL_NO_SURFACE || !EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)) {
                EGL14.eglDestroyContext(display, context)
                return null
            }
            val tex = IntArray(1)
            GLES20.glGenTextures(1, tex, 0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0])
            val texture = SurfaceTexture(tex[0]).apply { setDefaultBufferSize(size.width, size.height) }
            return FallbackPreviewSink(thread, display, context, pbuffer, tex[0], texture, Surface(texture))
        }
    }
}
