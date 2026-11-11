package com.cozycards.app.ui.gfx

import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * Effects layer that lives independently of board steps, so particles, debris, beams and
 * flying goal items keep moving while the next phase (falling, cascades) already plays.
 * All coordinates are in the overlay's pixel space. Updated once per frame by [tick].
 */
class Fx {
    /** Incremented every frame; reading it inside a draw lambda schedules redraws. */
    val frame = mutableLongStateOf(0L)
    var timeMs = 0.0
        private set

    private val particles = ArrayList<Particle>(512)
    private val rings = ArrayList<Ring>()
    private val beams = ArrayList<Beam>()
    private val flyers = ArrayList<Flyer>()
    private val texts = ArrayList<FloatText>()
    private val flashes = ArrayList<Flash>()
    private val scheduled = ArrayList<Pair<Double, () -> Unit>>()
    private var shakePower = 0f
    var shakeX = 0f
        private set
    var shakeY = 0f
        private set
    var quality = 1f
    private val rnd = Random(7)

    val busy get() = particles.isNotEmpty() || rings.isNotEmpty() || beams.isNotEmpty() || flyers.isNotEmpty() ||
        texts.isNotEmpty() || flashes.isNotEmpty() || scheduled.isNotEmpty() || shakePower > 0.05f

    class Particle(
        val image: ImageBitmap?, var x: Float, var y: Float, var vx: Float, var vy: Float,
        val gravity: Float, var rot: Float, val rotSpeed: Float, val size: Float, val life: Float,
        val tint: Color?, val additive: Boolean, val drag: Float = 0f, val grow: Float = 0f,
    ) { var age = 0f }

    class Ring(val x: Float, val y: Float, val maxR: Float, val life: Float, val color: Color, val width: Float) { var age = 0f }
    class Beam(val a: Offset, val b: Offset, val life: Float, val color: Color, val seed: Int) { var age = 0f }
    class Flash(val x: Float, val y: Float, val r: Float, val life: Float, val color: Color) { var age = 0f }
    class FloatText(val text: String, val x: Float, val y: Float, val color: Color, val life: Float, val big: Boolean) { var age = 0f }
    class Flyer(
        val image: ImageBitmap?, val from: Offset, val to: Offset, val ctrl: Offset, val size: Float, val life: Float,
        val trail: Color, val onArrive: (() -> Unit)?,
    ) { var age = 0f; var arrived = false }

    fun at(delayMs: Double, action: () -> Unit) {
        if (delayMs <= 0) action() else scheduled += (timeMs + delayMs) to action
    }

    fun clear() {
        particles.clear(); rings.clear(); beams.clear(); flyers.clear(); texts.clear(); flashes.clear(); scheduled.clear(); shakePower = 0f
    }

    private fun budget(n: Int) = (n * quality).toInt().coerceAtLeast(1)

    // ------------------------------------------------------------------ emitters

    /** Colourful burst when a tile pops: its own themed bits plus sparkles. */
    fun burst(x: Float, y: Float, cell: Float, color: Color, bit: ImageBitmap?, count: Int = 7) {
        flashes += Flash(x, y, cell * 0.75f, 0.18f, Color.White)
        repeat(budget(count)) {
            val a = rnd.nextFloat() * 2 * PI.toFloat()
            val sp = cell * (2.2f + rnd.nextFloat() * 3.5f)
            particles += Particle(bit, x, y, cos(a) * sp, sin(a) * sp - cell * 2f, cell * 9f, rnd.nextFloat() * 360f,
                (rnd.nextFloat() - 0.5f) * 720f, cell * (0.22f + rnd.nextFloat() * 0.18f), 0.55f + rnd.nextFloat() * 0.35f, null, false, drag = 1.2f)
        }
        repeat(budget(5)) {
            val a = rnd.nextFloat() * 2 * PI.toFloat()
            val sp = cell * (1.5f + rnd.nextFloat() * 2.5f)
            particles += Particle(Sprites["sparkle"], x, y, cos(a) * sp, sin(a) * sp, 0f, 0f, 180f,
                cell * (0.25f + rnd.nextFloat() * 0.25f), 0.35f + rnd.nextFloat() * 0.25f, color, true, drag = 2.5f)
        }
    }

    /** Broken obstacle pieces that tumble down under gravity. */
    fun debris(x: Float, y: Float, cell: Float, image: ImageBitmap?, tint: Color?, count: Int, smoke: Boolean) {
        repeat(budget(count)) {
            val a = -PI.toFloat() / 2 + (rnd.nextFloat() - 0.5f) * 2.6f
            val sp = cell * (3f + rnd.nextFloat() * 4f)
            particles += Particle(image, x + (rnd.nextFloat() - 0.5f) * cell * 0.5f, y + (rnd.nextFloat() - 0.5f) * cell * 0.5f,
                cos(a) * sp, sin(a) * sp, cell * 14f, rnd.nextFloat() * 360f, (rnd.nextFloat() - 0.5f) * 900f,
                cell * (0.28f + rnd.nextFloat() * 0.22f), 0.8f + rnd.nextFloat() * 0.4f, tint, false, drag = 0.4f)
        }
        if (smoke) repeat(budget(3)) {
            particles += Particle(Sprites["p_smoke"], x + (rnd.nextFloat() - 0.5f) * cell * 0.6f, y, (rnd.nextFloat() - 0.5f) * cell, -cell * 0.8f,
                0f, rnd.nextFloat() * 360f, 30f, cell * 0.5f, 0.7f, Color(0xCCFFFFFF), false, drag = 1f, grow = 1.4f)
        }
    }

    fun glow(x: Float, y: Float, radius: Float, color: Color, life: Float = 0.35f) {
        flashes += Flash(x, y, radius, life, color)
    }

    fun ring(x: Float, y: Float, maxR: Float, color: Color = Color.White, life: Float = 0.45f, width: Float = 18f) {
        rings += Ring(x, y, maxR, life, color, width)
    }

    fun beam(a: Offset, b: Offset, color: Color = Color(0xFFFFF3C4), life: Float = 0.35f) {
        beams += Beam(a, b, life, color, rnd.nextInt())
    }

    fun shake(power: Float) {
        shakePower = maxOf(shakePower, power)
    }

    /** A rocket flying along a line, leaving a glowing trail. */
    fun rocket(from: Offset, to: Offset, image: ImageBitmap?, cell: Float, durationS: Float) {
        flyers += Flyer(image, from, to, (from + to) / 2f, cell * 0.9f, durationS, Color(0xFFFFD27F), null)
    }

    /** Collected item flying in an arc to a HUD target. */
    fun fly(image: ImageBitmap?, from: Offset, to: Offset, size: Float, durationS: Float = 0.55f, trail: Color = Color(0xFFFFF3C4), onArrive: (() -> Unit)? = null) {
        val mid = (from + to) / 2f
        val lift = hypot(to.x - from.x, to.y - from.y) * 0.35f
        val ctrl = Offset(mid.x + (if (from.x < to.x) -lift else lift) * 0.4f, mid.y - lift)
        flyers += Flyer(image, from, to, ctrl, size, durationS, trail, onArrive)
    }

    fun text(text: String, x: Float, y: Float, color: Color, big: Boolean = true) {
        texts.clear()
        texts += FloatText(text, x, y, color, 1.1f, big)
    }

    fun confetti(width: Float, height: Float, cell: Float) {
        val bits = listOf("p_petal", "p_leaf", "p_coin", "sparkle")
        repeat(budget(90)) {
            val img = Sprites[bits[it % bits.size]]
            particles += Particle(img, rnd.nextFloat() * width, -cell * rnd.nextFloat() * 3f, (rnd.nextFloat() - 0.5f) * cell * 2f,
                cell * (1f + rnd.nextFloat() * 2f), cell * 2.2f, rnd.nextFloat() * 360f, (rnd.nextFloat() - 0.5f) * 400f,
                cell * (0.35f + rnd.nextFloat() * 0.25f), 3f + rnd.nextFloat() * 1.5f,
                if (img == Sprites["sparkle"]) Color(0xFFFFE08A) else null, img == Sprites["sparkle"], drag = 0.6f)
        }
    }

    // ------------------------------------------------------------------ simulation

    fun tick(dtS: Float) {
        val dt = dtS.coerceIn(0f, 0.05f)
        timeMs += dt * 1000.0
        if (scheduled.isNotEmpty()) {
            val due = scheduled.filter { it.first <= timeMs }
            if (due.isNotEmpty()) {
                scheduled.removeAll(due.toSet())
                due.forEach { it.second() }
            }
        }
        val pit = particles.iterator()
        while (pit.hasNext()) {
            val p = pit.next()
            p.age += dt
            if (p.age >= p.life) { pit.remove(); continue }
            val damp = exp(-p.drag * dt)
            p.vx *= damp; p.vy = p.vy * damp + p.gravity * dt
            p.x += p.vx * dt; p.y += p.vy * dt
            p.rot += p.rotSpeed * dt
        }
        if (particles.size > 700) particles.subList(0, particles.size - 700).clear()
        rings.removeAll { it.age += dt; it.age >= it.life }
        beams.removeAll { it.age += dt; it.age >= it.life }
        flashes.removeAll { it.age += dt; it.age >= it.life }
        texts.removeAll { it.age += dt; it.age >= it.life }
        val fit = flyers.iterator()
        while (fit.hasNext()) {
            val f = fit.next()
            f.age += dt
            if (f.age >= f.life) {
                if (!f.arrived) { f.arrived = true; f.onArrive?.invoke() }
                fit.remove()
            }
        }
        shakePower *= exp(-9f * dt)
        if (shakePower < 0.05f) shakePower = 0f
        shakeX = if (shakePower > 0f) (rnd.nextFloat() - 0.5f) * 2f * shakePower else 0f
        shakeY = if (shakePower > 0f) (rnd.nextFloat() - 0.5f) * 2f * shakePower else 0f
        frame.longValue++
    }

    // ------------------------------------------------------------------ drawing

    fun draw(scope: DrawScope, text: TextMeasurer) = with(scope) {
        for (f in flashes) {
            val t = f.age / f.life
            val r = f.r * (0.6f + 0.6f * t)
            drawImageCentered(Sprites["glow"], Offset(f.x, f.y), r * 2, (1f - t) * 0.9f, f.color, true)
        }
        for (r in rings) {
            val t = r.age / r.life
            val e = 1f - (1f - t) * (1f - t)
            drawCircle(r.color.copy(alpha = (1f - t) * 0.85f), r.maxR * e, Offset(r.x, r.y), style = Stroke(r.width * (1f - t) + 2f))
        }
        for (b in beams) drawBeam(b)
        for (p in particles) {
            val t = p.age / p.life
            val alpha = if (t < 0.7f) 1f else (1f - t) / 0.3f
            val size = p.size * (1f + p.grow * t)
            if (p.rot != 0f) {
                rotate(p.rot, Offset(p.x, p.y)) { drawImageCentered(p.image, Offset(p.x, p.y), size, alpha, p.tint, p.additive) }
            } else drawImageCentered(p.image, Offset(p.x, p.y), size, alpha, p.tint, p.additive)
        }
        for (f in flyers) {
            val t = (f.age / f.life).coerceIn(0f, 1f)
            val e = t * t * (3 - 2 * t)
            val pos = bezier(f.from, f.ctrl, f.to, e)
            for (k in 1..4) {
                val tt = (e - k * 0.04f).coerceAtLeast(0f)
                drawImageCentered(Sprites["glow"], bezier(f.from, f.ctrl, f.to, tt), f.size * (0.9f - k * 0.15f), 0.35f - k * 0.07f, f.trail, true)
            }
            val dir = bezier(f.from, f.ctrl, f.to, (e + 0.02f).coerceAtMost(1f)) - pos
            val angle = if (f.onArrive == null && (dir.x != 0f || dir.y != 0f)) Math.toDegrees(kotlin.math.atan2(dir.y, dir.x).toDouble()).toFloat() else 0f
            val s = f.size * (if (f.onArrive != null) 1f - 0.35f * e else 1f)
            rotate(angle, pos) { drawImageCentered(f.image, pos, s, 1f, null, false) }
        }
        for (tx in texts) {
            val t = tx.age / tx.life
            val pop = if (t < 0.18f) 0.4f + 0.75f * (t / 0.18f) else if (t < 0.28f) 1.15f - 0.15f * ((t - 0.18f) / 0.1f) else 1f
            val alpha = if (t > 0.75f) (1f - t) / 0.25f else 1f
            val style = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Black, fontSize = if (tx.big) 40.sp else 26.sp,
                color = tx.color.copy(alpha = alpha), textAlign = TextAlign.Center)
            val layout = text.measure(tx.text, style, constraints = Constraints(maxWidth = size.width.toInt().coerceAtLeast(1)))
            withTransform({ scale(pop, pop, Offset(tx.x, tx.y - t * 40f)) }) {
                val tl = Offset(tx.x - layout.size.width / 2f, tx.y - t * 40f - layout.size.height / 2f)
                // Outline by drawing the text offset in dark ink around the main text.
                for ((dx, dy) in listOf(-3f to 0f, 3f to 0f, 0f to -3f, 0f to 3f, 0f to 5f)) {
                    drawText(text.measure(tx.text, style.copy(color = Color(0xFF3B1F35).copy(alpha = alpha)), constraints = Constraints(maxWidth = size.width.toInt().coerceAtLeast(1))), topLeft = tl + Offset(dx, dy))
                }
                drawText(layout, topLeft = tl)
            }
        }
    }

    private fun bezier(a: Offset, c: Offset, b: Offset, t: Float): Offset {
        val u = 1 - t
        return a * (u * u) + c * (2 * u * t) + b * (t * t)
    }

    private fun DrawScope.drawBeam(b: Beam) {
        val t = b.age / b.life
        val alpha = if (t < 0.2f) t / 0.2f else 1f - (t - 0.2f) / 0.8f
        val rr = Random(b.seed + (b.age * 30).toInt())
        val path = Path()
        val segs = 9
        val dx = b.b.x - b.a.x; val dy = b.b.y - b.a.y
        val len = hypot(dx, dy).coerceAtLeast(1f)
        val nx = -dy / len; val ny = dx / len
        path.moveTo(b.a.x, b.a.y)
        for (i in 1 until segs) {
            val f = i / segs.toFloat()
            val off = (rr.nextFloat() - 0.5f) * len * 0.12f
            path.lineTo(b.a.x + dx * f + nx * off, b.a.y + dy * f + ny * off)
        }
        path.lineTo(b.b.x, b.b.y)
        drawPath(path, b.color.copy(alpha = alpha * 0.45f), style = Stroke(16f, cap = StrokeCap.Round), blendMode = BlendMode.Plus)
        drawPath(path, Color.White.copy(alpha = alpha), style = Stroke(4f, cap = StrokeCap.Round))
        drawImageCentered(Sprites["glow"], b.b, 70f, alpha, b.color, true)
    }
}

fun DrawScope.drawImageCentered(img: ImageBitmap?, c: Offset, size: Float, alpha: Float, tint: Color?, additive: Boolean) {
    if (img == null || alpha <= 0.01f || size <= 0.5f) return
    val w = size
    val h = size * img.height / img.width.coerceAtLeast(1)
    drawImage(
        img,
        srcOffset = androidx.compose.ui.unit.IntOffset.Zero,
        srcSize = androidx.compose.ui.unit.IntSize(img.width, img.height),
        dstOffset = androidx.compose.ui.unit.IntOffset((c.x - w / 2).toInt(), (c.y - h / 2).toInt()),
        dstSize = androidx.compose.ui.unit.IntSize(w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1)),
        alpha = alpha.coerceIn(0f, 1f),
        colorFilter = tint?.let { ColorFilter.tint(it, BlendMode.Modulate) },
        blendMode = if (additive) BlendMode.Plus else BlendMode.SrcOver,
    )
}

/** Fills a [Size] box keeping the image's aspect ratio when [fit] is true. */
fun DrawScope.drawImageIn(img: ImageBitmap?, topLeft: Offset, box: Size, alpha: Float = 1f) {
    if (img == null || alpha <= 0.01f) return
    drawImage(
        img,
        srcOffset = androidx.compose.ui.unit.IntOffset.Zero,
        srcSize = androidx.compose.ui.unit.IntSize(img.width, img.height),
        dstOffset = androidx.compose.ui.unit.IntOffset(topLeft.x.toInt(), topLeft.y.toInt()),
        dstSize = androidx.compose.ui.unit.IntSize(box.width.toInt().coerceAtLeast(1), box.height.toInt().coerceAtLeast(1)),
        alpha = alpha.coerceIn(0f, 1f),
    )
}
