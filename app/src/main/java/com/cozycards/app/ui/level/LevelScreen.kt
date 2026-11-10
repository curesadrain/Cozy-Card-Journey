package com.cozycards.app.ui.level

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cozycards.app.container
import com.cozycards.app.data.AppSettings
import com.cozycards.app.ui.common.BoosterIcon
import com.cozycards.app.ui.common.ChapterLooks
import com.cozycards.app.ui.common.CountBadge
import com.cozycards.app.ui.common.CozyButton
import com.cozycards.app.ui.common.Currency
import com.cozycards.app.ui.common.CurrencyIcon
import com.cozycards.app.ui.common.LootboxArt
import com.cozycards.app.ui.common.Panel
import com.cozycards.app.ui.common.SceneBackground
import com.cozycards.app.ui.common.StarIcon
import com.cozycards.app.ui.theme.Palette
import com.cozycards.engine.Dir
import com.cozycards.engine.GameStatus
import com.cozycards.engine.GoalProgress
import com.cozycards.engine.GoalType
import com.cozycards.engine.LossReason
import com.cozycards.engine.Piece
import com.cozycards.engine.PieceKind
import com.cozycards.engine.Blocker
import com.cozycards.engine.BlockerType
import com.cozycards.game.economy.BoosterKind
import com.cozycards.game.economy.BoosterType
import com.cozycards.levels.Chapters
import kotlinx.coroutines.launch

@Composable
fun LevelScreen(
    levelId: String,
    boosters: Set<BoosterType>,
    onExit: () -> Unit,
    onNextLevel: (String) -> Unit,
    onOpenLootboxes: () -> Unit,
) {
    val context = LocalContext.current
    val container = context.container
    val def = remember(levelId) { container.repository.service.level(levelId) } ?: run { onExit(); return }
    val vm: LevelViewModel = viewModel(key = "level_$levelId", factory = viewModelFactory { initializer { LevelViewModel(container, def, boosters) } })
    val state by vm.state.collectAsState()
    val player by container.repository.state.collectAsState()
    val settings by container.settings.settings.collectAsState(initial = AppSettings())
    val look = when (player.equippedOrDefault(com.cozycards.game.economy.CosmeticSlot.BOARD_THEME)) {
        com.cozycards.game.economy.Cosmetic.BOARD_PAPER -> ChapterLooks.washiPaper
        com.cozycards.game.economy.Cosmetic.BOARD_NIGHT -> ChapterLooks.nightMarket
        com.cozycards.game.economy.Cosmetic.BOARD_PASTEL -> ChapterLooks.pastelCafe
        else -> ChapterLooks.of(def.chapter)
    }
    val stageSpec = def.stages.getOrNull(state.stageIndex) ?: def.stages.first()
    val portals = remember(state.stageIndex) { stageSpec.portals.map { com.cozycards.engine.Portal(it.entrance.toPos(), it.exit.toPos()) } }
    val conveyors = remember(state.stageIndex) { stageSpec.conveyors.map { l -> l.map { it.toPos() } } }

    BackHandler { if (!state.finished) vm.togglePause(!state.paused) else onExit() }

    Box(Modifier.fillMaxSize()) {
        SceneBackground(look, dim = 0.05f, animate = !settings.reducedMotion)
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Hud(def.number, state, onPause = { vm.togglePause(true) })
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
                BoardView(
                    state = state,
                    portals = portals,
                    conveyors = conveyors,
                    reducedMotion = settings.reducedMotion,
                    haptics = settings.haptics,
                    onSwap = vm::onSwap,
                    onTap = vm::onTap,
                    onStepDone = vm::stepDone,
                    modifier = Modifier.fillMaxSize(),
                )
                if (state.shuffledNotice) Banner("No moves left – shuffling!")
                state.message?.let { msg ->
                    LaunchedEffect(msg) { kotlinx.coroutines.delay(1800); vm.clearMessage() }
                    Banner(msg)
                }
            }
            BoosterBar(state.activeBooster, player.boosters, onSelect = vm::selectBooster)
        }
        if (state.paused && !state.finished) PauseOverlay(
            onResume = { vm.togglePause(false) },
            onRestart = { vm.togglePause(false); vm.restart() },
            onQuit = onExit,
            settings = settings,
            onToggleMusic = { on -> container.scope.launch { container.settings.update { it.copy(musicVolume = if (on) 0.6f else 0f) } } },
            onToggleSfx = { on -> container.scope.launch { container.settings.update { it.copy(sfxVolume = if (on) 0.8f else 0f) } } },
        )
        AnimatedVisibility(state.finished && state.anim == null, enter = fadeIn() + scaleIn(initialScale = 0.9f), exit = fadeOut()) {
            if (state.status == GameStatus.WON) WinOverlay(def.number, state, onNext = {
                val nextId = Chapters.levelId(def.number + 1)
                // The next level goes through the same unlock check as starting from the map.
                container.scope.launch {
                    runCatching { container.repository.service.startLevel(nextId, emptySet()) }
                        .onSuccess { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { onNextLevel(nextId) } }
                }
            }, onMap = onExit, onOpenBoxes = onOpenLootboxes)
            else LoseOverlay(state, player.boosters, onRetry = { vm.restart(it) }, onMap = onExit)
        }
    }
}

@Composable
private fun Banner(text: String) {
    Text(
        text,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(Palette.Ink.copy(alpha = 0.8f)).padding(horizontal = 16.dp, vertical = 8.dp),
        color = Color.White,
        style = MaterialTheme.typography.labelLarge,
    )
}

@Composable
private fun Hud(levelNumber: Int, state: LevelUiState, onPause: () -> Unit) {
    Panel(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(66.dp)) {
                val low = state.movesLeft <= 3
                val pulse by animateFloatAsState(if (low) 1.12f else 1f, tween(300), label = "moves")
                Text("${state.movesLeft}", fontSize = 30.sp, fontWeight = FontWeight.Black, color = if (low) Palette.Danger else Palette.Ink, modifier = Modifier.scale(pulse))
                Text("moves", style = MaterialTheme.typography.labelSmall, color = Palette.InkSoft)
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceEvenly) {
                state.goals.forEach { GoalChip(it) }
            }
            Column(horizontalAlignment = Alignment.End) {
                IconButton(onClick = onPause, modifier = Modifier.size(36.dp)) { Icon(Icons.Rounded.Pause, "Pause", tint = Palette.Ink) }
                Text("Lv $levelNumber", style = MaterialTheme.typography.labelSmall, color = Palette.InkSoft)
                if (state.stageCount > 1) Text("Stage ${state.stageIndex + 1}/${state.stageCount}", style = MaterialTheme.typography.labelSmall, color = Palette.SakuraDeep)
                if (state.gravity != Dir.DOWN) Text("Gravity ${arrow(state.gravity)}", style = MaterialTheme.typography.labelSmall, color = Palette.InkSoft)
            }
        }
    }
}

private fun arrow(d: Dir) = when (d) { Dir.DOWN -> "↓"; Dir.UP -> "↑"; Dir.LEFT -> "←"; Dir.RIGHT -> "→" }

@Composable
fun GoalChip(g: GoalProgress) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
            GoalIcon(g.goal.type, g.goal.color, Modifier.size(34.dp))
            if (g.done) Canvas(Modifier.size(18.dp).align(Alignment.BottomEnd)) {
                drawCircle(Palette.Matcha)
                drawLine(Color.White, Offset(size.width * 0.25f, size.height * 0.5f), Offset(size.width * 0.45f, size.height * 0.7f), 4f)
                drawLine(Color.White, Offset(size.width * 0.45f, size.height * 0.7f), Offset(size.width * 0.78f, size.height * 0.3f), 4f)
            }
        }
        Text(if (g.done) "✓" else "${g.remaining}", style = MaterialTheme.typography.labelLarge, color = Palette.Ink)
    }
}

@Composable
fun GoalIcon(type: GoalType, color: Int, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val s = size.minDimension
        val c = center
        val o = Offset(c.x - s / 2, c.y - s / 2)
        when (type) {
            GoalType.COLLECT_COLOR -> drawPiece(Piece(0, color), c, s)
            GoalType.CLEAR_ICE -> drawIce(o, s, 2)
            GoalType.BREAK_BOXES -> drawBlocker(Blocker(BlockerType.BOX, 2), o, s)
            GoalType.BREAK_STONES -> drawBlocker(Blocker(BlockerType.STONE, 1), o, s)
            GoalType.BREAK_CHAINS -> { drawPiece(Piece(0, 4), c, s * 0.8f); drawChain(o, s, 1) }
            GoalType.CLEAR_WEBS -> { drawCircle(Color(0x33000000), s * 0.45f); drawWeb(o, s) }
            GoalType.DELIVER_DROPS -> drawPiece(Piece(0, -1, PieceKind.DROP), c, s)
            GoalType.OPEN_LOCKS -> drawBlocker(Blocker(BlockerType.LOCK, 1), o, s)
            GoalType.SCORE -> drawCircle(Palette.Gold, s * 0.4f)
        }
    }
}

@Composable
private fun BoosterBar(active: BoosterType?, counts: Map<BoosterType, Int>, onSelect: (BoosterType?) -> Unit) {
    val inLevel = BoosterType.entries.filter { it.kind == BoosterKind.IN_LEVEL }
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        if (active != null) {
            Text(
                when (active) {
                    BoosterType.SWAP_GLOVE -> "Tap two neighbouring tiles to swap them"
                    else -> "Tap a cell to use ${active.title} · tap the booster again to cancel"
                },
                Modifier.fillMaxWidth().padding(4.dp), textAlign = TextAlign.Center, color = Palette.Ink, style = MaterialTheme.typography.labelMedium,
            )
        }
        LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)) {
            items(inLevel) { b ->
                val n = counts[b] ?: 0
                val selected = active == b
                Box(
                    Modifier
                        .size(58.dp)
                        .clip(CircleShape)
                        .background(if (selected) Palette.SakuraDeep.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.75f))
                        .border(2.dp, if (selected) Palette.SakuraDeep else Color.Transparent, CircleShape)
                        .clickable { onSelect(b) },
                    contentAlignment = Alignment.Center,
                ) {
                    BoosterIcon(b, Modifier.size(42.dp))
                    CountBadge(n, Modifier.align(Alignment.TopEnd), color = if (n > 0) Palette.SakuraDeep else Color.Gray)
                }
            }
        }
    }
}

@Composable
private fun Overlay(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)).clickable(enabled = true, onClick = {}), contentAlignment = Alignment.Center) {
        Panel(Modifier.padding(24.dp).fillMaxWidth()) {
            Column(Modifier.padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) { content() }
        }
    }
}

@Composable
private fun PauseOverlay(onResume: () -> Unit, onRestart: () -> Unit, onQuit: () -> Unit, settings: AppSettings, onToggleMusic: (Boolean) -> Unit, onToggleSfx: (Boolean) -> Unit) {
    Overlay {
        Text("Paused", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Music", Modifier.weight(1f)); Switch(settings.musicVolume > 0f, onToggleMusic) }
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Sound effects", Modifier.weight(1f)); Switch(settings.sfxVolume > 0f, onToggleSfx) }
        Spacer(Modifier.height(12.dp))
        CozyButton("Resume", onResume, Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        CozyButton("Restart level", onRestart, Modifier.fillMaxWidth(), color = Palette.Lavender, textColor = Palette.Ink)
        Spacer(Modifier.height(8.dp))
        CozyButton("Back to map", onQuit, Modifier.fillMaxWidth(), color = Color.White, textColor = Palette.Ink)
        Text("Leaving costs nothing – there are no lives in this game.", style = MaterialTheme.typography.bodySmall, color = Palette.InkSoft, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp))
    }
}

@Composable
private fun WinOverlay(number: Int, state: LevelUiState, onNext: () -> Unit, onMap: () -> Unit, onOpenBoxes: () -> Unit) {
    Overlay {
        Text("Level $number complete!", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(3) { i ->
                val shown by animateFloatAsState(if (state.reward != null && i < state.stars) 1f else 0.6f, tween(300, delayMillis = 200 * i), label = "star$i")
                StarIcon(i < state.stars, Modifier.size(48.dp).scale(shown))
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("Score ${state.score}", style = MaterialTheme.typography.titleMedium)
        val r = state.reward
        if (r == null) {
            Text("Saving rewards…", style = MaterialTheme.typography.bodySmall, color = Palette.InkSoft)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                CurrencyIcon(Currency.COINS, Modifier.size(26.dp))
                Text(" +${r.coins}", style = MaterialTheme.typography.titleLarge)
                if (r.eventBonusPercent > 0) Text("  (event +${r.eventBonusPercent}%)", style = MaterialTheme.typography.bodySmall, color = Palette.SakuraDeep)
            }
            if (r.lootboxes.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    r.lootboxes.forEach { b ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            LootboxArt(b.tier, Modifier.size(64.dp), glow = 0.6f)
                            Text("${b.tier.name.lowercase().replaceFirstChar { it.uppercase() }} box", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                Text("First-clear reward", style = MaterialTheme.typography.labelSmall, color = Palette.InkSoft)
            } else if (!r.firstClear) {
                Text("Replay bonus – the first-clear box was already collected.", style = MaterialTheme.typography.bodySmall, color = Palette.InkSoft, textAlign = TextAlign.Center)
            }
            r.chapterCompleted?.let {
                Text("Chapter $it complete! Bonus box added.", color = Palette.SakuraDeep, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
            }
        }
        Spacer(Modifier.height(16.dp))
        if (number < Chapters.TOTAL_LEVELS) CozyButton("Next level", onNext, Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        if ((r?.lootboxes?.isNotEmpty()) == true) {
            CozyButton("Open lootbox", onOpenBoxes, Modifier.fillMaxWidth(), color = Palette.Lavender, textColor = Palette.Ink)
            Spacer(Modifier.height(8.dp))
        }
        CozyButton("Map", onMap, Modifier.fillMaxWidth(), color = Color.White, textColor = Palette.Ink)
    }
}

@Composable
private fun LoseOverlay(state: LevelUiState, counts: Map<BoosterType, Int>, onRetry: (Set<BoosterType>) -> Unit, onMap: () -> Unit) {
    var picked by remember { mutableStateOf(setOf<BoosterType>()) }
    Overlay {
        Text("So close!", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        val reason = when (state.lossReason) {
            LossReason.NO_POSSIBLE_MOVES -> "The board ran out of possible moves."
            else -> "You ran out of moves."
        }
        val left = state.goals.filter { !it.done }.joinToString { "${it.remaining} ${goalName(it)}" }
        Text("$reason Still needed: $left.", textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(6.dp))
        Text("No lives, no waiting: try again right away. Boosters are optional.", textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall, color = Palette.InkSoft)
        Spacer(Modifier.height(10.dp))
        PreLevelBoosterPicker(counts, picked) { b -> picked = if (b in picked) picked - b else picked + b }
        Spacer(Modifier.height(14.dp))
        CozyButton(if (picked.isEmpty()) "Try again" else "Try again with ${picked.size} booster(s)", { onRetry(picked) }, Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        CozyButton("Map", onMap, Modifier.fillMaxWidth(), color = Color.White, textColor = Palette.Ink)
    }
}

fun goalName(g: GoalProgress) = when (g.goal.type) {
    GoalType.COLLECT_COLOR -> TileStyle.names.getOrElse(g.goal.color) { "tiles" }.lowercase()
    GoalType.CLEAR_ICE -> "ice"
    GoalType.BREAK_BOXES -> "crates"
    GoalType.BREAK_STONES -> "stones"
    GoalType.BREAK_CHAINS -> "chains"
    GoalType.CLEAR_WEBS -> "webs"
    GoalType.DELIVER_DROPS -> "charms"
    GoalType.OPEN_LOCKS -> "locks"
    GoalType.SCORE -> "points"
}

@Composable
fun PreLevelBoosterPicker(counts: Map<BoosterType, Int>, picked: Set<BoosterType>, onToggle: (BoosterType) -> Unit) {
    val pre = BoosterType.entries.filter { it.kind == BoosterKind.PRE_LEVEL }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        pre.forEach { b ->
            val n = counts[b] ?: 0
            val sel = b in picked
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(56.dp)) {
                Box(
                    Modifier.size(48.dp).clip(CircleShape)
                        .background(if (sel) Palette.SakuraDeep.copy(alpha = 0.25f) else Color.White)
                        .border(2.dp, if (sel) Palette.SakuraDeep else Color.Black.copy(alpha = 0.08f), CircleShape)
                        .clickable(enabled = n > 0 || sel) { onToggle(b) },
                    contentAlignment = Alignment.Center,
                ) {
                    BoosterIcon(b, Modifier.size(36.dp))
                    CountBadge(n, Modifier.align(Alignment.TopEnd), color = if (n > 0) Palette.SakuraDeep else Color.Gray)
                }
                Text(b.title, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 2)
            }
        }
    }
}
