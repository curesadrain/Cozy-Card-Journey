package com.cozycards.app.ui.level

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import com.cozycards.app.ui.common.drawBombGlyph
import com.cozycards.app.ui.common.drawRainbowGlyph
import com.cozycards.app.ui.common.drawStar
import com.cozycards.app.ui.theme.Palette
import com.cozycards.engine.Blocker
import com.cozycards.engine.BlockerType
import com.cozycards.engine.Piece
import com.cozycards.engine.PieceKind
import com.cozycards.engine.Special
import kotlin.math.cos
import kotlin.math.sin

/** Tile colours with distinct shapes (colour-blind friendly). */
object TileStyle {
    val colors = listOf(
        Color(0xFFF48FB1), // sakura
        Color(0xFF81C784), // matcha leaf
        Color(0xFFFF9F59), // lantern
        Color(0xFFFFD54F), // moon
        Color(0xFF64B5F6), // wave drop
        Color(0xFFB39DDB), // plum candy
    )
    val names = listOf("Sakura", "Matcha", "Lantern", "Moon", "Droplet", "Plum")

    fun color(i: Int) = colors[i.coerceIn(0, colors.lastIndex)]
}

private fun Color.darker(f: Float = 0.75f) = Color(red * f, green * f, blue * f, alpha)
private fun Color.lighter(f: Float = 0.4f) = Color(red + (1 - red) * f, green + (1 - green) * f, blue + (1 - blue) * f, alpha)

/** Draws a piece centred at [c] in a cell of size [s]. */
fun DrawScope.drawPiece(piece: Piece, c: Offset, s: Float, alpha: Float = 1f, scaleF: Float = 1f) {
    if (alpha <= 0.01f || scaleF <= 0.01f) return
    scale(scaleF, c) {
        when {
            piece.kind == PieceKind.DROP -> drawCharm(c, s, alpha)
            piece.special == Special.RAINBOW -> {
                drawCircle(Color.White.copy(alpha = 0.35f * alpha), s * 0.46f, c)
                drawRainbowGlyph(c, s * 0.38f)
            }
            else -> {
                val col = TileStyle.color(piece.color)
                if (piece.special == Special.BOMB) {
                    drawCircle(col.copy(alpha = 0.45f * alpha), s * 0.46f, c)
                    drawBombGlyph(c + Offset(0f, s * 0.04f), s * 0.3f)
                    drawCircle(col.copy(alpha = alpha), s * 0.11f, c + Offset(-s * 0.08f, -s * 0.04f))
                } else if (piece.special == Special.PLANE) {
                    drawCircle(col.copy(alpha = 0.5f * alpha), s * 0.44f, c)
                    drawPlane(c, s * 0.36f, Color.White.copy(alpha = alpha), col.darker())
                } else {
                    drawGlyph(piece.color, c, s, col, alpha)
                    if (piece.special.isRocket) drawRocketMarks(c, s, piece.special == Special.ROCKET_H, alpha)
                }
                if (piece.kind == PieceKind.KEY) drawKey(c + Offset(s * 0.2f, s * 0.2f), s * 0.18f, alpha)
            }
        }
    }
}

private fun DrawScope.drawGlyph(color: Int, c: Offset, s: Float, col: Color, alpha: Float) {
    val r = s * 0.4f
    // Soft drop shadow
    drawCircle(Color.Black.copy(alpha = 0.12f * alpha), r * 0.95f, c + Offset(0f, s * 0.04f))
    val brush = Brush.radialGradient(listOf(col.lighter(0.55f).copy(alpha = alpha), col.copy(alpha = alpha), col.darker(0.8f).copy(alpha = alpha)), c - Offset(r * 0.35f, r * 0.45f), r * 1.6f)
    when (color) {
        0 -> { // five-petal blossom
            for (i in 0 until 5) rotate(i * 72f, c) {
                drawOval(brush, Offset(c.x - r * 0.33f, c.y - r * 1.0f), Size(r * 0.66f, r * 0.85f))
            }
            drawCircle(Color(0xFFFFF3C4).copy(alpha = alpha), r * 0.24f, c)
        }
        1 -> { // leaf
            val p = Path().apply {
                moveTo(c.x - r * 0.85f, c.y + r * 0.6f)
                cubicTo(c.x - r * 0.9f, c.y - r * 0.6f, c.x + r * 0.2f, c.y - r * 1.0f, c.x + r * 0.9f, c.y - r * 0.85f)
                cubicTo(c.x + r * 0.9f, c.y + r * 0.1f, c.x + r * 0.2f, c.y + r * 0.9f, c.x - r * 0.85f, c.y + r * 0.6f)
            }
            drawPath(p, brush)
            drawLine(col.darker(0.6f).copy(alpha = alpha), Offset(c.x - r * 0.7f, c.y + r * 0.5f), Offset(c.x + r * 0.6f, c.y - r * 0.6f), s * 0.035f)
        }
        2 -> { // lantern
            drawOval(brush, Offset(c.x - r * 0.75f, c.y - r * 0.85f), Size(r * 1.5f, r * 1.7f))
            for (k in -1..1) drawLine(col.darker(0.6f).copy(alpha = 0.6f * alpha), Offset(c.x - r * 0.7f, c.y + k * r * 0.45f), Offset(c.x + r * 0.7f, c.y + k * r * 0.45f), s * 0.02f)
            drawRect(Color(0xFF3B2A20).copy(alpha = alpha), Offset(c.x - r * 0.3f, c.y - r * 0.98f), Size(r * 0.6f, r * 0.18f))
            drawRect(Color(0xFF3B2A20).copy(alpha = alpha), Offset(c.x - r * 0.3f, c.y + r * 0.8f), Size(r * 0.6f, r * 0.18f))
        }
        3 -> { // crescent moon
            val p = Path().apply {
                addOval(androidx.compose.ui.geometry.Rect(c.x - r, c.y - r, c.x + r, c.y + r))
            }
            val cut = Path().apply { addOval(androidx.compose.ui.geometry.Rect(c.x - r * 0.35f, c.y - r * 1.15f, c.x + r * 1.35f, c.y + r * 0.55f)) }
            val moon = Path.combine(androidx.compose.ui.graphics.PathOperation.Difference, p, cut)
            drawPath(moon, brush)
            drawStar(c + Offset(r * 0.45f, r * 0.15f), r * 0.28f, Color.White.copy(alpha = 0.9f * alpha))
        }
        4 -> { // droplet
            val p = Path().apply {
                moveTo(c.x, c.y - r * 1.05f)
                cubicTo(c.x + r * 0.35f, c.y - r * 0.5f, c.x + r * 0.9f, c.y, c.x + r * 0.8f, c.y + r * 0.35f)
                cubicTo(c.x + r * 0.7f, c.y + r * 1.05f, c.x - r * 0.7f, c.y + r * 1.05f, c.x - r * 0.8f, c.y + r * 0.35f)
                cubicTo(c.x - r * 0.9f, c.y, c.x - r * 0.35f, c.y - r * 0.5f, c.x, c.y - r * 1.05f)
            }
            drawPath(p, brush)
        }
        else -> { // plum candy (konpeito star)
            val p = Path()
            for (i in 0 until 12) {
                val a = Math.PI / 6 * i - Math.PI / 2
                val rr = if (i % 2 == 0) r else r * 0.72f
                val x = c.x + (cos(a) * rr).toFloat(); val y = c.y + (sin(a) * rr).toFloat()
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            p.close()
            drawPath(p, brush)
        }
    }
    // Glossy highlight
    drawOval(Color.White.copy(alpha = 0.45f * alpha), Offset(c.x - r * 0.45f, c.y - r * 0.65f), Size(r * 0.45f, r * 0.25f))
}

private fun DrawScope.drawRocketMarks(c: Offset, s: Float, horizontal: Boolean, alpha: Float) {
    val r = s * 0.42f
    rotate(if (horizontal) 0f else 90f, c) {
        for (k in -1..1) drawLine(Color.White.copy(alpha = 0.85f * alpha), Offset(c.x - r * 0.6f, c.y + k * r * 0.28f), Offset(c.x + r * 0.6f, c.y + k * r * 0.28f), s * 0.045f)
        val p = Path().apply {
            moveTo(c.x + r * 1.05f, c.y); lineTo(c.x + r * 0.7f, c.y - r * 0.3f); lineTo(c.x + r * 0.7f, c.y + r * 0.3f); close()
            moveTo(c.x - r * 1.05f, c.y); lineTo(c.x - r * 0.7f, c.y - r * 0.3f); lineTo(c.x - r * 0.7f, c.y + r * 0.3f); close()
        }
        drawPath(p, Color.White.copy(alpha = alpha))
    }
}

fun DrawScope.drawPlane(c: Offset, r: Float, fill: Color, line: Color) {
    val p = Path().apply {
        moveTo(c.x + r, c.y - r * 0.6f)
        lineTo(c.x - r, c.y + r * 0.05f)
        lineTo(c.x - r * 0.2f, c.y + r * 0.25f)
        lineTo(c.x + r * 0.05f, c.y + r * 0.9f)
        lineTo(c.x + r * 0.3f, c.y + r * 0.4f)
        close()
    }
    drawPath(p, fill)
    drawPath(p, line, style = Stroke(r * 0.08f))
    drawLine(line, Offset(c.x + r, c.y - r * 0.6f), Offset(c.x - r * 0.2f, c.y + r * 0.25f), r * 0.06f)
}

private fun DrawScope.drawKey(c: Offset, r: Float, alpha: Float) {
    val gold = Palette.Gold.copy(alpha = alpha)
    drawCircle(Color.White.copy(alpha = 0.9f * alpha), r * 1.05f, c)
    drawCircle(gold, r * 0.4f, c - Offset(r * 0.35f, 0f), style = Stroke(r * 0.2f))
    drawLine(gold, c, c + Offset(r * 0.7f, 0f), r * 0.2f)
    drawLine(gold, c + Offset(r * 0.5f, 0f), c + Offset(r * 0.5f, r * 0.3f), r * 0.18f)
}

private fun DrawScope.drawCharm(c: Offset, s: Float, alpha: Float) {
    val r = s * 0.38f
    drawCircle(Color(0x55FFD54F).copy(alpha = 0.4f * alpha), s * 0.48f, c)
    drawRoundRect(Brush.verticalGradient(listOf(Color(0xFFE5484D), Color(0xFFB0262C)), c.y - r, c.y + r), Offset(c.x - r * 0.7f, c.y - r * 0.8f), Size(r * 1.4f, r * 1.8f), CornerRadius(r * 0.3f), alpha = alpha)
    drawRect(Palette.Gold.copy(alpha = alpha), Offset(c.x - r * 0.7f, c.y - r * 0.45f), Size(r * 1.4f, r * 0.12f))
    drawCircle(Palette.Gold.copy(alpha = alpha), r * 0.18f, c + Offset(0f, r * 0.2f))
    drawLine(Palette.Gold.copy(alpha = alpha), c + Offset(0f, -r * 0.8f), c + Offset(0f, -r * 1.15f), r * 0.1f)
}

fun DrawScope.drawIce(topLeft: Offset, s: Float, layers: Int, alpha: Float = 1f) {
    if (layers <= 0) return
    val a = (if (layers >= 2) 0.62f else 0.4f) * alpha
    drawRoundRect(Brush.linearGradient(listOf(Color(0xFFE6F7FF).copy(alpha = a), Color(0xFF9ED8F5).copy(alpha = a)), topLeft, topLeft + Offset(s, s)),
        topLeft + Offset(s * 0.04f, s * 0.04f), Size(s * 0.92f, s * 0.92f), CornerRadius(s * 0.16f))
    val crack = Color.White.copy(alpha = 0.8f * alpha)
    drawLine(crack, topLeft + Offset(s * 0.15f, s * 0.25f), topLeft + Offset(s * 0.4f, s * 0.38f), s * 0.025f)
    drawLine(crack, topLeft + Offset(s * 0.4f, s * 0.38f), topLeft + Offset(s * 0.35f, s * 0.6f), s * 0.025f)
    if (layers >= 2) {
        drawLine(crack, topLeft + Offset(s * 0.7f, s * 0.15f), topLeft + Offset(s * 0.6f, s * 0.45f), s * 0.025f)
        drawLine(crack, topLeft + Offset(s * 0.6f, s * 0.45f), topLeft + Offset(s * 0.85f, s * 0.7f), s * 0.025f)
        drawRoundRect(Color.White.copy(alpha = 0.6f * alpha), topLeft + Offset(s * 0.08f, s * 0.08f), Size(s * 0.84f, s * 0.84f), CornerRadius(s * 0.14f), style = Stroke(s * 0.035f))
    }
}

fun DrawScope.drawChain(topLeft: Offset, s: Float, layers: Int, alpha: Float = 1f) {
    if (layers <= 0) return
    val col = (if (layers >= 2) Color(0xFF6E6A7C) else Color(0xFF9A96A8)).copy(alpha = alpha)
    fun diagonal(flip: Boolean) {
        for (k in 0 until 4) {
            val t = (k + 0.5f) / 4f
            val x = topLeft.x + s * (if (flip) 1 - t else t)
            val y = topLeft.y + s * t
            rotate(if (flip) -45f else 45f, Offset(x, y)) {
                drawOval(col, Offset(x - s * 0.12f, y - s * 0.07f), Size(s * 0.24f, s * 0.14f), style = Stroke(s * 0.05f))
            }
        }
    }
    diagonal(false)
    if (layers >= 2) diagonal(true)
}

fun DrawScope.drawWeb(topLeft: Offset, s: Float, alpha: Float = 1f) {
    val c = topLeft + Offset(s / 2, s / 2)
    val col = Color.White.copy(alpha = 0.85f * alpha)
    for (i in 0 until 8) {
        val a = Math.PI / 4 * i
        drawLine(col, c, c + Offset((cos(a) * s * 0.5f).toFloat(), (sin(a) * s * 0.5f).toFloat()), s * 0.02f)
    }
    for (ring in 1..3) drawCircle(col, s * 0.15f * ring, c, style = Stroke(s * 0.018f))
}

fun DrawScope.drawBlocker(b: Blocker, topLeft: Offset, s: Float, alpha: Float = 1f) {
    val inset = s * 0.05f
    val o = topLeft + Offset(inset, inset)
    val sz = Size(s - inset * 2, s - inset * 2)
    when (b.type) {
        BlockerType.BOX -> {
            val wood = when (b.hp) { 1 -> Color(0xFFD9A066); 2 -> Color(0xFFB0773E); else -> Color(0xFF8A5530) }.copy(alpha = alpha)
            drawRoundRect(wood, o, sz, CornerRadius(s * 0.08f))
            val line = Color(0xFF5C3A1E).copy(alpha = 0.6f * alpha)
            for (k in 1..2) drawLine(line, o + Offset(0f, sz.height * k / 3), o + Offset(sz.width, sz.height * k / 3), s * 0.03f)
            drawRoundRect(line, o, sz, CornerRadius(s * 0.08f), style = Stroke(s * 0.05f))
            if (b.hp >= 2) {
                val metal = Color(0xFFB8BCC8).copy(alpha = alpha)
                listOf(o, o + Offset(sz.width - s * 0.18f, 0f), o + Offset(0f, sz.height - s * 0.18f), o + Offset(sz.width - s * 0.18f, sz.height - s * 0.18f))
                    .forEach { drawRect(metal, it, Size(s * 0.18f, s * 0.18f)) }
            }
            if (b.hp >= 3) {
                drawLine(Color(0xFF3A2414).copy(alpha = alpha), o, o + Offset(sz.width, sz.height), s * 0.07f)
                drawLine(Color(0xFF3A2414).copy(alpha = alpha), o + Offset(sz.width, 0f), o + Offset(0f, sz.height), s * 0.07f)
            }
        }
        BlockerType.STONE -> {
            val col = (if (b.hp >= 2) Color(0xFF6F7280) else Color(0xFF9EA1AD)).copy(alpha = alpha)
            drawRoundRect(Brush.radialGradient(listOf(col.lighter(0.3f), col, col.darker(0.7f)), o + Offset(sz.width * 0.3f, sz.height * 0.3f), s), o, sz, CornerRadius(s * 0.3f))
            drawCircle(col.darker(0.6f), s * 0.05f, o + Offset(sz.width * 0.3f, sz.height * 0.6f))
            drawCircle(col.darker(0.6f), s * 0.04f, o + Offset(sz.width * 0.65f, sz.height * 0.35f))
            if (b.hp >= 2) drawLine(col.darker(0.5f), o + Offset(sz.width * 0.2f, sz.height * 0.2f), o + Offset(sz.width * 0.7f, sz.height * 0.8f), s * 0.03f)
        }
        BlockerType.LOCK -> {
            drawRoundRect(Color(0xFF3E3550).copy(alpha = alpha), o, sz, CornerRadius(s * 0.12f))
            val c = topLeft + Offset(s / 2, s / 2)
            drawArc(Palette.Gold.copy(alpha = alpha), 180f, 180f, false, Offset(c.x - s * 0.16f, c.y - s * 0.3f), Size(s * 0.32f, s * 0.32f), style = Stroke(s * 0.06f))
            drawRoundRect(Palette.Gold.copy(alpha = alpha), Offset(c.x - s * 0.24f, c.y - s * 0.12f), Size(s * 0.48f, s * 0.36f), CornerRadius(s * 0.06f))
            drawCircle(Color(0xFF3E3550).copy(alpha = alpha), s * 0.05f, c + Offset(0f, s * 0.04f))
        }
    }
}

fun DrawScope.drawPortal(c: Offset, s: Float, color: Color, t: Float) {
    rotate(t * 360f, c) {
        for (k in 0 until 3) drawArc(color.copy(alpha = 0.7f - k * 0.18f), k * 120f, 90f, false, Offset(c.x - s * (0.3f + k * 0.08f), c.y - s * (0.12f + k * 0.03f)), Size(s * (0.6f + k * 0.16f), s * (0.24f + k * 0.06f)), style = Stroke(s * 0.05f))
    }
}

fun DrawScope.drawConveyorArrow(c: Offset, s: Float, dx: Int, dy: Int) {
    val angle = when { dx > 0 -> 0f; dx < 0 -> 180f; dy > 0 -> 90f; else -> -90f }
    rotate(angle, c) {
        translate(0f, 0f) {
            val p = Path().apply {
                moveTo(c.x + s * 0.18f, c.y); lineTo(c.x - s * 0.06f, c.y - s * 0.16f); lineTo(c.x - s * 0.06f, c.y + s * 0.16f); close()
            }
            drawPath(p, Color.White.copy(alpha = 0.35f))
        }
    }
}
