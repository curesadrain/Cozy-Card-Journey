package com.cozycards.app.ui.shop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.cozycards.app.audio.Sfx
import com.cozycards.app.container
import com.cozycards.app.data.PlayerState
import com.cozycards.app.ui.common.BoosterIcon
import com.cozycards.app.ui.common.CozyButton
import com.cozycards.app.ui.common.Currency
import com.cozycards.app.ui.common.CurrencyChip
import com.cozycards.app.ui.common.CurrencyIcon
import com.cozycards.app.ui.common.LootboxArt
import com.cozycards.app.ui.common.Panel
import com.cozycards.app.ui.common.SectionTitle
import com.cozycards.app.ui.loot.OddsDialog
import com.cozycards.app.ui.theme.Palette
import com.cozycards.game.economy.ShopCatalog
import com.cozycards.game.economy.ShopItem
import kotlinx.coroutines.launch

@Composable
fun ShopScreen() {
    val container = LocalContext.current.container
    val player by container.repository.state.collectAsState()
    val scope = rememberCoroutineScope()
    val items = remember { ShopCatalog.items(container.content.catalog.collections.map { it.id }) }
    var confirm by remember { mutableStateOf<ShopItem?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var odds by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(Palette.Cream).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Shop", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            CurrencyChip(Currency.COINS, player.wallet.coins)
            CurrencyChip(Currency.TOKENS, player.wallet.tokens)
        }
        Text("Everything here is bought with coins or tokens earned in play. There are no real-money purchases.",
            style = MaterialTheme.typography.bodySmall, color = Palette.InkSoft, modifier = Modifier.padding(horizontal = 14.dp))
        LazyColumn(contentPadding = com.cozycards.app.ui.common.screenPadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { SectionTitle("Lootboxes") { CozyButton("Odds", { odds = true }, small = true, color = Palette.Lavender, textColor = Palette.Ink) } }
            items(items.filterIsInstance<ShopItem.Box>()) { item ->
                ShopRow(item, player, onBuy = { confirm = item }) {
                    LootboxArt(item.tier, Modifier.size(48.dp))
                }
            }
            item { SectionTitle("Boosters") }
            items(items.filterIsInstance<ShopItem.Booster>()) { item ->
                ShopRow(item, player, onBuy = { confirm = item }, owned = "You have ${player.boosters[item.type] ?: 0}") {
                    com.cozycards.app.ui.common.BoosterArt(item.type, Modifier.size(44.dp))
                }
            }
            item { SectionTitle("Cosmetics") }
            items(items.filterIsInstance<ShopItem.Style>()) { item ->
                val have = item.cosmetic in player.ownedCosmetics
                ShopRow(item, player, onBuy = { confirm = item }, owned = if (have) "Owned – equip in Profile" else null, disabled = have) {
                    Text(item.cosmetic.slot.name.replace('_', ' ').lowercase(), style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(48.dp))
                }
            }
            item { SectionTitle("Exchange") }
            items(items.filterIsInstance<ShopItem.CoinPack>()) { item ->
                ShopRow(item, player, onBuy = { confirm = item }) { CurrencyIcon(Currency.COINS, Modifier.size(40.dp)) }
            }
            item {
                Text("Missing a particular card? Open it in the Collection to exchange tokens for it directly.", style = MaterialTheme.typography.bodySmall, color = Palette.InkSoft, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
    confirm?.let { item ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Buy ${item.title}?") },
            text = { Text(priceText(item) + "\n" + item.description) },
            confirmButton = {
                TextButton({
                    confirm = null
                    scope.launch {
                        message = runCatching {
                            val box = container.repository.service.buy(item)
                            container.audio.play(Sfx.COIN)
                            if (box != null) "Box added to your Lootboxes." else "Purchased!"
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

@Composable
private fun ShopRow(item: ShopItem, player: PlayerState, onBuy: () -> Unit, owned: String? = null, disabled: Boolean = false, icon: @Composable () -> Unit) {
    val affordable = player.wallet.coins >= item.coins && player.wallet.tokens >= item.tokens
    Panel(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            icon()
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(item.title, style = MaterialTheme.typography.titleSmall)
                Text(item.description, style = MaterialTheme.typography.bodySmall, color = Palette.InkSoft)
                owned?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Palette.SakuraDeep) }
            }
            Column(horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (item.coins > 0) { CurrencyIcon(Currency.COINS, Modifier.size(16.dp)); Text(" ${item.coins}", style = MaterialTheme.typography.labelLarge) }
                    if (item.tokens > 0) { Spacer(Modifier.width(4.dp)); CurrencyIcon(Currency.TOKENS, Modifier.size(16.dp)); Text(" ${item.tokens}", style = MaterialTheme.typography.labelLarge) }
                }
                Spacer(Modifier.height(4.dp))
                CozyButton("Buy", onBuy, small = true, enabled = affordable && !disabled, color = if (affordable) Palette.SakuraDeep else Color.Gray)
            }
        }
    }
}
