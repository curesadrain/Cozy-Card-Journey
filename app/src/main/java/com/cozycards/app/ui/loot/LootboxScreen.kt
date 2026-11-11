package com.cozycards.app.ui.loot

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.rotate as drawRotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cozycards.app.audio.Sfx
import com.cozycards.app.container
import com.cozycards.app.ui.common.CardThumbnail
import com.cozycards.app.ui.common.CozyButton
import com.cozycards.app.ui.common.EmptyState
import com.cozycards.app.ui.common.LootboxArt
import com.cozycards.app.ui.common.Panel
import com.cozycards.app.ui.common.RarityBadge
import com.cozycards.app.ui.map.tierName
import com.cozycards.app.ui.theme.Palette
import com.cozycards.game.OpenResult
import com.cozycards.game.cards.Rarity
import com.cozycards.game.economy.CosmeticSlot
import com.cozycards.game.loot.LootboxOdds
import com.cozycards.game.state.CardDraw
import com.cozycards.levels.LootboxTier
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun LootboxScreen(onCard: (String) -> Unit) {
    val container = LocalContext.current.container
    val player by container.repository.state.collectAsState()
    val scope = rememberCoroutineScope()
    var opening by remember { mutableStateOf<List<OpenResult>?>(null) }
    var showOdds by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun open(block: suspend () -> List<OpenResult>) {
        if (busy) return
        busy = true
        scope.launch {
            // Cards are granted inside the transaction before any animation plays.
            runCatching { block() }.onSuccess { if (it.isNotEmpty()) opening = it }.onFailure { error = it.message }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().background(Palette.Cream).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Lootboxes", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            CozyButton("History", { showHistory = true }, small = true, color = Palette.Lavender, enabled = player.recentOpened.isNotEmpty())
            Spacer(Modifier.width(8.dp))
            CozyButton("Odds", { showOdds = true }, small = true, color = Palette.Lavender, textColor = Palette.Ink)
        }
        LazyColumn(contentPadding = com.cozycards.app.ui.common.screenPadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Panel(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text("Pity protection", style = MaterialTheme.typography.titleSmall)
                        Text("Epic or better guaranteed in ${LootboxOdds.EPIC_PITY - player.pity.sinceEpicPlus} card(s) at the latest.", style = MaterialTheme.typography.bodySmall)
                        Text("Legendary guaranteed in ${LootboxOdds.LEGENDARY_PITY - player.pity.sinceLegendary} card(s) at the latest.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            val boxes = player.lootboxes
            if (boxes.isEmpty()) item { EmptyState("No unopened boxes. Clear new levels to earn one every time!") }
            else item {
                Panel(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text("${boxes.size} unopened box(es)", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            LootboxTier.entries.forEach { t ->
                                val n = boxes.count { it.tier == t }
                                if (n > 0) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    LootboxArt(t, Modifier.size(56.dp))
                                    Text("${tierName(t)} ×$n", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        CozyButton(if (busy) "Opening…" else "Open all (${minOf(boxes.size, 50)})", { open { container.repository.service.openAll() } }, Modifier.fillMaxWidth(), enabled = !busy)
                    }
                }
            }
            items(boxes, key = { it.id }) { box ->
                Panel(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        LootboxArt(box.tier, Modifier.size(54.dp))
                        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                            Text("${tierName(box.tier)} box · ${box.tier.cardCount} cards", style = MaterialTheme.typography.titleSmall)
                            Text(box.source + (box.collectionId?.let { " · ${container.content.catalog.collection(it)?.name}" } ?: ""), style = MaterialTheme.typography.bodySmall, color = Palette.InkSoft)
                        }
                        CozyButton("Open", { open { listOf(container.repository.service.openLootbox(box.id)) } }, small = true, enabled = !busy)
                    }
                }
            }
        }
    }
    opening?.let { results -> OpeningOverlay(results, onCard = { opening = null; onCard(it) }, onDone = { opening = null }) }
    if (showOdds) OddsDialog { showOdds = false }
    if (showHistory) androidx.compose.ui.window.Dialog(onDismissRequest = { showHistory = false }) {
        Panel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Recently opened", style = MaterialTheme.typography.titleLarge, color = Palette.Ink)
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(player.recentOpened, key = { "h${it.boxId}" }) { o ->
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0x14000000)).padding(10.dp)) {
                            Text("${tierName(o.tier)} box · ${o.source}", style = MaterialTheme.typography.labelLarge, color = Palette.Ink)
                            Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(o.openedAt)), style = MaterialTheme.typography.labelSmall, color = Palette.InkSoft)
                            Spacer(Modifier.height(4.dp))
                            o.draws.forEach { d ->
                                val c = container.content.catalog[d.cardId]
                                Row(Modifier.clickable { showHistory = false; onCard(d.cardId) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                    RarityBadge(d.rarity)
                                    Text("  ${c?.name ?: d.cardId}" + if (d.isNew) "  · NEW" else "", style = MaterialTheme.typography.bodySmall, color = Palette.Ink)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                CozyButton("Close", { showHistory = false }, Modifier.fillMaxWidth(), color = Palette.Lavender)
            }
        }
    }
    error?.let { AlertDialog(onDismissRequest = { error = null }, confirmButton = { TextButton({ error = null }) { Text("OK") } }, text = { Text(it) }) }
}

@Composable
fun OddsDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onDismiss) { Text("Close") } },
        title = { Text("Drop rates") },
        text = {
            LazyColumn {
                items(LootboxTier.entries) { t ->
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Text("${tierName(t)} box – ${t.cardCount} cards", style = MaterialTheme.typography.titleSmall)
                        LootboxOdds.oddsFor(t).forEach { (r, p) -> Text("${r.label}: %.1f%% per card".format(p * 100), style = MaterialTheme.typography.bodySmall) }
                        LootboxOdds.guarantee(t)?.let { Text("Guarantee: at least one ${it.label}${if (it == Rarity.EPIC) " or better" else ""} per box", style = MaterialTheme.typography.bodySmall, color = Palette.SakuraDeep) }
                    }
                }
                item {
                    Text(
                        "Pity: if ${LootboxOdds.EPIC_PITY - 1} cards in a row are below Epic, the next one is at least Epic. " +
                            "If ${LootboxOdds.LEGENDARY_PITY - 1} cards in a row are not Legendary, the next one is Legendary. " +
                            "Within a rarity every card of the eligible collections is equally likely. " +
                            "Box contents are fixed when the box is earned; nothing is ever sold for real money.",
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
    )
}

/**
 * Opening animation. The cards were already granted atomically; this is presentation only, so
 * closing the app mid-animation never loses anything (results remain in the History).
 */
@Composable
private fun OpeningOverlay(results: List<OpenResult>, onCard: (String) -> Unit, onDone: () -> Unit) {
    com.cozycards.app.ui.common.HideNavBar()
    val container = LocalContext.current.container
    val player by container.repository.state.collectAsState()
    val draws: List<CardDraw> = results.flatMap { it.draws }
    val revealed = remember { mutableStateListOf<Boolean>().apply { addAll(List(draws.size) { false }) } }
    var phase by remember { mutableStateOf(0) } // 0 shaking, 1 lid, 2 cards
    val shake = remember { Animatable(0f) }
    val tier = results.maxOf { it.box.tier }
    LaunchedEffect(Unit) {
        container.audio.play(Sfx.BOX_OPEN)
        repeat(6) { i -> shake.animateTo(if (i % 2 == 0) 8f else -8f, tween(70)) }
        shake.animateTo(0f, tween(70))
        phase = 1
        delay(450)
        phase = 2
    }
    val transition = rememberInfiniteTransition(label = "rays")
    val spin by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(12000, easing = LinearEasing), RepeatMode.Restart), label = "spin")
    val lid by animateFloatAsState(if (phase >= 1) 1f else 0f, tween(400), label = "lid")
    val frame = player.equippedOrDefault(CosmeticSlot.CARD_FRAME)

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Palette.Night, Palette.NightSoft))).clickable(enabled = true) {}) {
        Canvas(Modifier.fillMaxSize().rotate(spin)) {
            for (i in 0 until 12) {
                drawRotate(i * 30f) {
                    drawRect(Color.White.copy(alpha = 0.04f), topLeft = androidx.compose.ui.geometry.Offset(center.x - 40f, 0f), size = androidx.compose.ui.geometry.Size(80f, center.y))
                }
            }
        }
        if (phase < 2) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                LootboxArt(tier, Modifier.size(220.dp).graphicsLayer { rotationZ = shake.value }, lidOpen = lid, glow = lid)
            }
        } else {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(12.dp)) {
                Text(if (results.size > 1) "${results.size} boxes opened" else "${tierName(tier)} box", style = MaterialTheme.typography.headlineSmall, color = Color.White)
                Text("Tap a card to reveal it", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f))
                val albums = results.flatMap { it.completedAlbums }
                if (albums.isNotEmpty()) Text("Album complete! +tokens & coins", color = Palette.Gold, style = MaterialTheme.typography.titleSmall)
                LazyVerticalGrid(GridCells.Adaptive(100.dp), Modifier.weight(1f).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    itemsIndexed(draws) { i, d ->
                        RevealCard(d, revealed[i], frame, onReveal = {
                            if (!revealed[i]) {
                                revealed[i] = true
                                container.audio.play(if (d.rarity.atLeast(Rarity.EPIC)) Sfx.RARE_CARD else Sfx.TAP)
                            } else onCard(d.cardId)
                        })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (revealed.any { !it }) CozyButton("Reveal all", {
                        val anyRare = draws.indices.any { !revealed[it] && draws[it].rarity.atLeast(Rarity.EPIC) }
                        for (i in revealed.indices) revealed[i] = true
                        container.audio.play(if (anyRare) Sfx.RARE_CARD else Sfx.SWISH)
                    }, Modifier.weight(1f), color = Palette.Lavender, textColor = Palette.Ink)
                    CozyButton("Done", onDone, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun RevealCard(d: CardDraw, revealed: Boolean, frame: com.cozycards.game.economy.Cosmetic, onReveal: () -> Unit) {
    val container = LocalContext.current.container
    val card = container.content.catalog[d.cardId] ?: return
    val flip by animateFloatAsState(if (revealed) 180f else 0f, tween(500), label = "flip")
    val glowColor = Palette.rarity(d.rarity)
    val glow = rememberInfiniteTransition(label = "glow")
    val pulse by glow.animateFloat(0.4f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse")
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .graphicsLayer { rotationY = flip; cameraDistance = 14f * density }
                .clickable(onClick = onReveal),
            contentAlignment = Alignment.Center,
        ) {
            if (flip < 90f) {
                Box(
                    Modifier.fillMaxWidth().height(140.dp).clip(RoundedCornerShape(10.dp))
                        .background(Brush.verticalGradient(listOf(glowColor.copy(alpha = 0.9f), Palette.NightSoft))),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("?", style = MaterialTheme.typography.displaySmall, color = Color.White.copy(alpha = 0.8f))
                }
            } else {
                Box(Modifier.graphicsLayer { rotationY = 180f }) {
                    if (d.rarity.atLeast(Rarity.EPIC)) Box(Modifier.matchParentSize().scale(1.08f).background(glowColor.copy(alpha = 0.35f * pulse), RoundedCornerShape(12.dp)))
                    CardThumbnail(card, true, Modifier.fillMaxWidth(), widthPx = 300, frame = frame)
                }
            }
        }
        if (revealed) {
            Text(card.name, style = MaterialTheme.typography.labelSmall, color = Color.White, maxLines = 1, textAlign = TextAlign.Center)
            Row {
                RarityBadge(d.rarity)
                if (d.isNew) Text(" NEW", color = Palette.Gold, style = MaterialTheme.typography.labelSmall)
            }
            if (d.pityTriggered || d.guaranteed) Text(if (d.guaranteed) "guaranteed" else "pity", color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall)
        }
    }
}
