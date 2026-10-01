package pl.vovcia.softtempest

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.os.SystemClock
import android.util.Log
import java.security.SecureRandom
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * OpenGL ES 3.0 renderer that draws one full-screen triangle through the noise fragment shader.
 *
 * Every frame gets a fresh 32-bit seed from a CSPRNG so the pattern is unpredictable and never
 * repeats. [amplitude] and [mode] are written from the main thread and read on the GL thread;
 * both are `@Volatile` primitives so no locking is required.
 */
class NoiseRenderer : GLSurfaceView.Renderer {

    @Volatile var amplitude: Float = NoiseSettings.DEFAULT_AMPLITUDE
    @Volatile var mode: Int = NoiseSettings.DEFAULT_MODE

    /**
     * Alpha applied to the whole window by the compositor (see OverlayService). The shader
     * amplitude is scaled up by its inverse so the user-facing amplitude stays the effective
     * on-screen opacity, up to the window-alpha cap.
     */
    @Volatile var windowAlpha: Float = 1f

    private val random = SecureRandom()
    private var program = 0
    private var vao = 0
    private var uTime = -1
    private var uAmplitude = -1
    private var uMode = -1
    private var uResolution = -1
    private var uSeed = -1
    private var width = 1
    private var height = 1
    private var startNanos = 0L

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = buildProgram(Shaders.VERTEX, Shaders.FRAGMENT)
        uTime = GLES30.glGetUniformLocation(program, "uTime")
        uAmplitude = GLES30.glGetUniformLocation(program, "uAmplitude")
        uMode = GLES30.glGetUniformLocation(program, "uMode")
        uResolution = GLES30.glGetUniformLocation(program, "uResolution")
        uSeed = GLES30.glGetUniformLocation(program, "uSeed")

        val ids = IntArray(1)
        GLES30.glGenVertexArrays(1, ids, 0)
        vao = ids[0]

        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_BLEND) // we overwrite the whole surface each frame
        GLES30.glClearColor(0f, 0f, 0f, 0f) // fully transparent
        startNanos = SystemClock.elapsedRealtimeNanos()
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w.coerceAtLeast(1)
        height = h.coerceAtLeast(1)
        GLES30.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        if (program == 0) return

        // Wrap the clock so the float uniform keeps millisecond precision indefinitely.
        val elapsedSec = ((SystemClock.elapsedRealtimeNanos() - startNanos) / 1_000_000L % 3_600_000L) / 1000f

        GLES30.glUseProgram(program)
        GLES30.glUniform1f(uTime, elapsedSec)
        val wa = windowAlpha.coerceIn(0.05f, 1f)
        GLES30.glUniform1f(uAmplitude, (amplitude / wa).coerceIn(0f, 1f))
        GLES30.glUniform1i(uMode, mode)
        GLES30.glUniform2f(uResolution, width.toFloat(), height.toFloat())
        GLES30.glUniform1ui(uSeed, random.nextInt())

        GLES30.glBindVertexArray(vao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glBindVertexArray(0)
    }

    private fun buildProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vs = compile(GLES30.GL_VERTEX_SHADER, vertexSrc)
        val fs = compile(GLES30.GL_FRAGMENT_SHADER, fragmentSrc)
        val prog = GLES30.glCreateProgram()
        GLES30.glAttachShader(prog, vs)
        GLES30.glAttachShader(prog, fs)
        GLES30.glLinkProgram(prog)
        val status = IntArray(1)
        GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, status, 0)
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        if (status[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(prog)
            GLES30.glDeleteProgram(prog)
            Log.e(TAG, "Program link failed: $log")
            return 0
        }
        return prog
    }

    private fun compile(type: Int, src: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, src)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "Shader compile failed (type=$type): ${GLES30.glGetShaderInfoLog(shader)}")
        }
        return shader
    }

    private companion object {
        const val TAG = "NoiseRenderer"
    }
}
