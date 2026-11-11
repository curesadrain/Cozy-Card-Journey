package com.cozycards.app.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cozycards.app.ui.gfx.Sprites
import com.cozycards.app.ui.gfx.drawImageCentered
import com.cozycards.app.ui.theme.Palette
import com.cozycards.game.economy.BoosterType

/**
 * Shared visual language for every screen: cream paper plates with a gold rim, glossy
 * sprite buttons, gold-rimmed medallions. All screens use these instead of raw Material parts.
 */

/** Cream plate with a gold rim. */
@Composable
fun Panel(modifier: Modifier = Modifier, color: Color = Palette.CreamPlate, content: @Composable () -> Unit) {
    Box(
        modifier
            .shadow(6.dp, RoundedCornerShape(22.dp), clip = false)
            .clip(RoundedCornerShape(22.dp))
            .background(color)
            .border(2.5.dp, Palette.GoldRim, RoundedCornerShape(22.dp)),
    ) { content() }
}

private val grayscale = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

enum class ButtonStyle { PRIMARY, SECONDARY, PLAIN }

/**
 * Glossy button. Pink = primary action, lavender = secondary, cream plate = neutral.
 * The legacy [color] parameter maps old call sites onto these three styles.
 */
@Composable
fun CozyButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = Palette.SakuraDeep,
    @Suppress("UNUSED_PARAMETER") textColor: Color = Color.White,
    small: Boolean = false,
) {
    val style = when (color) {
        Palette.SakuraDeep -> ButtonStyle.PRIMARY
        Palette.Lavender, Palette.Token -> ButtonStyle.SECONDARY
        else -> ButtonStyle.PLAIN
    }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, label = "press")
    val sprite = when (style) {
        ButtonStyle.PRIMARY -> Sprites["btn_pink"]
        ButtonStyle.SECONDARY -> Sprites["btn_lavender"]
        ButtonStyle.PLAIN -> null
    }
    val look = if (sprite != null) Modifier.drawBehind {
        drawThreeSliceFiltered(sprite, if (enabled) 1f else 0.75f, if (enabled) null else grayscale)
    } else Modifier
        .clip(RoundedCornerShape(50))
        .background(Brush.verticalGradient(listOf(Color(0xFFFFFCF6), Color(0xFFF6E8D6))))
        .border(2.5.dp, Palette.GoldRim, RoundedCornerShape(50))
    Box(
        modifier
            .scale(scale)
            .then(look)
            .clickable(interaction, null, enabled = enabled, onClick = onClick)
            .padding(PaddingValues(horizontal = if (small) 16.dp else 24.dp, vertical = if (small) 9.dp else 14.dp)),
        contentAlignment = Alignment.Center,
    ) {
        val base = if (small) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge
        Text(
            text,
            color = if (style == ButtonStyle.PLAIN) (if (enabled) Palette.Ink else Palette.InkSoft) else Color.White,
            textAlign = TextAlign.Center,
            style = base.copy(
                fontWeight = FontWeight.ExtraBold,
                shadow = if (style != ButtonStyle.PLAIN) Shadow(Color(0x99502040), Offset(0f, 2f), 3f) else null,
            ),
        )
    }
}

fun DrawScope.drawThreeSliceFiltered(img: androidx.compose.ui.graphics.ImageBitmap, alpha: Float, filter: ColorFilter?) {
    val sh = img.height
    val cap = sh / 2
    val dh = size.height
    val dcap = (dh * cap / sh).coerceAtMost(size.width / 2)
    fun part(sx: Int, sw: Int, dx: Float, dw: Float) = drawImage(
        img, srcOffset = IntOffset(sx, 0), srcSize = IntSize(sw, sh),
        dstOffset = IntOffset(dx.toInt(), 0), dstSize = IntSize(dw.toInt().coerceAtLeast(1), dh.toInt()), alpha = alpha, colorFilter = filter,
    )
    part(0, cap, 0f, dcap)
    part(cap, img.width - 2 * cap, dcap, size.width - 2 * dcap)
    part(img.width - cap, cap, size.width - dcap, dcap)
}

/** Selectable pill used for filters and tabs. */
@Composable
fun CozyChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .clip(shape)
            .background(if (selected) Brush.verticalGradient(listOf(Color(0xFFFF8FB8), Color(0xFFD9467C))) else Brush.verticalGradient(listOf(Color(0xFFFFFCF6), Color(0xFFF6E8D6))))
            .border(2.dp, if (selected) Palette.GoldRim else Brush.verticalGradient(listOf(Color(0xFFE9D3A0), Color(0xFFD7B676))), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) { Box(Modifier.size(16.dp)) { leadingIcon() }; Spacer(Modifier.width(6.dp)) }
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides if (selected) Color.White else Palette.Ink,
            androidx.compose.material3.LocalTextStyle provides MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
        ) { label() }
    }
}

/** Gold-rimmed round medallion (moves counter, boosters, map nodes). */
@Composable
fun Medallion(
    size: Dp,
    modifier: Modifier = Modifier,
    fill: Brush = Brush.radialGradient(listOf(Color(0xFFFFFCF6), Color(0xFFF7D8E4))),
    rim: Dp = 4.dp,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .size(size)
            .shadow(6.dp, CircleShape, clip = false)
            .clip(CircleShape)
            .background(fill)
            .border(rim, Palette.GoldRim, CircleShape),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Count badge in the same pink + gold style. */
@Composable
fun GoldBadge(n: Int, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Box(
        modifier.size(22.dp).clip(CircleShape)
            .background(if (enabled) Brush.verticalGradient(listOf(Color(0xFFFF8FB8), Color(0xFFD9467C))) else Brush.verticalGradient(listOf(Color(0xFFB8B0BC), Color(0xFF8E8796))))
            .border(1.5.dp, Palette.GoldRim, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(if (n > 99) "99+" else n.toString(), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Black)
    }
}

/**
 * Booster artwork. Boosters that match a game piece reuse that piece's art so they read the same
 * on the board and in menus; the remaining tools fall back to drawn icons until dedicated art exists.
 */
@Composable
fun BoosterArt(type: BoosterType, modifier: Modifier = Modifier) {
    when (type) {
        BoosterType.ROCKET, BoosterType.BOMB, BoosterType.RAINBOW, BoosterType.ROCKET_BOMB -> androidx.compose.foundation.Canvas(modifier) {
            val s = size.minDimension
            when (type) {
                BoosterType.ROCKET -> drawImageCentered(Sprites["rocket_h"], center, s, 1f, null, false)
                BoosterType.BOMB -> drawImageCentered(Sprites["bomb"], center, s, 1f, null, false)
                BoosterType.RAINBOW -> drawImageCentered(Sprites["rainbow"], center, s, 1f, null, false)
                else -> {
                    drawImageCentered(Sprites["rocket_v"], center - Offset(s * 0.18f, 0f), s * 0.8f, 1f, null, false)
                    drawImageCentered(Sprites["bomb"], center + Offset(s * 0.2f, s * 0.12f), s * 0.7f, 1f, null, false)
                }
            }
        }
        BoosterType.EXTRA_MOVES -> Box(modifier, contentAlignment = Alignment.Center) {
            Text("+5", fontSize = 20.sp, fontWeight = FontWeight.Black, color = Color(0xFF3F8F4A),
                style = MaterialTheme.typography.titleLarge.copy(shadow = Shadow(Color.White, Offset(0f, 2f), 2f)))
        }
        else -> BoosterIcon(type, modifier)
    }
}

/** Section header used across screens. */
@Composable
fun PlateTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(vertical = 4.dp), style = MaterialTheme.typography.titleLarge, color = Palette.Ink)
}

@Composable
fun Gap(h: Dp = 8.dp) = Spacer(Modifier.height(h))

@Suppress("unused")
@Composable
private fun DesignPreviewColumn(content: @Composable () -> Unit) = Column(Modifier.fillMaxWidth()) { content() }
