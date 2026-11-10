package com.cozycards.app.renderer

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.os.SystemClock
import android.util.Log
import com.cozycards.app.cards.GlCardLayers
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.exp

/** Visual parameters of the card currently shown. */
data class CardLook(
    val frameFoil: Float,
    val artFoil: Float,
    /** 0 common … 3 legendary. */
    val rarity: Int,
    val edgeColor: Int,
    val backSheen: Float,
    /** Art window in UV space (left, top, right, bottom). */
    val artRect: FloatArray,
)

enum class RenderQuality(val level: Float, val textureSize: Int) { LOW(0f, 512), MEDIUM(1f, 1024), HIGH(2f, 1024) }

/**
 * OpenGL ES 2.0 renderer for a single collectible card. Interaction state is written from the
 * UI thread through the setters and read on the GL thread; values are smoothed every frame.
 */
class CardRenderer(private val onError: (String) -> Unit) : GLSurfaceView.Renderer {
    private val mesh = CardMesh()
    private var cardProgram = 0
    private var bgProgram = 0
    private val tex = IntArray(3)
    private var texturesReady = false
    @Volatile private var pendingLayers: GlCardLayers? = null
    @Volatile var look: CardLook? = null
    @Volatile var quality: RenderQuality = RenderQuality.HIGH
    @Volatile var bgTop = floatArrayOf(0.20f, 0.16f, 0.28f)
    @Volatile var bgBottom = floatArrayOf(0.07f, 0.06f, 0.11f)

    // Interaction targets (degrees / units), written by UI thread.
    @Volatile var targetRotX = 0f
    @Volatile var targetRotY = 0f
    @Volatile var targetScale = 1f
    @Volatile var targetPanX = 0f
    @Volatile var targetPanY = 0f
    @Volatile var flipped = false
    @Volatile var gyroX = 0f
    @Volatile var gyroY = 0f
    @Volatile var interacting = false
    @Volatile var idleSway = true

    private var rotX = 0f; private var rotY = 0f; private var scale = 1f
    private var panX = 0f; private var panY = 0f
    private var flip = 0f
    private var lastFrame = 0L
    private var aspect = 1f
    private val start = SystemClock.uptimeMillis()

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val model = FloatArray(16)
    private val mvp = FloatArray(16)
    private val tmp = FloatArray(16)
    private val inv = FloatArray(16)
    private val camPos = floatArrayOf(0f, 0f, 3.6f)
    private val quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0)
    }

    fun setLayers(layers: GlCardLayers) { pendingLayers = layers }

    fun reset() {
        targetRotX = 0f; targetRotY = 0f; targetScale = 1f; targetPanX = 0f; targetPanY = 0f
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        texturesReady = false
        cardProgram = buildProgram(CardShaders.CARD_VERTEX, CardShaders.CARD_FRAGMENT)
        bgProgram = buildProgram(CardShaders.BG_VERTEX, CardShaders.BG_FRAGMENT)
        GLES20.glGenTextures(3, tex, 0)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        // A re-created context lost its textures: re-upload the last layers if we still have them.
        lastLayers?.let { pendingLayers = it }
    }

    private var lastLayers: GlCardLayers? = null

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        aspect = width.toFloat() / height.coerceAtLeast(1)
        Matrix.perspectiveM(proj, 0, 30f, aspect, 0.1f, 20f)
        // Keep the card fully visible on wide screens too.
        camPos[2] = if (aspect > 0.8f) 3.6f else 3.6f * (0.8f / aspect).coerceAtMost(1.6f)
        Matrix.setLookAtM(view, 0, 0f, 0f, camPos[2], 0f, 0f, 0f, 0f, 1f, 0f)
    }

    override fun onDrawFrame(gl: GL10?) {
        pendingLayers?.let { uploadLayers(it); pendingLayers = null }
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0.016f else ((now - lastFrame) / 1000f).coerceIn(0.001f, 0.1f)
        lastFrame = now
        val t = (now - start) / 1000f

        // Exponential smoothing towards targets.
        val k = 1f - exp(-dt * 14f)
        rotX += (targetRotX - rotX) * k
        rotY += (targetRotY - rotY) * k
        scale += (targetScale - scale) * k
        panX += (targetPanX - panX) * k
        panY += (targetPanY - panY) * k
        val flipTarget = if (flipped) 1f else 0f
        flip += (flipTarget - flip) * (1f - exp(-dt * 7f))
        if (abs(flip - flipTarget) < 0.0005f) flip = flipTarget

        var swayX = 0f; var swayY = 0f
        if (idleSway && !interacting) {
            swayX = kotlin.math.sin(t * 0.7f) * 3f
            swayY = kotlin.math.sin(t * 0.5f + 1f) * 5f
        }

        GLES20.glClearColor(bgBottom[0], bgBottom[1], bgBottom[2], 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val ry = rotY + gyroY + swayY + flip * 180f
        val rx = rotX + gyroX + swayX
        drawBackground(rx, ry)
        val l = look
        if (!texturesReady || l == null) return

        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, panX, panY, 0f)
        Matrix.scaleM(model, 0, scale, scale, scale)
        Matrix.rotateM(model, 0, rx, 1f, 0f, 0f)
        Matrix.rotateM(model, 0, ry, 0f, 1f, 0f)
        Matrix.multiplyMM(tmp, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, proj, 0, tmp, 0)
        Matrix.invertM(inv, 0, model, 0)
        val camLocal = FloatArray(4)
        Matrix.multiplyMV(camLocal, 0, inv, 0, floatArrayOf(camPos[0], camPos[1], camPos[2], 1f), 0)
        // Normal matrix = rotation part of the model (uniform scale only).
        val nm = floatArrayOf(model[0], model[1], model[2], model[4], model[5], model[6], model[8], model[9], model[10])
        val s = scale.coerceAtLeast(0.01f)
        for (i in nm.indices) nm[i] /= s

        GLES20.glUseProgram(cardProgram)
        val p = cardProgram
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(p, "uMvp"), 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(p, "uModel"), 1, false, model, 0)
        GLES20.glUniformMatrix3fv(GLES20.glGetUniformLocation(p, "uNormalMatrix"), 1, false, nm, 0)
        GLES20.glUniform3f(GLES20.glGetUniformLocation(p, "uCamPos"), camPos[0], camPos[1], camPos[2])
        GLES20.glUniform3f(GLES20.glGetUniformLocation(p, "uCamLocal"), camLocal[0], camLocal[1], camLocal[2])
        GLES20.glUniform3f(GLES20.glGetUniformLocation(p, "uLightDir"), -0.45f, 0.7f, 0.9f)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uTime"), t)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uFrameFoil"), l.frameFoil)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uArtFoil"), if (quality == RenderQuality.LOW) l.artFoil * 0.6f else l.artFoil)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uRarity"), l.rarity.toFloat())
        GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uQuality"), quality.level)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uBackSheen"), l.backSheen)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uDepth"), 0.035f)
        val ec = l.edgeColor
        GLES20.glUniform3f(GLES20.glGetUniformLocation(p, "uEdgeColor"), ((ec shr 16) and 255) / 255f, ((ec shr 8) and 255) / 255f, (ec and 255) / 255f)
        GLES20.glUniform4f(GLES20.glGetUniformLocation(p, "uArtRect"), l.artRect[0], l.artRect[1], l.artRect[2], l.artRect[3])
        bindTex(0, tex[0], "uFrame")
        bindTex(1, tex[1], "uArt")
        bindTex(2, tex[2], "uBack")

        val stride = CardMesh.STRIDE_BYTES
        val buf = mesh.buffer
        fun attrib(name: String, size: Int, offset: Int) {
            val loc = GLES20.glGetAttribLocation(p, name)
            if (loc < 0) return
            buf.position(offset)
            GLES20.glVertexAttribPointer(loc, size, GLES20.GL_FLOAT, false, stride, buf)
            GLES20.glEnableVertexAttribArray(loc)
        }
        attrib("aPos", 3, 0); attrib("aNormal", 3, 3); attrib("aUv", 2, 6); attrib("aFace", 1, 8)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, mesh.vertexCount)
        buf.position(0)
    }

    private fun drawBackground(rx: Float, ry: Float) {
        GLES20.glDepthMask(false)
        GLES20.glUseProgram(bgProgram)
        val p = bgProgram
        GLES20.glUniform3f(GLES20.glGetUniformLocation(p, "uTop"), bgTop[0], bgTop[1], bgTop[2])
        GLES20.glUniform3f(GLES20.glGetUniformLocation(p, "uBottom"), bgBottom[0], bgBottom[1], bgBottom[2])
        // Shadow moves opposite to the tilt, as if lit from above-front.
        val sx = 0.5f + panX * 0.25f - ry / 900f
        val sy = 0.42f + panY * 0.2f + rx / 900f
        GLES20.glUniform2f(GLES20.glGetUniformLocation(p, "uShadow"), sx, sy)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(p, "uShadowSize"), 0.32f * scale / aspect.coerceAtLeast(0.3f) * 0.6f, 0.36f * scale)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uShadowAlpha"), if (texturesReady) 0.55f else 0f)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uAspect"), aspect)
        val loc = GLES20.glGetAttribLocation(p, "aPos")
        GLES20.glVertexAttribPointer(loc, 2, GLES20.GL_FLOAT, false, 8, quad)
        GLES20.glEnableVertexAttribArray(loc)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(loc)
        GLES20.glDepthMask(true)
    }

    private fun bindTex(unit: Int, id: Int, name: String) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(cardProgram, name), unit)
    }

    private fun uploadLayers(layers: GlCardLayers) {
        val size = quality.textureSize
        upload(tex[0], layers.frame, size)
        upload(tex[1], layers.art, size)
        upload(tex[2], layers.back, size)
        lastLayers = layers
        texturesReady = true
    }

    /** ES 2.0 needs power-of-two textures for mipmaps: resample to a square POT texture. */
    private fun upload(id: Int, bmp: Bitmap, size: Int) {
        val pot = if (bmp.width == size && bmp.height == size) bmp else Bitmap.createScaledBitmap(bmp, size, size, true)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, pot, 0)
        GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
        if (pot !== bmp) pot.recycle()
    }

    private fun buildProgram(vs: String, fs: String): Int {
        val v = compile(GLES20.GL_VERTEX_SHADER, vs)
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(p)
            Log.e("CardRenderer", "link failed: $log")
            onError("Shader link failed: $log")
        }
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(s)
            Log.e("CardRenderer", "compile failed: $log")
            onError("Shader compile failed: $log")
        }
        return s
    }
}
