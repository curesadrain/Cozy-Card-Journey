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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import com.cozycards.app.ui.common.SceneBackground
import com.cozycards.app.ui.common.StarRow
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

private const val PER_ROW = 5

@Composable
fun MapScreen(onPlay: (String, Set<BoosterType>) -> Unit, onOpenProfile: () -> Unit) {
    val container = LocalContext.current.container
    val player by container.repository.state.collectAsState()
    val service = container.repository.service
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf<LevelDefinition?>(null) }
    var daily by remember { mutableStateOf<DailyStatus?>(null) }
    var dailyResult by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(player.recentRewards.size) { daily = runCatching { service.dailyStatus() }.getOrNull() }

    val currentChapter = (player.currentLevel - 1) / Chapters.LEVELS_PER_CHAPTER + 1
    val listState = rememberLazyListState()
    // Item index: each chapter = 1 header + 10 rows.
    LaunchedEffect(player.loaded) {
        if (player.loaded) {
            val row = ((player.currentLevel - 1) % Chapters.LEVELS_PER_CHAPTER) / PER_ROW
            listState.scrollToItem(((currentChapter - 1) * 11 + row).coerceAtLeast(0))
        }
    }
    val look = ChapterLooks.of(currentChapter)

    val context = LocalContext.current
    var bg by remember(currentChapter) { mutableStateOf(com.cozycards.app.ui.gfx.Backgrounds.peek(currentChapter, false)) }
    LaunchedEffect(currentChapter) {
        if (bg == null) bg = withContext(Dispatchers.IO) { com.cozycards.app.ui.gfx.Backgrounds.load(context, currentChapter, false) }
    }
    Box(Modifier.fillMaxSize()) {
        val image = bg
        if (image != null) {
            androidx.compose.foundation.Image(image, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Color(0x55201530)))
        } else SceneBackground(look)
        Column(Modifier.fillMaxSize()) {
            TopBar(player, daily, onDaily = {
                scope.launch {
                    dailyResult = runCatching { describe(service.claimDaily()) }.getOrElse { it.message }
                    daily = runCatching { service.dailyStatus() }.getOrNull()
                }
            })
            LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
                for (ch in Chapters.all) {
                    item(key = "ch${ch.number}") { ChapterHeader(ch, player) }
                    for (r in 0 until Chapters.LEVELS_PER_CHAPTER / PER_ROW) {
                        item(key = "row${ch.number}_$r") {
                            LevelRow(ch, r, player) { number -> service.level(number)?.let { selected = it } }
                        }
                    }
                }
            }
        }
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
                    Text(msg, textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
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
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CurrencyChip(Currency.COINS, player.wallet.coins)
        CurrencyChip(Currency.TOKENS, player.wallet.tokens)
        Spacer(Modifier.weight(1f))
        if (daily != null) {
            val transition = rememberInfiniteTransition(label = "daily")
            val pulse by transition.animateFloat(1f, 1.1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse")
            Box(
                Modifier.scale(if (daily.canClaim) pulse else 1f).clip(RoundedCornerShape(50))
                    .background(if (daily.canClaim) Palette.SakuraDeep else Color.White.copy(alpha = 0.7f))
                    .clickable(enabled = daily.canClaim, onClick = onDaily)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    when {
                        daily.clockTampered -> "Clock changed"
                        daily.canClaim -> "Daily gift!"
                        else -> "Streak ${daily.streak}"
                    },
                    color = if (daily.canClaim) Color.White else Palette.Ink, style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun ChapterHeader(ch: ChapterInfo, player: PlayerState) {
    val look = ChapterLooks.of(ch.number)
    val cleared = (ch.firstLevel..ch.lastLevel).count { player.progress[Chapters.levelId(it)]?.firstClearedAt != null }
    val stars = (ch.firstLevel..ch.lastLevel).sumOf { player.progress[Chapters.levelId(it)]?.bestStars ?: 0 }
    val unlocked = player.isUnlocked(ch.firstLevel)
    Panel(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
        val context = LocalContext.current
        var thumb by remember(ch.number) { mutableStateOf(com.cozycards.app.ui.gfx.Backgrounds.peek(ch.number, true)) }
        LaunchedEffect(ch.number) {
            if (thumb == null) thumb = withContext(Dispatchers.IO) { com.cozycards.app.ui.gfx.Backgrounds.load(context, ch.number, true) }
        }
        Box(Modifier.fillMaxWidth().height(168.dp)) {
            val t = thumb
            if (t != null) {
                androidx.compose.foundation.Image(t, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    alignment = androidx.compose.ui.Alignment.TopCenter)
                Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0x99000000), Color(0x33000000), Color(0xAA000000)))))
                if (!unlocked) Box(Modifier.fillMaxSize().background(Color(0x77000000)))
            } else SceneBackground(look, Modifier.fillMaxSize(), animate = false, dim = if (unlocked) 0f else 0.35f)
            Column(Modifier.padding(16.dp)) {
                Text("Chapter ${ch.number}", style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.9f))
                Text(ch.title, style = MaterialTheme.typography.headlineSmall, color = Color.White)
                Text(ch.subtitle, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.85f))
                if (ch.introduces.isNotEmpty()) Text("New: " + ch.introduces.joinToString { it.title }, style = MaterialTheme.typography.labelMedium, color = look.accent)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProgressBar(cleared / 50f, Modifier.weight(1f), color = look.accent)
                    Spacer(Modifier.width(8.dp))
                    Text("$cleared/50", style = MaterialTheme.typography.labelMedium, color = Color.White)
                    Spacer(Modifier.width(8.dp))
                    Text("★ $stars/150", style = MaterialTheme.typography.labelMedium, color = Palette.Gold)
                }
                if (!unlocked) Text("Locked – finish the previous chapter", style = MaterialTheme.typography.labelSmall, color = Color.White)
            }
        }
    }
}

@Composable
private fun LevelRow(ch: ChapterInfo, row: Int, player: PlayerState, onClick: (Int) -> Unit) {
    val look = ChapterLooks.of(ch.number)
    val numbers = (0 until PER_ROW).map { ch.firstLevel + row * PER_ROW + it }
    val ordered = if (row % 2 == 0) numbers else numbers.reversed()
    Box(Modifier.fillMaxWidth().height(86.dp).padding(horizontal = 18.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val y = size.height * 0.42f
            val dash = PathEffect.dashPathEffect(floatArrayOf(14f, 12f))
            drawLine(Color.White.copy(alpha = 0.8f), Offset(size.width * 0.1f, y), Offset(size.width * 0.9f, y), 8f, pathEffect = dash)
            // Connector to the next row on the turning side.
            val xTurn = if (row % 2 == 0) size.width * 0.9f else size.width * 0.1f
            drawLine(Color.White.copy(alpha = 0.8f), Offset(xTurn, y), Offset(xTurn, size.height + y), 8f, pathEffect = dash)
        }
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            ordered.forEach { n -> LevelNode(n, player, look.accent, onClick) }
        }
    }
}

@Composable
private fun LevelNode(number: Int, player: PlayerState, accent: Color, onClick: (Int) -> Unit) {
    val id = Chapters.levelId(number)
    val progress = player.progress[id]
    val cleared = progress?.firstClearedAt != null
    val unlocked = player.isUnlocked(number)
    val current = number == player.currentLevel
    val boss = number % 10 == 0
    val transition = rememberInfiniteTransition(label = "node")
    val pulse by transition.animateFloat(1f, 1.12f, infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Reverse), label = "pulse")
    val size = if (boss) 58.dp else 50.dp
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(60.dp)) {
        Box(
            Modifier
                .scale(if (current) pulse else 1f)
                .size(size)
                .clip(CircleShape)
                .background(
                    when {
                        cleared -> Color.White
                        current -> accent
                        unlocked -> Color.White.copy(alpha = 0.9f)
                        else -> Color(0xFFB9B2C2)
                    }
                )
                .border(3.dp, if (boss) Palette.Gold else if (cleared) accent else Color.White, CircleShape)
                .clickable(enabled = unlocked) { onClick(number) },
            contentAlignment = Alignment.Center,
        ) {
            if (!unlocked) {
                Canvas(Modifier.size(18.dp)) {
                    drawRoundRect(Color.White, Offset(0f, this.size.height * 0.45f), androidx.compose.ui.geometry.Size(this.size.width, this.size.height * 0.55f))
                    drawArc(Color.White, 180f, 180f, false, Offset(this.size.width * 0.2f, 0f), androidx.compose.ui.geometry.Size(this.size.width * 0.6f, this.size.height * 0.8f), style = androidx.compose.ui.graphics.drawscope.Stroke(4f))
                }
            } else {
                Text("$number", fontWeight = FontWeight.Black, fontSize = if (number >= 100) 14.sp else 17.sp, color = if (current && !cleared) Color.White else Palette.Ink)
            }
        }
        if (cleared) StarRow(progress?.bestStars ?: 0, 13.dp)
        else if (number % 5 == 0) Text(
            if (number % 50 == 0) "Legendary" else if (number % 10 == 0) "Epic" else "Rare",
            fontSize = 9.sp, color = Color.White, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 2.dp).clip(RoundedCornerShape(50)).background(Palette.Ink.copy(alpha = 0.55f)).padding(horizontal = 5.dp),
        )
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
