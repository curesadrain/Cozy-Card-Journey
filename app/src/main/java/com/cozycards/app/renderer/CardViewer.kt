package com.cozycards.app.renderer

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.GLSurfaceView
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.cozycards.app.cards.CardLayout
import com.cozycards.app.cards.GlCardLayers
import com.cozycards.app.cards.RarityStyle
import com.cozycards.game.cards.CardDef
import com.cozycards.game.cards.Rarity
import com.cozycards.game.economy.Cosmetic
import kotlin.math.hypot

/** Controller handed to the parent so buttons (flip, reset) can drive the renderer. */
class CardViewerController {
    internal var renderer: CardRenderer? = null
    var flipped by mutableStateOf(false)
        private set

    fun flip() {
        flipped = !flipped
        renderer?.flipped = flipped
    }

    fun reset() = renderer?.reset()
}

fun lookFor(card: CardDef, back: Cosmetic): CardLook {
    val l = CardLayout(1000, 1400)
    return CardLook(
        frameFoil = RarityStyle.frameFoil(card.gameRarity),
        artFoil = RarityStyle.artFoil(card.gameRarity),
        rarity = card.gameRarity.ordinal,
        edgeColor = when (card.gameRarity) {
            Rarity.LEGENDARY -> 0xE2B857
            Rarity.EPIC -> 0xD9CBEF
            else -> 0xF2EEE6
        },
        backSheen = if (back == Cosmetic.BACK_GOLD) 0.8f else if (back == Cosmetic.BACK_LANTERN) 0.3f else 0f,
        artRect = floatArrayOf(l.art.left / 1000f, l.art.top / 1400f, l.art.right / 1000f, l.art.bottom / 1400f),
        textRect = floatArrayOf(l.textBox.left / 1000f, l.textBox.top / 1400f, l.textBox.right / 1000f, l.textBox.bottom / 1400f),
        nameRect = floatArrayOf(l.nameBar.left / 1000f, l.nameBar.top / 1400f, l.nameBar.right / 1000f, l.nameBar.bottom / 1400f),
    )
}

/**
 * Interactive 2.5D card: one finger rotates, two fingers zoom/pan, double tap resets.
 * The device tilt (game rotation vector) adds parallax when enabled.
 */
@Composable
fun CardViewer(
    card: CardDef,
    layers: GlCardLayers?,
    back: Cosmetic,
    quality: RenderQuality,
    gyroscope: Boolean,
    controller: CardViewerController,
    modifier: Modifier = Modifier,
    onGlError: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val errorCallback by rememberUpdatedState(onGlError)
    val renderer = remember { CardRenderer { msg -> errorCallback(msg) } }
    var surface by remember { mutableStateOf<GLSurfaceView?>(null) }

    LaunchedEffect(renderer) { controller.renderer = renderer; renderer.flipped = controller.flipped }
    LaunchedEffect(card.id, back) { renderer.look = lookFor(card, back) }
    LaunchedEffect(layers) { layers?.let { renderer.setLayers(it) } }
    LaunchedEffect(quality) { renderer.quality = quality }

    // Lifecycle: pause GL rendering in background.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, surface) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> surface?.onResume()
                Lifecycle.Event.ON_PAUSE -> surface?.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    // Gyroscope tilt.
    DisposableEffect(gyroscope) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) ?: sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val listener = TiltListener(renderer)
        if (gyroscope && sensor != null) sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        renderer.idleSway = !(gyroscope && sensor != null)
        onDispose {
            sm.unregisterListener(listener)
            renderer.gyroX = 0f; renderer.gyroY = 0f
        }
    }

    Box(modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                GLSurfaceView(ctx).apply {
                    setEGLContextClientVersion(2)
                    if (quality == RenderQuality.HIGH) setEGLConfigChooser(MultisampleConfigChooser()) else setEGLConfigChooser(8, 8, 8, 8, 16, 0)
                    preserveEGLContextOnPause = true
                    setRenderer(renderer)
                    renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                    surface = this
                }
            },
            onRelease = { it.onPause() },
        )
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = { renderer.reset() })
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        renderer.interacting = true
                        var lastDist = 0f
                        var lastCentroid = Offset.Unspecified
                        do {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.size == 1) {
                                val c = pressed[0]
                                if (c.positionChanged()) {
                                    val d = c.position - c.previousPosition
                                    renderer.targetRotY = (renderer.targetRotY + d.x * 0.35f).coerceIn(-75f, 75f)
                                    renderer.targetRotX = (renderer.targetRotX + d.y * 0.35f).coerceIn(-60f, 60f)
                                    c.consume()
                                }
                                lastDist = 0f
                                lastCentroid = Offset.Unspecified
                            } else if (pressed.size >= 2) {
                                val a = pressed[0].position
                                val b = pressed[1].position
                                val dist = hypot(a.x - b.x, a.y - b.y)
                                val centroid = (a + b) / 2f
                                if (lastDist > 0f) {
                                    renderer.targetScale = (renderer.targetScale * dist / lastDist).coerceIn(0.6f, 2.6f)
                                    val dc = centroid - lastCentroid
                                    renderer.targetPanX = (renderer.targetPanX + dc.x / size.width * 2.2f).coerceIn(-1.2f, 1.2f)
                                    renderer.targetPanY = (renderer.targetPanY - dc.y / size.height * 2.6f).coerceIn(-1.4f, 1.4f)
                                }
                                lastDist = dist
                                lastCentroid = centroid
                                pressed.forEach { it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                        renderer.interacting = false
                    }
                },
        )
    }
}

/** High-pass filtered device tilt → small rotation offsets. */
private class TiltListener(private val renderer: CardRenderer) : SensorEventListener {
    private val rot = FloatArray(9)
    private val orient = FloatArray(3)
    private var basePitch = Float.NaN
    private var baseRoll = Float.NaN

    override fun onSensorChanged(e: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rot, e.values)
        SensorManager.getOrientation(rot, orient)
        val pitch = Math.toDegrees(orient[1].toDouble()).toFloat()
        val roll = Math.toDegrees(orient[2].toDouble()).toFloat()
        if (basePitch.isNaN()) { basePitch = pitch; baseRoll = roll }
        // Slowly follow the resting position so any holding angle becomes neutral.
        basePitch += (pitch - basePitch) * 0.01f
        baseRoll += (roll - baseRoll) * 0.01f
        val tx = ((pitch - basePitch) * 1.2f).coerceIn(-25f, 25f)
        val ty = ((roll - baseRoll) * 1.2f).coerceIn(-25f, 25f)
        renderer.gyroX += (tx - renderer.gyroX) * 0.25f
        renderer.gyroY += (ty - renderer.gyroY) * 0.25f
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}

/** 4x MSAA when available, otherwise a plain RGBA8888 + depth config. */
private class MultisampleConfigChooser : GLSurfaceView.EGLConfigChooser {
    override fun chooseConfig(egl: javax.microedition.khronos.egl.EGL10, display: javax.microedition.khronos.egl.EGLDisplay): javax.microedition.khronos.egl.EGLConfig {
        val EGL_OPENGL_ES2_BIT = 4
        fun attempt(samples: Int): javax.microedition.khronos.egl.EGLConfig? {
            val attrs = mutableListOf(
                javax.microedition.khronos.egl.EGL10.EGL_RED_SIZE, 8, javax.microedition.khronos.egl.EGL10.EGL_GREEN_SIZE, 8,
                javax.microedition.khronos.egl.EGL10.EGL_BLUE_SIZE, 8, javax.microedition.khronos.egl.EGL10.EGL_DEPTH_SIZE, 16,
                javax.microedition.khronos.egl.EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
            )
            if (samples > 0) attrs += listOf(javax.microedition.khronos.egl.EGL10.EGL_SAMPLE_BUFFERS, 1, javax.microedition.khronos.egl.EGL10.EGL_SAMPLES, samples)
            attrs += javax.microedition.khronos.egl.EGL10.EGL_NONE
            val configs = arrayOfNulls<javax.microedition.khronos.egl.EGLConfig>(1)
            val num = IntArray(1)
            return if (egl.eglChooseConfig(display, attrs.toIntArray(), configs, 1, num) && num[0] > 0) configs[0] else null
        }
        return attempt(4) ?: attempt(0) ?: error("No EGL config")
    }
}
