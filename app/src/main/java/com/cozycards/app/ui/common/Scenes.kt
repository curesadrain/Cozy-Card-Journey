package com.cozycards.app.ui.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import com.cozycards.engine.Rng
import kotlin.math.PI
import kotlin.math.sin

enum class Skyline { SHOPS, GARDEN, CAFE, FESTIVAL, BAMBOO, MARKET, HILL, COAST, TEMPLE, CITY, HANOK, ALLEY, STATION, ONSEN, BOOKS, ROOFTOP, PIER, POND, SHRINE, HOME }
enum class Particles { PETALS, LEAVES, SNOW, FIREFLIES, RAIN, NONE }

data class ChapterLook(
    val skyTop: Color,
    val skyBottom: Color,
    val ground: Color,
    val accent: Color,
    val night: Boolean,
    val skyline: Skyline,
    val particles: Particles,
)

/** Visual identity of each chapter (pairs with com.cozycards.levels.Chapters). */
object ChapterLooks {
    private fun c(v: Long) = Color(v)
    val all = listOf(
        ChapterLook(c(0xFFFFD9E6), c(0xFFFFF2E6), c(0xFFE7B9C9), c(0xFFD9467C), false, Skyline.SHOPS, Particles.PETALS),
        ChapterLook(c(0xFFD6EBD8), c(0xFFF4F6E8), c(0xFF9CC29A), c(0xFF4E8A5A), false, Skyline.GARDEN, Particles.PETALS),
        ChapterLook(c(0xFFF2DCC5), c(0xFFFFF5EA), c(0xFFC9A27E), c(0xFF8A5A3C), false, Skyline.CAFE, Particles.NONE),
        ChapterLook(c(0xFF2B2150), c(0xFF6D4C8F), c(0xFF3A2A55), c(0xFFF08A4B), true, Skyline.FESTIVAL, Particles.FIREFLIES),
        ChapterLook(c(0xFFCFE8C8), c(0xFFF1F8E8), c(0xFF6E9C5E), c(0xFF3F7A3A), false, Skyline.BAMBOO, Particles.LEAVES),
        ChapterLook(c(0xFF1B2140), c(0xFF424B7A), c(0xFF262B4C), c(0xFFC9D6FF), true, Skyline.MARKET, Particles.FIREFLIES),
        ChapterLook(c(0xFFFFE1EC), c(0xFFFDF0F6), c(0xFFE8A7C0), c(0xFFC2457A), false, Skyline.HILL, Particles.PETALS),
        ChapterLook(c(0xFFBFE3F2), c(0xFFF0F8FB), c(0xFF79B4C9), c(0xFF2F6F8F), false, Skyline.COAST, Particles.NONE),
        ChapterLook(c(0xFFF7D3A8), c(0xFFFFF0DC), c(0xFFC7703F), c(0xFFB0412E), false, Skyline.TEMPLE, Particles.LEAVES),
        ChapterLook(c(0xFF1E2A4A), c(0xFF4A5C8C), c(0xFFDDE6F5), c(0xFFFFD27F), true, Skyline.CITY, Particles.SNOW),
        ChapterLook(c(0xFFE7E0D2), c(0xFFF8F4EC), c(0xFFA89272), c(0xFF5C4630), false, Skyline.HANOK, Particles.LEAVES),
        ChapterLook(c(0xFFEFD9C4), c(0xFFFCF4EA), c(0xFFB58E6B), c(0xFF7A4E33), false, Skyline.ALLEY, Particles.NONE),
        ChapterLook(c(0xFF141A33), c(0xFF33406E), c(0xFF20263F), c(0xFF8FD3FF), true, Skyline.STATION, Particles.FIREFLIES),
        ChapterLook(c(0xFFDDE7F0), c(0xFFF6F9FC), c(0xFFB7C6D4), c(0xFF5E7FA0), false, Skyline.ONSEN, Particles.SNOW),
        ChapterLook(c(0xFF9AA8B8), c(0xFFDCE3EA), c(0xFF6F7C8A), c(0xFF3C4A5A), false, Skyline.BOOKS, Particles.RAIN),
        ChapterLook(c(0xFF0F1433), c(0xFF3A3577), c(0xFF1A1D3D), c(0xFFFFE6A3), true, Skyline.ROOFTOP, Particles.FIREFLIES),
        ChapterLook(c(0xFF1A1340), c(0xFF5A2F6E), c(0xFF201A3A), c(0xFFFF9EC7), true, Skyline.PIER, Particles.FIREFLIES),
        ChapterLook(c(0xFFD3EEDC), c(0xFFF3FBF5), c(0xFF6FAF8F), c(0xFFE8743B), false, Skyline.POND, Particles.PETALS),
        ChapterLook(c(0xFFE3EAF3), c(0xFFFFFFFF), c(0xFFD2DCE8), c(0xFFB0412E), false, Skyline.SHRINE, Particles.SNOW),
        ChapterLook(c(0xFFFFE3D1), c(0xFFFFF6EC), c(0xFFF2B8A0), c(0xFFD9467C), false, Skyline.HOME, Particles.PETALS),
    )

    fun of(chapter: Int) = all[(chapter - 1).coerceIn(0, all.lastIndex)]

    /** Board theme cosmetics override the chapter look on the level screen. */
    val washiPaper = ChapterLook(c(0xFFF6EEDF), c(0xFFFFFBF2), c(0xFFD8C6A8), c(0xFFB0412E), false, Skyline.ALLEY, Particles.NONE)
    val nightMarket = ChapterLook(c(0xFF120F26), c(0xFF2E2950), c(0xFF1C1836), c(0xFFFFB04A), true, Skyline.MARKET, Particles.FIREFLIES)
    val pastelCafe = ChapterLook(c(0xFFFDE2E4), c(0xFFE2ECE9), c(0xFFCDB4DB), c(0xFF9A6FB0), false, Skyline.CAFE, Particles.PETALS)
}

/** Animated, procedurally drawn scene background. Cheap: a single Canvas. */
@Composable
fun SceneBackground(look: ChapterLook, modifier: Modifier = Modifier.fillMaxSize(), animate: Boolean = true, dim: Float = 0f) {
    val transition = rememberInfiniteTransition(label = "scene")
    val t by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(26_000, easing = LinearEasing), RepeatMode.Restart), label = "t")
    val seeds = remember(look) { List(26) { Rng(it * 7919L + look.skyline.ordinal).let { r -> Triple(r.nextDouble().toFloat(), r.nextDouble().toFloat(), r.nextDouble().toFloat()) } } }
    Canvas(modifier) {
        drawRect(Brush.verticalGradient(listOf(look.skyTop, look.skyBottom)))
        drawCelestial(look)
        drawSkyline(look)
        val time = if (animate) t else 0.3f
        drawParticles(look, seeds, time)
        if (dim > 0f) drawRect(Color.Black.copy(alpha = dim))
    }
}

private val DrawScope.w get() = size.width
private val DrawScope.h get() = size.height

private fun DrawScope.drawCelestial(look: ChapterLook) {
    if (look.night) {
        val r = Rng(look.skyline.ordinal.toLong())
        repeat(50) {
            drawCircle(Color.White.copy(alpha = 0.3f + r.nextDouble().toFloat() * 0.6f), radius = 1f + r.nextDouble().toFloat() * 2.2f,
                center = Offset(r.nextDouble().toFloat() * w, r.nextDouble().toFloat() * h * 0.55f))
        }
        drawCircle(Brush.radialGradient(listOf(Color(0x66FFF3C4), Color.Transparent), Offset(w * 0.78f, h * 0.14f), w * 0.22f), w * 0.22f, Offset(w * 0.78f, h * 0.14f))
        drawCircle(Color(0xFFFFF3C4), w * 0.07f, Offset(w * 0.78f, h * 0.14f))
    } else {
        drawCircle(Brush.radialGradient(listOf(Color(0x88FFFFFF), Color.Transparent), Offset(w * 0.2f, h * 0.12f), w * 0.35f), w * 0.35f, Offset(w * 0.2f, h * 0.12f))
        drawCircle(Color(0xCCFFF8E6), w * 0.08f, Offset(w * 0.2f, h * 0.12f))
    }
}

private fun DrawScope.roof(x: Float, base: Float, width: Float, height: Float, color: Color) {
    val p = Path().apply {
        moveTo(x - width * 0.12f, base - height * 0.55f)
        quadraticBezierTo(x + width * 0.5f, base - height * 0.85f, x + width * 1.12f, base - height * 0.55f)
        lineTo(x + width, base - height * 0.5f)
        lineTo(x + width, base); lineTo(x, base); lineTo(x, base - height * 0.5f); close()
    }
    drawPath(p, color)
}

private fun DrawScope.lanternString(y: Float, color: Color, count: Int) {
    val path = Path().apply { moveTo(0f, y); quadraticBezierTo(w / 2, y + h * 0.04f, w, y) }
    drawPath(path, Color.Black.copy(alpha = 0.35f), style = Stroke(2f))
    for (i in 0 until count) {
        val x = (i + 0.5f) * w / count
        val sag = (1 - ((x - w / 2) / (w / 2)).let { it * it }) * h * 0.02f
        drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.5f), Color.Transparent), Offset(x, y + sag + 18f), 40f), 40f, Offset(x, y + sag + 18f))
        drawOval(color, Offset(x - 11f, y + sag + 4f), Size(22f, 28f))
    }
}

private fun DrawScope.drawSkyline(look: ChapterLook) {
    val ground = look.ground
    val far = ground.copy(alpha = 0.45f)
    val base = h * 0.78f
    val rng = Rng(look.skyline.ordinal * 31L + 5)
    // Distant hills
    val hills = Path().apply {
        moveTo(0f, base - h * 0.08f)
        var x = 0f
        while (x <= w) { quadraticBezierTo(x + w * 0.12f, base - h * (0.12f + rng.nextDouble().toFloat() * 0.08f), x + w * 0.25f, base - h * 0.08f); x += w * 0.25f }
        lineTo(w, h); lineTo(0f, h); close()
    }
    drawPath(hills, far)
    when (look.skyline) {
        Skyline.BAMBOO -> for (i in 0 until 14) {
            val x = i * w / 13f + rng.nextInt(20)
            drawRect(ground.copy(alpha = 0.8f), Offset(x, h * 0.2f), Size(10f, base))
            for (k in 0 until 8) drawRect(ground.copy(alpha = 0.9f), Offset(x - 2f, h * 0.22f + k * h * 0.08f), Size(14f, 3f))
        }
        Skyline.TEMPLE, Skyline.SHRINE -> {
            val tw = w * 0.5f
            val x = w * 0.25f
            drawRect(look.accent, Offset(x + tw * 0.08f, base - h * 0.2f), Size(12f, h * 0.2f))
            drawRect(look.accent, Offset(x + tw * 0.88f, base - h * 0.2f), Size(12f, h * 0.2f))
            drawRect(look.accent, Offset(x - tw * 0.04f, base - h * 0.215f), Size(tw * 1.08f, 14f))
            drawRect(look.accent, Offset(x + tw * 0.02f, base - h * 0.17f), Size(tw * 0.96f, 9f))
        }
        Skyline.COAST, Skyline.PIER, Skyline.POND -> {
            for (k in 0 until 5) {
                val y = base + k * 18f
                val p = Path().apply { moveTo(0f, y); var x = 0f; while (x < w) { quadraticBezierTo(x + 30f, y - 10f, x + 60f, y); x += 60f } }
                drawPath(p, Color.White.copy(alpha = 0.35f), style = Stroke(3f))
            }
        }
        else -> Unit
    }
    // Houses / shops in the foreground
    var x = -20f
    while (x < w) {
        val bw = w * (0.16f + rng.nextDouble().toFloat() * 0.12f)
        val bh = h * (0.1f + rng.nextDouble().toFloat() * 0.1f)
        roof(x, base + h * 0.02f, bw, bh * 2f, ground)
        // Glowing windows
        val win = if (look.night) Color(0xFFFFD27F) else Color.White.copy(alpha = 0.6f)
        drawRect(win, Offset(x + bw * 0.25f, base - bh * 0.6f), Size(bw * 0.18f, bh * 0.35f))
        drawRect(win, Offset(x + bw * 0.58f, base - bh * 0.6f), Size(bw * 0.18f, bh * 0.35f))
        x += bw * 1.05f
    }
    drawRect(ground, Offset(0f, base + h * 0.02f), Size(w, h))
    if (look.skyline in setOf(Skyline.FESTIVAL, Skyline.MARKET, Skyline.ALLEY, Skyline.SHOPS, Skyline.STATION, Skyline.ROOFTOP, Skyline.HOME)) {
        lanternString(h * 0.32f, look.accent, 7)
    }
}

private fun DrawScope.drawParticles(look: ChapterLook, seeds: List<Triple<Float, Float, Float>>, t: Float) {
    when (look.particles) {
        Particles.NONE -> Unit
        Particles.PETALS, Particles.LEAVES -> seeds.forEach { (a, b, c) ->
            val y = ((b + t * (0.6f + c)) % 1f) * h
            val x = (a * w + sin((t * 6 + c * 10) * PI.toFloat()) * 30f + t * w * 0.3f) % w
            val col = if (look.particles == Particles.PETALS) Color(0xFFFFB7CF) else Color(0xFFE59A4B)
            rotate(t * 720f * (c - 0.5f) + a * 360f, Offset(x, y)) {
                drawOval(col.copy(alpha = 0.85f), Offset(x - 7f, y - 4f), Size(14f, 8f))
            }
        }
        Particles.SNOW -> seeds.forEach { (a, b, c) ->
            val y = ((b + t * (0.5f + c * 0.6f)) % 1f) * h
            val x = a * w + sin((t * 8 + c * 6) * PI.toFloat()) * 18f
            drawCircle(Color.White.copy(alpha = 0.85f), 2.5f + c * 3f, Offset(x, y))
        }
        Particles.FIREFLIES -> seeds.forEach { (a, b, c) ->
            val x = a * w + sin((t * 4 + c * 9) * PI.toFloat()) * 26f
            val y = h * (0.35f + b * 0.5f) + sin((t * 3 + a * 7) * PI.toFloat()) * 20f
            val glow = 0.4f + 0.6f * ((sin((t * 10 + c * 13) * PI.toFloat()) + 1f) / 2f)
            drawCircle(Brush.radialGradient(listOf(look.accent.copy(alpha = 0.5f * glow), Color.Transparent), Offset(x, y), 18f), 18f, Offset(x, y))
            drawCircle(Color(0xFFFFF3C4).copy(alpha = glow), 2.5f, Offset(x, y))
        }
        Particles.RAIN -> seeds.forEach { (a, b, c) ->
            val y = ((b + t * 6f * (0.8f + c)) % 1f) * h
            val x = a * w
            drawLine(Color.White.copy(alpha = 0.4f), Offset(x, y), Offset(x - 6f, y + 26f), strokeWidth = 2f)
        }
    }
}
