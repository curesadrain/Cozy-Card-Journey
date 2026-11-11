package com.cozycards.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.cozycards.game.cards.Rarity

object Palette {
    val Sakura = Color(0xFFF4A6C1)
    val SakuraDeep = Color(0xFFD9467C)
    val Cream = Color(0xFFFFF7EC)
    val Paper = Color(0xFFFBF1E3)
    val Ink = Color(0xFF2E2538)
    val InkSoft = Color(0xFF5B5068)
    val Lavender = Color(0xFFB9A2F0)
    val Night = Color(0xFF1F1B33)
    val NightSoft = Color(0xFF2E2950)
    val Matcha = Color(0xFF8DBF8B)
    val Lantern = Color(0xFFF08A4B)
    val Gold = Color(0xFFE8B84A)
    val Sky = Color(0xFF9CC8F0)
    val Coin = Color(0xFFF2C14E)
    val Token = Color(0xFF7FC8C2)
    val Danger = Color(0xFFE05A5A)
    /** Shared look of every plate, chip and frame: cream paper with a gold rim. */
    val CreamPlate = Color(0xF7FFF7EC)
    val GoldLight = Color(0xFFF7D774)
    val GoldDark = Color(0xFFC8962C)
    val GoldRim = androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFFF7D774), Color(0xFFC8962C)))

    fun rarity(r: Rarity) = when (r) {
        Rarity.COMMON -> Color(0xFFB9AE9E)
        Rarity.RARE -> Color(0xFF5E8BD0)
        Rarity.EPIC -> Color(0xFF8E5BD8)
        Rarity.LEGENDARY -> Color(0xFFE2A93B)
    }
}

private val scheme = lightColorScheme(
    primary = Palette.SakuraDeep,
    onPrimary = Color.White,
    secondary = Palette.Lavender,
    onSecondary = Palette.Ink,
    tertiary = Palette.Matcha,
    background = Palette.Cream,
    onBackground = Palette.Ink,
    surface = Palette.Paper,
    onSurface = Palette.Ink,
    surfaceVariant = Color(0xFFF3E6F0),
    onSurfaceVariant = Palette.InkSoft,
    error = Palette.Danger,
)

private val serif = FontFamily.Serif
private val typography = Typography(
    displaySmall = TextStyle(fontFamily = serif, fontWeight = FontWeight.Bold, fontSize = 30.sp),
    headlineMedium = TextStyle(fontFamily = serif, fontWeight = FontWeight.Bold, fontSize = 26.sp),
    headlineSmall = TextStyle(fontFamily = serif, fontWeight = FontWeight.Bold, fontSize = 22.sp),
    titleLarge = TextStyle(fontFamily = serif, fontWeight = FontWeight.Bold, fontSize = 20.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 15.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 12.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp),
)

@Composable
fun CozyTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
