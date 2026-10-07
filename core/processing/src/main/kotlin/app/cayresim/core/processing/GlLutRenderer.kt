package app.cayresim.core.processing

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.opengl.GLUtils
import app.cayresim.core.pure.Lut3D
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Wendet eine 3D-LUT auf der GPU an (R20). Genau ein GL-Kontext, nur auf dem GpuDispatcher benutzen.
 * Die Kotlin-Referenz ist [Lut3D.apply]; der Emulator-Test vergleicht beide.
 */
class GlLutRenderer {
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0

    fun isReady() = program != 0

    /** Richtet den Kontext ein; false, wenn kein GLES 3 verfuegbar ist (Fehler als Wert, R24). */
    fun setUp(): Boolean {
        if (isReady()) return true
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) return false
        val v = IntArray(2)
        if (!EGL14.eglInitialize(display, v, 0, v, 1)) return false
        val attribs = intArrayOf(EGL14.EGL_RENDERABLE_TYPE, 0x40 /* EGL_OPENGL_ES3_BIT_KHR */, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE)
        val configs = arrayOfNulls<EGLConfig>(1); val n = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, n, 0) || n[0] == 0) return false
        context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        if (context == EGL14.EGL_NO_CONTEXT) return false
        surface = EGL14.eglCreatePbufferSurface(display, configs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        if (!EGL14.eglMakeCurrent(display, surface, surface, context)) return false
        program = buildProgram()
        return program != 0
    }

    fun maxTextureSize(): Int { val a = IntArray(1); GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, a, 0); return a[0] }

    /** Gibt ein neues Bitmap mit angewendeter LUT zurueck, oder null bei GPU-Fehler. */
    fun render(src: Bitmap, lut: Lut3D): Bitmap? {
        if (!setUp()) return null
        val w = src.width; val h = src.height
        if (w > maxTextureSize() || h > maxTextureSize()) return null
        val tex = IntArray(3)
        GLES30.glGenTextures(3, tex, 0)
        try {
            // Quelle
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0])
            params2d()
            val argb = if (src.config == Bitmap.Config.ARGB_8888) src else src.copy(Bitmap.Config.ARGB_8888, false)
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, argb, 0)
            // LUT als 3D-Textur in Gleitkomma, damit die Interpolation genau bleibt
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, tex[1])
            for (p in intArrayOf(GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_TEXTURE_WRAP_R))
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, p, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            val fb: FloatBuffer = ByteBuffer.allocateDirect(lut.data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(lut.data).also { it.position(0) }
            GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB16F, lut.size, lut.size, lut.size, 0, GLES30.GL_RGB, GLES30.GL_FLOAT, fb)
            // Ziel
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[2])
            params2d()
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
            val fbo = IntArray(1); GLES30.glGenFramebuffers(1, fbo, 0)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tex[2], 0)
            if (GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) != GLES30.GL_FRAMEBUFFER_COMPLETE) return null

            GLES30.glViewport(0, 0, w, h)
            GLES30.glUseProgram(program)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0])
            GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uSrc"), 0)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1); GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, tex[1])
            GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uLut"), 1)
            GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uSize"), lut.size.toFloat())
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)

            val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
            GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glDeleteFramebuffers(1, fbo, 0)
            if (GLES30.glGetError() != GLES30.GL_NO_ERROR) return null
            buf.position(0)
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            out.copyPixelsFromBuffer(buf)
            if (argb !== src) argb.recycle()
            return out
        } finally {
            GLES30.glDeleteTextures(3, tex, 0)
        }
    }

    fun release() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            if (program != 0) GLES30.glDeleteProgram(program)
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface); EGL14.eglDestroyContext(display, context); EGL14.eglTerminate(display)
        }
        display = EGL14.EGL_NO_DISPLAY; context = EGL14.EGL_NO_CONTEXT; surface = EGL14.EGL_NO_SURFACE; program = 0
    }

    private fun params2d() {
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
    }

    private fun buildProgram(): Int {
        fun compile(type: Int, src: String): Int {
            val s = GLES30.glCreateShader(type); GLES30.glShaderSource(s, src); GLES30.glCompileShader(s)
            val ok = IntArray(1); GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) { GLES30.glDeleteShader(s); return 0 }
            return s
        }
        val vs = compile(GLES30.GL_VERTEX_SHADER, VERTEX); val fs = compile(GLES30.GL_FRAGMENT_SHADER, FRAGMENT)
        if (vs == 0 || fs == 0) return 0
        val p = GLES30.glCreateProgram(); GLES30.glAttachShader(p, vs); GLES30.glAttachShader(p, fs); GLES30.glLinkProgram(p)
        val ok = IntArray(1); GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        GLES30.glDeleteShader(vs); GLES30.glDeleteShader(fs)
        return if (ok[0] == 0) 0 else p
    }

    private companion object {
        // Ein Dreieck ueber den ganzen Bildschirm, ohne Vertex-Puffer.
        const val VERTEX = """#version 300 es
out vec2 vUv;
void main() {
    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    vUv = p;
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}"""
        // Bitmap-Zeile 0 liegt oben, glReadPixels liest unten zuerst: beides hebt sich auf, wenn vUv direkt genutzt wird.
        const val FRAGMENT = """#version 300 es
precision highp float;
precision highp sampler3D;
uniform sampler2D uSrc;
uniform sampler3D uLut;
uniform float uSize;
in vec2 vUv;
out vec4 outColor;
void main() {
    vec4 c = texture(uSrc, vUv);
    vec3 coord = (c.rgb * (uSize - 1.0) + 0.5) / uSize;
    outColor = vec4(texture(uLut, coord).rgb, 1.0);
}"""
    }
}
