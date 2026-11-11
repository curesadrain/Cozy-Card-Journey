package com.cozycards.app.ui.map

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.cozycards.app.container
import com.cozycards.app.data.PlayerState
import com.cozycards.app.ui.common.ChapterLooks
import com.cozycards.app.ui.common.CozyButton
import com.cozycards.app.ui.common.Currency
import com.cozycards.app.ui.common.CurrencyChip
import com.cozycards.app.ui.common.CurrencyIcon
import com.cozycards.app.ui.common.LootboxArt
import com.cozycards.app.ui.common.Panel
import com.cozycards.app.ui.common.ProgressBar
import com.cozycards.app.ui.common.StarIcon
import com.cozycards.app.ui.common.StarRow
import com.cozycards.app.ui.gfx.Backgrounds
import com.cozycards.app.ui.gfx.Sprites
import com.cozycards.app.ui.gfx.drawImageCentered
import com.cozycards.app.ui.level.GoalIcon
import com.cozycards.app.ui.level.PreLevelBoosterPicker
import com.cozycards.app.ui.theme.Palette
import com.cozycards.game.DailyStatus
import com.cozycards.game.economy.BoosterType
import com.cozycards.game.progression.DailyReward
import com.cozycards.levels.ChapterInfo
import com.cozycards.levels.Chapters
import com.cozycards.levels.LevelDefinition
import com.cozycards.levels.LootboxTier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.sin

private val NODE_H = 112.dp
private val BANNER_H = 230.dp

/** Map rows from the bottom up: a chapter gate, then its 50 levels. */
private sealed interface MapRow {
    val chapter: Int
    data class Gate(override val chapter: Int) : MapRow
    data class Node(val number: Int) : MapRow { override val chapter get() = (number - 1) / Chapters.LEVELS_PER_CHAPTER + 1 }
}

private val rows: List<MapRow> = buildList {
    for (ch in Chapters.all) {
        add(MapRow.Gate(ch.number))
        for (n in ch.firstLevel..ch.lastLevel) add(MapRow.Node(n))
    }
}

/** Horizontal position (0..1) of a level on the winding path. */
private fun pathX(n: Int) = 0.5f + 0.27f * sin(n * 0.78f) + 0.05f * sin(n * 2.1f)

@Composable
fun MapScreen(onPlay: (String, Set<BoosterType>) -> Unit, onOpenProfile: () -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val player by container.repository.state.collectAsState()
    val service = container.repository.service
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf<LevelDefinition?>(null) }
    var daily by remember { mutableStateOf<DailyStatus?>(null) }
    var dailyResult by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(player.recentRewards.size) { daily = runCatching { service.dailyStatus() }.getOrNull() }

    val listState = rememberLazyListState()
    LaunchedEffect(player.loaded) {
        if (player.loaded) {
            val idx = rows.indexOfFirst { it is MapRow.Node && it.number == player.currentLevel }
            listState.scrollToItem((idx - 2).coerceAtLeast(0))
        }
    }
    val transition = rememberInfiniteTransition(label = "map")
    val t by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart), label = "t")

    val gateY = remember { androidx.compose.runtime.mutableStateMapOf<Int, Float>() }
    val screenH = with(LocalDensity.current) { androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp.toPx() }
    // Which chapter art is behind the path, and how far the next chapter has faded in.
    val scene by remember {
        androidx.compose.runtime.derivedStateOf {
            val visible = listState.layoutInfo.visibleItemsInfo.map { rows[it.index] }
            val gate = visible.filterIsInstance<MapRow.Gate>().firstOrNull { it.chapter > 1 }
            if (gate != null) {
                val y = gateY[gate.chapter] ?: (screenH / 2)
                Triple(gate.chapter - 1, gate.chapter, (1f - y / screenH).coerceIn(0f, 1f))
            } else {
                val ch = visible.firstOrNull()?.chapter ?: ((player.currentLevel - 1) / Chapters.LEVELS_PER_CHAPTER + 1)
                Triple(ch, ch, 0f)
            }
        }
    }
    Box(Modifier.fillMaxSize().background(Color(0xFF2A2140))) {
        val artA = rememberChapterArt(scene.first, false)
        val artB = rememberChapterArt(scene.second, false)
        if (artA != null) androidx.compose.foundation.Image(artA, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        if (artB != null && scene.third > 0f) androidx.compose.foundation.Image(artB, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = scene.third)
        Box(Modifier.fillMaxSize().background(Color(0x22150E22)))
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val widthPx = constraints.maxWidth.toFloat()
            LazyColumn(state = listState, reverseLayout = true, contentPadding = PaddingValues(top = 90.dp, bottom = com.cozycards.app.ui.common.NAV_SPACE), modifier = Modifier.fillMaxSize()) {
                itemsIndexed(rows, key = { _, r -> if (r is MapRow.Node) "n${r.number}" else "g${r.chapter}" }) { _, row ->
                    when (row) {
                        is MapRow.Gate -> ChapterGate(Chapters.all[row.chapter - 1], player) { y -> gateY[row.chapter] = y }
                        is MapRow.Node -> LevelRow(row.number, player, widthPx, t) { n -> service.level(n)?.let { selected = it } }
                    }
                }
            }
        }
        TopBar(player, daily, onDaily = {
            scope.launch {
                dailyResult = runCatching { describe(service.claimDaily()) }.getOrElse { it.message }
                daily = runCatching { service.dailyStatus() }.getOrNull()
            }
        })
        selected?.let { def ->
            PreLevelDialog(def, player, onDismiss = { selected = null }, onPlay = { boosters ->
                scope.launch {
                    val result = runCatching { service.startLevel(def.id, boosters) }
                    // Navigation must happen on the main thread.
                    withContext(Dispatchers.Main.immediate) {
                        result.onSuccess { selected = null; onPlay(def.id, boosters) }
                            .onFailure { dailyResult = it.message }
                    }
                }
            })
        }
        dailyResult?.let { msg ->
            Dialog(onDismissRequest = { dailyResult = null }) {
                Panel { Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(msg, textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium, color = Palette.Ink)
                    Spacer(Modifier.height(12.dp))
                    CozyButton("OK", { dailyResult = null })
                } }
            }
        }
    }
}

fun describe(r: DailyReward) = when (r) {
    is DailyReward.Coins -> "Daily reward: ${r.amount} coins!"
    is DailyReward.Tokens -> "Daily reward: ${r.amount} collector tokens!"
    is DailyReward.Booster -> "Daily reward: ${r.amount}× ${r.type.title}!"
    is DailyReward.Box -> "Daily reward: a ${r.tier.name.lowercase()} lootbox!"
}

@Composable
private fun TopBar(player: PlayerState, daily: DailyStatus?, onDaily: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color(0xAA1A1228), Color.Transparent)))
            .statusBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CurrencyChip(Currency.COINS, player.wallet.coins)
        CurrencyChip(Currency.TOKENS, player.wallet.tokens)
        Spacer(Modifier.weight(1f))
        if (daily != null) {
            val transition = rememberInfiniteTransition(label = "daily")
            val pulse by transition.animateFloat(1f, 1.08f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse")
            CozyButton(
                when {
                    daily.clockTampered -> "Clock changed"
                    daily.canClaim -> "Daily gift!"
                    else -> "Streak ${daily.streak}"
                },
                onDaily, Modifier.scale(if (daily.canClaim) pulse else 1f), enabled = daily.canClaim,
                color = if (daily.canClaim) Palette.SakuraDeep else Palette.Lavender, small = true,
            )
        }
    }
}

/** Loads a chapter background (full size) asynchronously. */
@Composable
private fun rememberChapterArt(chapter: Int, thumb: Boolean): ImageBitmap? {
    val context = LocalContext.current
    var img by remember(chapter, thumb) { mutableStateOf(Backgrounds.peek(chapter, thumb)) }
    LaunchedEffect(chapter, thumb) {
        if (img == null) img = withContext(Dispatchers.IO) { Backgrounds.load(context, chapter, thumb) }
    }
    return img
}

/**
 * Draws a slice of the chapter illustration so that consecutive rows form one continuous,
 * endlessly mirrored painting behind the path.
 */
private fun DrawScope.sceneSlice(img: ImageBitmap?, bottomY: Float, rowH: Float, fallbackTop: Color, fallbackBottom: Color) {
    if (img == null) {
        drawRect(Brush.verticalGradient(listOf(fallbackTop, fallbackBottom)))
        return
    }
    val scale = size.width / img.width
    val tileH = img.height * scale
    var y = bottomY
    var drawn = 0f
    while (drawn < rowH - 0.5f) {
        val tile = (y / tileH).toInt()
        val inTile = y - tile * tileH
        val take = minOf(rowH - drawn, tileH - inTile)
        // Odd tiles are mirrored vertically so the seams match.
        val srcBottom = if (tile % 2 == 0) img.height - inTile / scale else inTile / scale + take / scale
        val srcTop = srcBottom - take / scale
        val dstTop = size.height - drawn - take
        if (tile % 2 == 0) {
            drawImage(img, IntOffset(0, srcTop.toInt().coerceAtLeast(0)), IntSize(img.width, (take / scale).toInt().coerceAtLeast(1)),
                IntOffset(0, dstTop.toInt()), IntSize(size.width.toInt(), take.toInt() + 1))
        } else {
            scale(1f, -1f, Offset(size.width / 2, dstTop + take / 2)) {
                drawImage(img, IntOffset(0, (inTile / scale).toInt().coerceAtMost(img.height - 1)), IntSize(img.width, (take / scale).toInt().coerceAtLeast(1)),
                    IntOffset(0, dstTop.toInt()), IntSize(size.width.toInt(), take.toInt() + 1))
            }
        }
        drawn += take
        y += take
    }
    // Soft darkening so nodes and path stay readable over busy art.
    drawRect(Color(0x33150E22))
}

@Composable
private fun ChapterGate(ch: ChapterInfo, player: PlayerState, onY: (Float) -> Unit = {}) {
    val art = rememberChapterArt(ch.number, false)
    val thumb = rememberChapterArt(ch.number, true)
    val look = ChapterLooks.of(ch.number)
    val cleared = (ch.firstLevel..ch.lastLevel).count { player.progress[Chapters.levelId(it)]?.firstClearedAt != null }
    val stars = (ch.firstLevel..ch.lastLevel).sumOf { player.progress[Chapters.levelId(it)]?.bestStars ?: 0 }
    val unlocked = player.isUnlocked(ch.firstLevel)
    Box(Modifier.fillMaxWidth().height(BANNER_H).onGloballyPositioned { onY(it.positionInRoot().y + it.size.height / 2f) }) {
        Panel(Modifier.align(Alignment.Center).padding(horizontal = 18.dp).fillMaxWidth()) {
            Column {
                // Picture strip on top, text on an opaque cream plate below: always readable.
                Box(Modifier.fillMaxWidth().height(84.dp)) {
                    if (thumb != null) androidx.compose.foundation.Image(thumb, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x66000000)))))
                    Text("Chapter ${ch.number}", Modifier.align(Alignment.BottomStart).padding(12.dp), color = Color.White,
                        style = MaterialTheme.typography.labelLarge.copy(shadow = androidx.compose.ui.graphics.Shadow(Color.Black, Offset(0f, 2f), 4f)))
                    if (!unlocked) Box(Modifier.fillMaxSize().background(Color(0x88000000)), contentAlignment = Alignment.Center) {
                        Text("🔒 Finish chapter ${ch.number - 1}", color = Color.White, style = MaterialTheme.typography.titleSmall)
                    }
                }
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text(ch.title, style = MaterialTheme.typography.headlineSmall, color = Palette.Ink)
                    Text(ch.subtitle, style = MaterialTheme.typography.bodySmall, color = Palette.InkSoft)
                    if (ch.introduces.isNotEmpty()) Text("New: " + ch.introduces.joinToString { it.title }, style = MaterialTheme.typography.labelMedium, color = Palette.SakuraDeep)
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ProgressBar(cleared / 50f, Modifier.weight(1f), color = Palette.SakuraDeep)
                        Spacer(Modifier.width(8.dp))
                        Text("$cleared/50", style = MaterialTheme.typography.labelMedium, color = Palette.Ink)
                        Spacer(Modifier.width(8.dp))
                        StarIcon(true, Modifier.size(16.dp))
                        Text(" $stars/150", style = MaterialTheme.typography.labelMedium, color = Palette.Ink)
                    }
                }
            }
        }
    }
}

@Composable
private fun LevelRow(number: Int, player: PlayerState, widthPx: Float, t: Float, onClick: (Int) -> Unit) {
    val chapter = (number - 1) / Chapters.LEVELS_PER_CHAPTER + 1
    val idxInChapter = (number - 1) % Chapters.LEVELS_PER_CHAPTER
    val art = rememberChapterArt(chapter, false)
    val look = ChapterLooks.of(chapter)
    val density = LocalDensity.current
    val rowH = with(density) { NODE_H.toPx() }
    val bannerH = with(density) { BANNER_H.toPx() }
    val unlocked = player.isUnlocked(number)
    val current = number == player.currentLevel
    val progress = player.progress[Chapters.levelId(number)]
    val cleared = progress?.firstClearedAt != null
    val x = pathX(number) * widthPx
    val xPrev = pathX(number - 1) * widthPx
    val xNext = pathX(number + 1) * widthPx
    Box(Modifier.fillMaxWidth().height(NODE_H)) {
        Canvas(Modifier.fillMaxSize()) {
            // Scenery continues seamlessly from the chapter gate below.
            val cy = size.height / 2
            // Path: smooth curve through this node between the neighbours' midpoints.
            val path = Path().apply {
                moveTo((x + xPrev) / 2, size.height)
                quadraticBezierTo(x, cy, (x + xNext) / 2, 0f)
            }
            val doneColor = Color(0xFFF7D774)
            drawPath(path, Color(0x99302040), style = Stroke(26f, cap = StrokeCap.Round))
            drawPath(path, if (cleared) doneColor else Color(0xFFFFF3E2), style = Stroke(14f, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 30f))))
            if (cleared || current) drawPath(path, doneColor.copy(alpha = 0.35f), style = Stroke(34f, cap = StrokeCap.Round))
        }
        // Reward chest next to milestone levels.
        if (number % 5 == 0) {
            val chestX = if (pathX(number) > 0.5f) x - with(density) { 88.dp.toPx() } else x + with(density) { 38.dp.toPx() }
            val tier = when { number % 50 == 0 -> LootboxTier.LEGENDARY; number % 10 == 0 -> LootboxTier.EPIC; else -> LootboxTier.RARE }
            Box(Modifier.offset { IntOffset(chestX.toInt(), (rowH * 0.18f).toInt()) }) {
                LootboxArt(tier, Modifier.size(50.dp), lidOpen = if (cleared) 1f else 0f, glow = if (cleared) 0f else 0.5f + 0.5f * sin(t * 2 * PI.toFloat()))
            }
        }
        val size = when { current -> 80.dp; number % 10 == 0 -> 70.dp; else -> 62.dp }
        LevelNode(number, unlocked, current, cleared, progress?.bestStars ?: 0, size, t,
            Modifier.offset { IntOffset((x - with(density) { size.toPx() } / 2).toInt(), ((rowH - with(density) { size.toPx() }) / 2).toInt()) }) { onClick(number) }
    }
}

@Composable
private fun LevelNode(number: Int, unlocked: Boolean, current: Boolean, cleared: Boolean, stars: Int, size: Dp, t: Float, modifier: Modifier, onClick: () -> Unit) {
    val pulse = 1f + if (current) 0.06f * sin(t * 2 * PI.toFloat()) else 0f
    Box(modifier.size(size)) {
        if (current) Canvas(Modifier.size(size).scale(1.6f)) {
            drawImageCentered(Sprites["glow"], center, this.size.minDimension, 0.55f + 0.25f * sin(t * 2 * PI.toFloat()), Color(0xFFFFD6E8), true)
        }
        val fill = when {
            current -> Brush.radialGradient(listOf(Color(0xFFFFB8D2), Color(0xFFD9467C)))
            cleared -> Brush.radialGradient(listOf(Color(0xFFFFF4C8), Color(0xFFF2B93B)))
            unlocked -> Brush.radialGradient(listOf(Color(0xFFFFFCF6), Color(0xFFF7D8E4)))
            else -> Brush.radialGradient(listOf(Color(0xFFB9B4C4), Color(0xFF7C7590)))
        }
        com.cozycards.app.ui.common.Medallion(size, Modifier.scale(pulse).clickable(
            enabled = unlocked, interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick,
        ), fill = fill, rim = if (current) 5.dp else 4.dp) {
            if (unlocked) Text(
                "$number", fontWeight = FontWeight.Black, fontSize = if (number >= 100) 18.sp else 22.sp,
                color = if (current) Color.White else Palette.Ink,
                style = MaterialTheme.typography.titleLarge.copy(shadow = if (current) androidx.compose.ui.graphics.Shadow(Color(0x88502040), Offset(0f, 2f), 3f) else null),
            ) else Canvas(Modifier.size(22.dp)) {
                drawRoundRect(Color(0xEEFFFFFF), Offset(0f, this.size.height * 0.45f), Size(this.size.width, this.size.height * 0.55f), androidx.compose.ui.geometry.CornerRadius(4f))
                drawArc(Color(0xEEFFFFFF), 180f, 180f, false, Offset(this.size.width * 0.2f, 0f), Size(this.size.width * 0.6f, this.size.height * 0.8f), style = Stroke(5f))
            }
        }
        if (cleared) Row(Modifier.align(Alignment.BottomCenter).offset(y = 14.dp)) {
            repeat(3) { i -> StarIcon(i < stars, Modifier.size(if (i == 1) 22.dp else 18.dp).offset(y = if (i == 1) 3.dp else 0.dp)) }
        }
        if (current) {
            val bob = sin(t * 2 * PI.toFloat()) * 5f
            Box(Modifier.align(Alignment.TopCenter).offset(y = (-46).dp + bob.dp)) {
                Text(
                    "Play", color = Color.White, fontWeight = FontWeight.Black,
                    modifier = Modifier.clip(RoundedCornerShape(50))
                        .background(Brush.verticalGradient(listOf(Color(0xFFFF8FB8), Color(0xFFD9467C))))
                        .border(2.dp, Palette.GoldRim, RoundedCornerShape(50))
                        .padding(horizontal = 12.dp, vertical = 3.dp),
                )
            }
        }
    }
}

@Composable
private fun PreLevelDialog(def: LevelDefinition, player: PlayerState, onDismiss: () -> Unit, onPlay: (Set<BoosterType>) -> Unit) {
    var picked by remember { mutableStateOf(setOf<BoosterType>()) }
    val progress = player.progress[def.id]
    val firstClear = progress?.firstClearedAt == null
    Dialog(onDismissRequest = onDismiss) {
        Panel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Level ${def.number}", style = MaterialTheme.typography.headlineMedium)
                Text("${Chapters.all[def.chapter - 1].title} · ${def.tier.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }}", style = MaterialTheme.typography.bodySmall, color = Palette.InkSoft)
                if (progress != null && progress.bestStars > 0) StarRow(progress.bestStars, 18.dp)
                Spacer(Modifier.height(12.dp))
                Text("Goals", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                    def.stages.first().goals.forEach { g ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            GoalIcon(g.type, g.color, Modifier.size(36.dp))
                            Text("${g.target}", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
                Text("${def.moves} moves" + if (def.stages.size > 1) " · ${def.stages.size} stages" else "", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CurrencyIcon(Currency.COINS, Modifier.size(20.dp))
                    Text(" ${def.reward.coins}+", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.width(14.dp))
                    if (firstClear) {
                        LootboxArt(def.reward.lootbox, Modifier.size(34.dp))
                        Text(" ${tierName(def.reward.lootbox)} box", style = MaterialTheme.typography.labelLarge)
                    } else {
                        Text("Box already collected", style = MaterialTheme.typography.labelMedium, color = Palette.InkSoft)
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text("Pre-level boosters (optional)", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                PreLevelBoosterPicker(player.boosters, picked) { b -> picked = if (b in picked) picked - b else picked + b }
                Spacer(Modifier.height(16.dp))
                CozyButton("Play", { onPlay(picked) }, Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                CozyButton("Close", onDismiss, Modifier.fillMaxWidth(), color = Color.White, textColor = Palette.Ink)
            }
        }
    }
}

fun tierName(t: LootboxTier) = t.name.lowercase().replaceFirstChar { it.uppercase() }
