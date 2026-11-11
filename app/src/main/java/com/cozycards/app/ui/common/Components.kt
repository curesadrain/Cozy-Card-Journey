package com.cozycards.app.ui.common

import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cozycards.app.cards.CARD_ASPECT
import com.cozycards.app.container
import com.cozycards.app.ui.gfx.drawImageCentered
import com.cozycards.app.ui.theme.Palette
import com.cozycards.game.cards.CardDef
import com.cozycards.game.cards.Rarity
import com.cozycards.game.economy.BoosterType
import com.cozycards.game.economy.Cosmetic
import com.cozycards.levels.LootboxTier
import kotlin.math.cos
import kotlin.math.sin

/** Stretches a pill-shaped sprite horizontally without distorting its rounded ends. */
fun androidx.compose.ui.graphics.drawscope.DrawScope.drawThreeSlice(img: androidx.compose.ui.graphics.ImageBitmap, alpha: Float) {
    val sh = img.height
    val cap = sh / 2
    val dh = size.height
    val dcap = (dh * cap / sh).coerceAtMost(size.width / 2)
    fun part(sx: Int, sw: Int, dx: Float, dw: Float) = drawImage(
        img, srcOffset = androidx.compose.ui.unit.IntOffset(sx, 0), srcSize = androidx.compose.ui.unit.IntSize(sw, sh),
        dstOffset = androidx.compose.ui.unit.IntOffset(dx.toInt(), 0), dstSize = androidx.compose.ui.unit.IntSize(dw.toInt().coerceAtLeast(1), dh.toInt()), alpha = alpha,
    )
    part(0, cap, 0f, dcap)
    part(cap, img.width - 2 * cap, dcap, size.width - 2 * dcap)
    part(img.width - cap, cap, size.width - dcap, dcap)
}

@Composable
fun CurrencyChip(kind: Currency, amount: Long, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(Palette.CreamPlate)
            .border(2.dp, Palette.GoldRim, RoundedCornerShape(50))
            .padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CurrencyIcon(kind, Modifier.size(22.dp))
        Spacer(Modifier.width(6.dp))
        Text(formatAmount(amount), style = MaterialTheme.typography.labelLarge, color = Palette.Ink)
    }
}

enum class Currency { COINS, TOKENS }

fun formatAmount(v: Long) = when {
    v >= 1_000_000 -> "%.1fM".format(v / 1_000_000.0)
    v >= 10_000 -> "%.1fk".format(v / 1000.0)
    else -> v.toString()
}

@Composable
fun CurrencyIcon(kind: Currency, modifier: Modifier = Modifier) {
    val sprite = com.cozycards.app.ui.gfx.Sprites[if (kind == Currency.COINS) "ui_coin" else "ui_token"]
    if (sprite != null) {
        Canvas(modifier) { drawImageCentered(sprite, center, size.minDimension * 1.15f, 1f, null, false) }
        return
    }
    Canvas(modifier) {
        val r = size.minDimension / 2
        if (kind == Currency.COINS) {
            drawCircle(Brush.radialGradient(listOf(Color(0xFFFFE7A0), Palette.Coin, Color(0xFFC8962C))), r)
            drawCircle(Color(0xFFB7862A), r * 0.62f, style = Stroke(r * 0.12f))
            drawRect(Color(0xFFB7862A), Offset(center.x - r * 0.18f, center.y - r * 0.18f), Size(r * 0.36f, r * 0.36f), style = Stroke(r * 0.1f))
        } else {
            val p = Path().apply {
                for (i in 0 until 6) {
                    val a = Math.PI / 3 * i - Math.PI / 2
                    val x = center.x + (cos(a) * r).toFloat(); val y = center.y + (sin(a) * r).toFloat()
                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                }
                close()
            }
            drawPath(p, Brush.linearGradient(listOf(Color(0xFFB8F0E8), Palette.Token, Color(0xFF3F8F88))))
            drawCircle(Color.White.copy(alpha = 0.8f), r * 0.28f)
        }
    }
}

@Composable
fun StarRow(stars: Int, size: Dp = 14.dp, max: Int = 3) {
    Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
        repeat(max) { i -> StarIcon(i < stars, Modifier.size(size)) }
    }
}

@Composable
fun StarIcon(filled: Boolean, modifier: Modifier = Modifier) {
    val sprite = com.cozycards.app.ui.gfx.Sprites["ui_star"]
    Canvas(modifier) {
        if (sprite != null) {
            if (filled) drawImageCentered(sprite, center, size.minDimension * 1.15f, 1f, null, false)
            else drawImageCentered(sprite, center, size.minDimension * 1.05f, 0.35f, Color(0xFF5B5068), false)
        } else drawStar(center, size.minDimension / 2, if (filled) Palette.Gold else Color.Black.copy(alpha = 0.18f))
    }
}

fun DrawScope.drawStar(c: Offset, r: Float, color: Color) {
    val p = Path()
    for (i in 0 until 10) {
        val a = Math.PI / 5 * i - Math.PI / 2
        val rr = if (i % 2 == 0) r else r * 0.45f
        val x = c.x + (cos(a) * rr).toFloat(); val y = c.y + (sin(a) * rr).toFloat()
        if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
    }
    p.close()
    drawPath(p, color)
}

@Composable
fun ProgressBar(progress: Float, modifier: Modifier = Modifier, color: Color = Palette.SakuraDeep) {
    LinearProgressIndicator(
        progress = { progress.coerceIn(0f, 1f) },
        modifier = modifier.height(8.dp).clip(RoundedCornerShape(50)),
        color = color,
        trackColor = Color.Black.copy(alpha = 0.08f),
    )
}

@Composable
fun RarityBadge(r: Rarity, modifier: Modifier = Modifier) {
    Text(
        r.label.uppercase(),
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Palette.rarity(r))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        color = Color.White,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, action: (@Composable RowScope.() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleLarge, color = Palette.Ink, modifier = Modifier.weight(1f))
        action?.invoke(this)
    }
}

/** Card thumbnail rendered asynchronously from the bitmap cache. */
@Composable
fun CardThumbnail(card: CardDef, owned: Boolean, modifier: Modifier = Modifier, widthPx: Int = 300, frame: Cosmetic = Cosmetic.FRAME_STANDARD, refreshKey: Any? = null) {
    val cache = LocalContext.current.container.bitmaps
    var bmp by remember(card.id, owned, widthPx, frame, refreshKey) { mutableStateOf<Bitmap?>(cache.peekThumbnail(card, widthPx, owned, frame)) }
    LaunchedEffect(card.id, owned, widthPx, frame, refreshKey) {
        if (bmp == null) bmp = cache.thumbnail(card, widthPx, owned, frame)
    }
    Box(modifier.aspectRatio(CARD_ASPECT)) {
        val b = bmp
        if (b != null) {
            Image(b.asImageBitmap(), card.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        } else {
            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.08f)))
        }
    }
}

@Composable
fun BoosterIcon(type: BoosterType, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val r = size.minDimension / 2
        drawCircle(Brush.radialGradient(listOf(Color.White, Color(0xFFFFE4EE))), r)
        drawCircle(Palette.SakuraDeep.copy(alpha = 0.35f), r, style = Stroke(r * 0.08f))
        when (type) {
            BoosterType.ROCKET -> drawRocketGlyph(center, r * 0.6f, Palette.Lantern, true)
            BoosterType.BOMB -> drawBombGlyph(center, r * 0.55f)
            BoosterType.RAINBOW -> drawRainbowGlyph(center, r * 0.55f)
            BoosterType.ROCKET_BOMB -> { drawRocketGlyph(center - Offset(r * 0.25f, 0f), r * 0.45f, Palette.Lantern, true); drawBombGlyph(center + Offset(r * 0.25f, r * 0.15f), r * 0.35f) }
            BoosterType.EXTRA_MOVES -> {
                drawCircle(Palette.Matcha, r * 0.5f)
                drawLine(Color.White, center - Offset(r * 0.25f, 0f), center + Offset(r * 0.25f, 0f), r * 0.12f)
                drawLine(Color.White, center - Offset(0f, r * 0.25f), center + Offset(0f, r * 0.25f), r * 0.12f)
            }
            BoosterType.HAMMER, BoosterType.SUPER_HAMMER -> rotate(-35f, center) {
                drawRect(Color(0xFF8A5A3C), Offset(center.x - r * 0.07f, center.y - r * 0.1f), Size(r * 0.14f, r * 0.7f))
                val headColor = if (type == BoosterType.SUPER_HAMMER) Palette.Gold else Color(0xFF8A8FA3)
                drawRect(headColor, Offset(center.x - r * 0.4f, center.y - r * 0.45f), Size(r * 0.8f, r * 0.35f))
            }
            BoosterType.SWAP_GLOVE -> {
                drawCircle(Palette.Lavender, r * 0.5f)
                drawLine(Palette.Ink, center + Offset(-r * 0.3f, -r * 0.12f), center + Offset(r * 0.3f, -r * 0.12f), r * 0.1f)
                drawLine(Palette.Ink, center + Offset(-r * 0.3f, r * 0.12f), center + Offset(r * 0.3f, r * 0.12f), r * 0.1f)
            }
            BoosterType.SHUFFLE -> {
                drawArc(Palette.Sky, 200f, 280f, false, Offset(center.x - r * 0.45f, center.y - r * 0.45f), Size(r * 0.9f, r * 0.9f), style = Stroke(r * 0.15f))
                drawCircle(Palette.Sky, r * 0.12f, center + Offset(r * 0.38f, -r * 0.2f))
            }
            BoosterType.AREA_CLEAR -> {
                for (i in 0 until 3) for (j in 0 until 3) drawRect(Palette.SakuraDeep.copy(alpha = 0.7f), Offset(center.x - r * 0.45f + i * r * 0.32f, center.y - r * 0.45f + j * r * 0.32f), Size(r * 0.26f, r * 0.26f))
            }
        }
    }
}

fun DrawScope.drawRocketGlyph(c: Offset, r: Float, color: Color, horizontal: Boolean) {
    rotate(if (horizontal) 0f else 90f, c) {
        val p = Path().apply {
            moveTo(c.x + r, c.y)
            lineTo(c.x + r * 0.3f, c.y - r * 0.4f); lineTo(c.x - r * 0.6f, c.y - r * 0.4f)
            lineTo(c.x - r * 0.9f, c.y - r * 0.7f); lineTo(c.x - r * 0.8f, c.y)
            lineTo(c.x - r * 0.9f, c.y + r * 0.7f); lineTo(c.x - r * 0.6f, c.y + r * 0.4f)
            lineTo(c.x + r * 0.3f, c.y + r * 0.4f); close()
        }
        drawPath(p, color)
        drawCircle(Color.White, r * 0.15f, Offset(c.x + r * 0.15f, c.y))
    }
}

fun DrawScope.drawBombGlyph(c: Offset, r: Float) {
    drawCircle(Brush.radialGradient(listOf(Color(0xFF6B6680), Color(0xFF2B2838)), c - Offset(r * 0.3f, r * 0.3f), r * 1.4f), r, c)
    drawLine(Color(0xFF8A5A3C), c + Offset(r * 0.5f, -r * 0.6f), c + Offset(r * 0.9f, -r * 1.0f), r * 0.18f)
    drawCircle(Palette.Lantern, r * 0.2f, c + Offset(r * 0.95f, -r * 1.05f))
}

fun DrawScope.drawRainbowGlyph(c: Offset, r: Float) {
    drawCircle(Brush.sweepGradient(listOf(Color(0xFFFF6B8A), Color(0xFFFFC75F), Color(0xFF8DE08B), Color(0xFF6BC5FF), Color(0xFFB98BFF), Color(0xFFFF6B8A)), c), r, c)
    drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.9f), Color.Transparent), c - Offset(r * 0.3f, r * 0.3f), r * 0.8f), r, c)
}

@Composable
fun LootboxArt(tier: LootboxTier, modifier: Modifier = Modifier, lidOpen: Float = 0f, glow: Float = 0f) {
    val color = when (tier) {
        LootboxTier.COMMON -> Color(0xFFE7B9C9)
        LootboxTier.RARE -> Color(0xFF8FB3DE)
        LootboxTier.EPIC -> Color(0xFFB18AE8)
        LootboxTier.LEGENDARY -> Color(0xFFF2C94C)
    }
    Canvas(modifier.aspectRatio(1f)) {
        val w = size.width
        if (glow > 0f) drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.7f * glow), Color.Transparent)), w * 0.6f)
        val bodyTop = w * 0.45f
        drawRoundRect(Brush.verticalGradient(listOf(color, color.copy(red = color.red * 0.75f, green = color.green * 0.75f, blue = color.blue * 0.75f)), bodyTop, w * 0.9f),
            Offset(w * 0.15f, bodyTop), Size(w * 0.7f, w * 0.42f), androidx.compose.ui.geometry.CornerRadius(w * 0.05f))
        drawRect(Color.White.copy(alpha = 0.75f), Offset(w * 0.46f, bodyTop), Size(w * 0.08f, w * 0.42f))
        rotate(-lidOpen * 35f, Offset(w * 0.12f, bodyTop)) {
            val lidY = bodyTop - w * 0.12f - lidOpen * w * 0.08f
            drawRoundRect(color, Offset(w * 0.12f, lidY), Size(w * 0.76f, w * 0.14f), androidx.compose.ui.geometry.CornerRadius(w * 0.04f))
            drawRect(Color.White.copy(alpha = 0.8f), Offset(w * 0.46f, lidY), Size(w * 0.08f, w * 0.14f))
            // Bow
            drawOval(Color.White, Offset(w * 0.32f, lidY - w * 0.1f), Size(w * 0.17f, w * 0.12f))
            drawOval(Color.White, Offset(w * 0.51f, lidY - w * 0.1f), Size(w * 0.17f, w * 0.12f))
        }
        drawStar(Offset(w * 0.5f, bodyTop + w * 0.21f), w * 0.07f, Color.White.copy(alpha = 0.9f))
    }
}

/** Small round count badge. */
@Composable
fun CountBadge(n: Int, modifier: Modifier = Modifier, color: Color = Palette.SakuraDeep) {
    Box(modifier.size(20.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
        Text(if (n > 99) "99+" else n.toString(), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(56.dp)) { drawStar(center, size.minDimension / 2, Palette.Lavender.copy(alpha = 0.5f)) }
        Spacer(Modifier.height(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Palette.InkSoft, textAlign = TextAlign.Center)
    }
}

@Composable
fun dpToPx(dp: Dp): Int = with(LocalDensity.current) { dp.roundToPx() }
