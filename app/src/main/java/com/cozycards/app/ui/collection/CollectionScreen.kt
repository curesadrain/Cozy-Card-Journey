package com.cozycards.app.ui.collection

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cozycards.app.container
import com.cozycards.app.data.PlayerState
import com.cozycards.app.ui.common.CardThumbnail
import com.cozycards.app.ui.common.CozyButton
import com.cozycards.app.ui.common.Currency
import com.cozycards.app.ui.common.CurrencyChip
import com.cozycards.app.ui.common.EmptyState
import com.cozycards.app.ui.common.Panel
import com.cozycards.app.ui.common.ProgressBar
import com.cozycards.app.ui.theme.Palette
import com.cozycards.game.cards.CardDef
import com.cozycards.game.cards.CollectionDef
import com.cozycards.game.cards.Rarity
import com.cozycards.game.economy.CosmeticSlot
import com.cozycards.game.economy.Economy
import kotlinx.coroutines.launch

enum class OwnedFilter(val label: String) { ALL("All"), OWNED("Owned"), MISSING("Missing"), DUPLICATES("Duplicates") }
enum class CardSort(val label: String) { ALBUM("Album order"), RARITY("Rarity"), NAME("Name"), RECENT("Recently obtained"), DUPLICATES("Most duplicates") }

@Composable
fun CollectionScreen(onCard: (String) -> Unit) {
    val container = LocalContext.current.container
    val player by container.repository.state.collectAsState()
    val catalog = container.content.catalog
    val scope = rememberCoroutineScope()
    var collectionId by remember { mutableStateOf(catalog.collections.first().id) }
    var query by remember { mutableStateOf("") }
    var rarity by remember { mutableStateOf<Rarity?>(null) }
    var owned by remember { mutableStateOf(OwnedFilter.ALL) }
    var album by remember { mutableStateOf<String?>(null) }
    var favorites by remember { mutableStateOf(false) }
    var sort by remember { mutableStateOf(CardSort.ALBUM) }
    var sortMenu by remember { mutableStateOf(false) }
    var albumMenu by remember { mutableStateOf(false) }
    var recycleConfirm by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<String?>(null) }

    val collection = catalog.collection(collectionId)!!
    val all = catalog.inCollection(collectionId)
    val filtered = remember(collectionId, query, rarity, owned, album, favorites, sort, player.cards) {
        all.asSequence()
            .filter { query.isBlank() || it.name.contains(query, true) || it.series.contains(query, true) || it.description.contains(query, true) }
            .filter { rarity == null || it.gameRarity == rarity }
            .filter { album == null || collection.albums.first { a -> a.id == album }.cardIds.contains(it.id) }
            .filter {
                val o = player.cards[it.id]
                when (owned) {
                    OwnedFilter.ALL -> true
                    OwnedFilter.OWNED -> o != null
                    OwnedFilter.MISSING -> o == null
                    OwnedFilter.DUPLICATES -> (o?.duplicates ?: 0) > 0
                }
            }
            .filter { !favorites || player.cards[it.id]?.favorite == true }
            .toList()
            .let { list ->
                when (sort) {
                    CardSort.ALBUM -> list
                    CardSort.RARITY -> list.sortedByDescending { it.gameRarity.ordinal }
                    CardSort.NAME -> list.sortedBy { it.name }
                    CardSort.RECENT -> list.sortedByDescending { player.cards[it.id]?.lastObtainedAt ?: 0 }
                    CardSort.DUPLICATES -> list.sortedByDescending { player.cards[it.id]?.duplicates ?: 0 }
                }
            }
    }
    val frame = player.equippedOrDefault(CosmeticSlot.CARD_FRAME)
    val safeTokens = player.cards.values.sumOf { o ->
        val c = catalog[o.cardId] ?: return@sumOf 0L
        if (o.favorite || c.gameRarity.atLeast(Rarity.EPIC)) 0L else Economy.recycleValue(c.gameRarity) * o.duplicates
    }

    Column(Modifier.fillMaxSize().background(Palette.Cream).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Collection", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            CurrencyChip(Currency.TOKENS, player.wallet.tokens)
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            catalog.collections.forEach { c ->
                val ownedCount = catalog.inCollection(c.id).count { player.cards.containsKey(it.id) }
                val total = catalog.inCollection(c.id).size
                CollectionTab(c, ownedCount, total, c.id == collectionId) { collectionId = c.id; album = null }
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(104.dp),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Text(collection.description, style = MaterialTheme.typography.bodySmall, color = Palette.InkSoft)
                    Text(collection.disclaimer, style = MaterialTheme.typography.labelSmall, color = Palette.InkSoft.copy(alpha = 0.8f), modifier = Modifier.padding(top = 2.dp))
                    Spacer(Modifier.height(8.dp))
                    collection.albums.forEach { a ->
                        val have = a.cardIds.count { player.cards.containsKey(it) }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                            Text(a.name, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(140.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            ProgressBar(have.toFloat() / a.cardIds.size, Modifier.weight(1f), color = if (have == a.cardIds.size) Palette.Matcha else Palette.SakuraDeep)
                            Text("  $have/${a.cardIds.size}", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Text("Completing an album gives ${Economy.ALBUM_COMPLETE_COINS} coins + ${Economy.ALBUM_COMPLETE_TOKENS} tokens; a full collection gives a Legendary box.", style = MaterialTheme.typography.labelSmall, color = Palette.InkSoft, modifier = Modifier.padding(top = 4.dp))
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        leadingIcon = { Icon(Icons.Rounded.Search, null) },
                        placeholder = { Text("Search cards") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(50),
                    )
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(rarity == null, { rarity = null }, { Text("Any rarity") })
                        Rarity.entries.forEach { r -> FilterChip(rarity == r, { rarity = if (rarity == r) null else r }, { Text(r.label) }) }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OwnedFilter.entries.forEach { f -> FilterChip(owned == f, { owned = f }, { Text(f.label) }) }
                        FilterChip(favorites, { favorites = !favorites }, { Text("Favourites") }, leadingIcon = { Icon(Icons.Rounded.Favorite, null) })
                    }
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box {
                            FilterChip(album != null, { albumMenu = true }, { Text(album?.let { id -> collection.albums.first { it.id == id }.name } ?: "All series") })
                            DropdownMenu(albumMenu, { albumMenu = false }) {
                                DropdownMenuItem({ Text("All series") }, { album = null; albumMenu = false })
                                collection.albums.forEach { a -> DropdownMenuItem({ Text(a.name) }, { album = a.id; albumMenu = false }) }
                            }
                        }
                        Box {
                            FilterChip(sort != CardSort.ALBUM, { sortMenu = true }, { Text("Sort: ${sort.label}") })
                            DropdownMenu(sortMenu, { sortMenu = false }) {
                                CardSort.entries.forEach { s -> DropdownMenuItem({ Text(s.label) }, { sort = s; sortMenu = false }) }
                            }
                        }
                    }
                    if (safeTokens > 0) {
                        Spacer(Modifier.height(6.dp))
                        CozyButton("Recycle Common & Rare duplicates (+$safeTokens tokens)", { recycleConfirm = true }, Modifier.fillMaxWidth(), color = Palette.Token, small = true)
                    }
                    Text("${filtered.size} card(s)", style = MaterialTheme.typography.labelSmall, color = Palette.InkSoft, modifier = Modifier.padding(top = 6.dp))
                }
            }
            if (filtered.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { EmptyState("No cards match these filters.") }
            items(filtered, key = { it.id }) { card -> CardCell(card, player, frame, onCard) }
        }
    }

    if (recycleConfirm) AlertDialog(
        onDismissRequest = { recycleConfirm = false },
        title = { Text("Recycle duplicates?") },
        text = { Text("All duplicate Common and Rare cards will become $safeTokens collector tokens. One copy of every card is always kept. Epic, Legendary and favourite cards are never touched here.") },
        confirmButton = {
            TextButton({
                recycleConfirm = false
                scope.launch { info = runCatching { "Received ${container.repository.service.recycleSafeDuplicates()} tokens" }.getOrElse { it.message } }
            }) { Text("Recycle") }
        },
        dismissButton = { TextButton({ recycleConfirm = false }) { Text("Cancel") } },
    )
    info?.let { AlertDialog(onDismissRequest = { info = null }, confirmButton = { TextButton({ info = null }) { Text("OK") } }, text = { Text(it) }) }
}

@Composable
private fun CollectionTab(c: CollectionDef, owned: Int, total: Int, selected: Boolean, onClick: () -> Unit) {
    val accent = Color(android.graphics.Color.parseColor(c.accentColor))
    Panel(Modifier.clickable(onClick = onClick), color = if (selected) accent.copy(alpha = 0.22f) else Color.White) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp).width(120.dp)) {
            Text(c.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            ProgressBar(owned.toFloat() / total.coerceAtLeast(1), Modifier.fillMaxWidth(), color = accent)
            Text("$owned / $total collected", style = MaterialTheme.typography.labelSmall, color = Palette.InkSoft)
        }
    }
}

@Composable
private fun CardCell(card: CardDef, player: PlayerState, frame: com.cozycards.game.economy.Cosmetic, onCard: (String) -> Unit) {
    val o = player.cards[card.id]
    Column(Modifier.clickable { onCard(card.id) }) {
        Box {
            CardThumbnail(card, o != null, Modifier.fillMaxWidth(), widthPx = 300, frame = frame)
            if ((o?.duplicates ?: 0) > 0) {
                Text("×${o!!.count}", Modifier.align(Alignment.TopEnd).padding(4.dp).clip(RoundedCornerShape(50)).background(Palette.Ink.copy(alpha = 0.8f)).padding(horizontal = 6.dp, vertical = 1.dp),
                    color = Color.White, style = MaterialTheme.typography.labelSmall)
            }
            if (o?.favorite == true) Icon(Icons.Rounded.Favorite, "favourite", tint = Palette.SakuraDeep, modifier = Modifier.align(Alignment.TopStart).padding(4.dp).width(16.dp))
        }
        Text(if (o != null) card.name else "???", style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(card.gameRarity.label, style = MaterialTheme.typography.labelSmall, color = Palette.rarity(card.gameRarity))
    }
}
