package com.cozycards.app.ui.shop

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.cozycards.app.audio.Sfx
import com.cozycards.app.cards.CardArt
import com.cozycards.app.container
import com.cozycards.app.data.PlayerState
import com.cozycards.app.ui.common.BoosterArt
import com.cozycards.app.ui.common.CardThumbnail
import com.cozycards.app.ui.common.CozyButton
import com.cozycards.app.ui.common.CozyChip
import com.cozycards.app.ui.common.Currency
import com.cozycards.app.ui.common.CurrencyChip
import com.cozycards.app.ui.common.CurrencyIcon
import com.cozycards.app.ui.common.LootboxArt
import com.cozycards.app.ui.common.Panel
import com.cozycards.app.ui.common.screenPadding
import com.cozycards.app.ui.gfx.Sprites
import com.cozycards.app.ui.gfx.drawImageCentered
import com.cozycards.app.ui.loot.OddsDialog
import com.cozycards.app.ui.theme.Palette
import com.cozycards.game.economy.Cosmetic
import com.cozycards.game.economy.CosmeticSlot
import com.cozycards.game.economy.ShopCatalog
import com.cozycards.game.economy.ShopItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class ShopTab(val label: String) { CHESTS("Chests"), BOOSTERS("Boosters"), STYLES("Styles"), EXCHANGE("Exchange") }

@Composable
fun ShopScreen() {
    val container = LocalContext.current.container
    val player by container.repository.state.collectAsState()
    val scope = rememberCoroutineScope()
    val catalog = container.content.catalog
    val items = remember { ShopCatalog.items(catalog.collections.map { it.id }) }
    var tab by remember { mutableStateOf(ShopTab.CHESTS) }
    var confirm by remember { mutableStateOf<ShopItem?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var odds by remember { mutableStateOf(false) }

    fun titleOf(item: ShopItem) = when (item) {
        is ShopItem.Box -> item.collectionId?.let { "${catalog.collection(it)?.name ?: it} Box" } ?: item.title
        is ShopItem.Booster -> "${item.type.title} ×${item.amount}"
        else -> item.title
    }

    Column(Modifier.fillMaxSize().background(Palette.Cream).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Shop", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f), color = Palette.Ink)
            CurrencyChip(Currency.COINS, player.wallet.coins)
            CurrencyChip(Currency.TOKENS, player.wallet.tokens)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ShopTab.entries.forEach { t -> CozyChip(tab == t, { tab = t }, { Text(t.label, maxLines = 1) }, modifier = Modifier.weight(1f), compact = true) }
        }
        LazyColumn(contentPadding = screenPadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (tab) {
                ShopTab.CHESTS -> {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Bought with coins or tokens earned in play. No real-money purchases.", style = MaterialTheme.typography.bodySmall, color = Palette.InkSoft, modifier = Modifier.weight(1f))
                            CozyButton("Odds", { odds = true }, small = true, color = Palette.Lavender)
                        }
                    }
                    items(items.filterIsInstance<ShopItem.Box>(), key = { it.id }) { item ->
                        ShopRow(titleOf(item), item.description, player, listOf(item)) { confirm = it }
                    }
                }
                ShopTab.BOOSTERS -> {
                    // One row per booster with both pack sizes.
                    val byType = items.filterIsInstance<ShopItem.Booster>().groupBy { it.type }
                    items(byType.keys.toList(), key = { it.name }) { type ->
                        val packs = byType[type]!!.sortedBy { it.amount }
                        ShopRow(type.title, "${type.description}  ·  You have ${player.boosters[type] ?: 0}", player, packs) { confirm = it }
                    }
                }
                ShopTab.STYLES -> {
                    val styles = items.filterIsInstance<ShopItem.Style>()
                    for (slot in CosmeticSlot.entries) {
                        val list = styles.filter { it.cosmetic.slot == slot }
                        if (list.isEmpty()) continue
                        item(key = "h$slot") {
                            Text(
                                when (slot) { CosmeticSlot.CARD_BACK -> "Card backs"; CosmeticSlot.BOARD_THEME -> "Board themes"; CosmeticSlot.CARD_FRAME -> "Card frames" },
                                style = MaterialTheme.typography.titleLarge, color = Palette.Ink, modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        items(list, key = { it.id }) { item ->
                            val owned = item.cosmetic in player.ownedCosmetics
                            ShopRow(item.title, item.description + if (owned) "\nOwned – equip it in Profile › Settings" else "", player, if (owned) emptyList() else listOf(item), preview = { CosmeticPreview(item.cosmetic) }) { confirm = it }
                        }
                    }
                }
                ShopTab.EXCHANGE -> {
                    items(items.filterIsInstance<ShopItem.CoinPack>(), key = { it.id }) { item ->
                        ShopRow(item.title, item.description, player, listOf(item), preview = { CurrencyIcon(Currency.COINS, Modifier.size(52.dp)) }) { confirm = it }
                    }
                    item {
                        Text("Missing a particular card? Open it in the Collection to exchange tokens for it directly.", style = MaterialTheme.typography.bodySmall, color = Palette.InkSoft, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        }
    }
    confirm?.let { item ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Buy ${titleOf(item)}?") },
            text = { Text(priceText(item) + "\n" + item.description) },
            confirmButton = {
                TextButton({
                    confirm = null
                    scope.launch {
                        message = runCatching {
                            val box = container.repository.service.buy(item)
                            container.audio.play(Sfx.COIN)
                            if (box != null) "Chest added to your Lootboxes." else "Purchased!"
                        }.getOrElse { it.message }
                    }
                }) { Text("Buy") }
            },
            dismissButton = { TextButton({ confirm = null }) { Text("Cancel") } },
        )
    }
    message?.let { AlertDialog(onDismissRequest = { message = null }, confirmButton = { TextButton({ message = null }) { Text("OK") } }, text = { Text(it) }) }
    if (odds) OddsDialog { odds = false }
}

private fun priceText(item: ShopItem) = buildString {
    append("Price: ")
    if (item.coins > 0) append("${item.coins} coins")
    if (item.coins > 0 && item.tokens > 0) append(" + ")
    if (item.tokens > 0) append("${item.tokens} tokens")
}

/**
 * One product: preview on the left, text in the middle and one buy button per offer
 * (e.g. ×1 and ×5 for boosters) on the right.
 */
@Composable
private fun ShopRow(
    title: String,
    description: String,
    player: PlayerState,
    offers: List<ShopItem>,
    preview: (@Composable () -> Unit)? = null,
    onBuy: (ShopItem) -> Unit,
) {
    Panel(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(60.dp), contentAlignment = Alignment.Center) {
                when {
                    preview != null -> preview()
                    offers.firstOrNull() is ShopItem.Box -> LootboxArt((offers.first() as ShopItem.Box).tier, Modifier.size(56.dp))
                    offers.firstOrNull() is ShopItem.Booster -> BoosterArt((offers.first() as ShopItem.Booster).type, Modifier.size(48.dp))
                }
            }
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = Palette.Ink)
                Text(description, style = MaterialTheme.typography.bodySmall, color = Palette.InkSoft)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                offers.forEach { item ->
                    val affordable = player.wallet.coins >= item.coins && player.wallet.tokens >= item.tokens
                    val label = buildString {
                        if (item is ShopItem.Booster) append("×${item.amount}  ")
                        append(if (item.coins > 0) "${item.coins}" else "${item.tokens}")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CurrencyIcon(if (item.coins > 0) Currency.COINS else Currency.TOKENS, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        CozyButton(label, { onBuy(item) }, small = true, enabled = affordable, modifier = Modifier.width(if (item is ShopItem.Booster) 100.dp else 92.dp))
                    }
                }
            }
        }
    }
}

/** Small visual preview of a cosmetic. */
@Composable
private fun CosmeticPreview(c: Cosmetic) {
    val container = LocalContext.current.container
    when (c.slot) {
        CosmeticSlot.CARD_BACK -> {
            var bmp by remember(c) { mutableStateOf<Bitmap?>(null) }
            LaunchedEffect(c) { bmp = withContext(Dispatchers.Default) { CardArt.renderBack(c, 120, 168) } }
            bmp?.let { Image(it.asImageBitmap(), c.title, Modifier.height(58.dp), contentScale = ContentScale.Fit) }
        }
        CosmeticSlot.CARD_FRAME -> {
            val sample = remember { container.content.catalog.cards.first() }
            CardThumbnail(sample, true, Modifier.height(58.dp), widthPx = 160, frame = c)
        }
        CosmeticSlot.BOARD_THEME -> {
            val bg = when (c) {
                Cosmetic.BOARD_PAPER -> Color(0xFFF1E3C6)
                Cosmetic.BOARD_NIGHT -> Color(0xFF1C1836)
                Cosmetic.BOARD_PASTEL -> Color(0xFFF9D9E6)
                else -> Color(0xFFE9D7EF)
            }
            Canvas(Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(bg)) {
                val cell = size.width / 2
                for (i in 0 until 4) {
                    val o = Offset((i % 2) * cell, (i / 2) * cell)
                    drawImageCentered(Sprites[if ((i % 2 + i / 2) % 2 == 0) "cell_light" else "cell_pink"], o + Offset(cell / 2, cell / 2), cell * 0.92f, 0.9f, null, false)
                    drawImageCentered(Sprites.tile(i), o + Offset(cell / 2, cell / 2), cell * 0.75f, 1f, null, false)
                }
            }
        }
    }
}
