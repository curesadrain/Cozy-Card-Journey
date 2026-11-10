package com.cozycards.app.ui.collection

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.cozycards.app.cards.GlCardLayers
import com.cozycards.app.container
import com.cozycards.app.data.AppSettings
import com.cozycards.app.data.EffectsQuality
import com.cozycards.app.renderer.CardViewer
import com.cozycards.app.renderer.CardViewerController
import com.cozycards.app.renderer.RenderQuality
import com.cozycards.app.ui.common.CardThumbnail
import com.cozycards.app.ui.common.CozyButton
import com.cozycards.app.ui.common.Panel
import com.cozycards.app.ui.common.RarityBadge
import com.cozycards.app.ui.theme.Palette
import com.cozycards.game.cards.Rarity
import com.cozycards.game.economy.CosmeticSlot
import com.cozycards.game.economy.Economy
import com.cozycards.game.state.CardHistoryEntry
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

fun EffectsQuality.toRender(auto: EffectsQuality): RenderQuality = when (if (this == EffectsQuality.AUTO) auto else this) {
    EffectsQuality.LOW -> RenderQuality.LOW
    EffectsQuality.MEDIUM -> RenderQuality.MEDIUM
    else -> RenderQuality.HIGH
}

@Composable
fun CardDetailScreen(cardId: String, onBack: () -> Unit) {
    val container = LocalContext.current.container
    val catalog = container.content.catalog
    val card = catalog[cardId] ?: run { onBack(); return }
    val player by container.repository.state.collectAsState()
    val settings by container.settings.settings.collectAsState(initial = AppSettings())
    val owned = player.cards[cardId]
    val scope = rememberCoroutineScope()
    val controller = remember { CardViewerController() }
    var fullscreen by remember { mutableStateOf(false) }
    var hideUi by remember { mutableStateOf(false) }
    var glFailed by remember { mutableStateOf(false) }
    var imageVersion by remember { mutableIntStateOf(0) }
    var layers by remember { mutableStateOf<GlCardLayers?>(null) }
    var history by remember { mutableStateOf<List<CardHistoryEntry>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    var recycleDialog by remember { mutableStateOf(false) }
    var exchangeDialog by remember { mutableStateOf(false) }
    val quality = settings.quality.toRender(container.autoQuality)
    val back = player.equippedOrDefault(CosmeticSlot.CARD_BACK)
    val frame = player.equippedOrDefault(CosmeticSlot.CARD_FRAME)

    LaunchedEffect(cardId, owned != null, imageVersion, back, frame, quality) {
        if (owned != null) layers = container.bitmaps.glLayers(card, if (quality == RenderQuality.LOW) 512 else 720, frame, back)
    }
    LaunchedEffect(cardId, owned?.count) { history = container.repository.cardHistory(cardId) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { container.content.importCardImage(cardId, uri) }
            container.bitmaps.invalidate(cardId)
            imageVersion++
            message = if (ok) "Your image is now shown on this card (only on this device)." else "Could not import that image."
        }
    }
    BackHandler(fullscreen) { fullscreen = false; hideUi = false }

    Column(Modifier.fillMaxSize().background(Palette.Night)) {
        Box(Modifier.fillMaxWidth().then(if (fullscreen) Modifier.fillMaxHeight() else Modifier.height(470.dp))) {
            if (owned != null && !glFailed) {
                CardViewer(card, layers, back, quality, settings.gyroscope, controller, Modifier.fillMaxSize(), onGlError = { glFailed = true })
            } else {
                // Fallback 2D card (not owned, or GL unavailable).
                Box(Modifier.fillMaxSize().padding(40.dp), contentAlignment = Alignment.Center) {
                    CardThumbnail(card, owned != null, Modifier.fillMaxHeight().graphicsLayer { rotationY = -8f; cameraDistance = 30f }, widthPx = 600, frame = frame, refreshKey = imageVersion)
                }
            }
            if (!hideUi) {
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { if (fullscreen) { fullscreen = false } else onBack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White) }
                    Spacer(Modifier.weight(1f))
                    if (owned != null) {
                        IconButton(onClick = { scope.launch { runCatching { container.repository.service.setFavorite(cardId, !owned.favorite) } } }) {
                            Icon(if (owned.favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, "Favourite", tint = Palette.Sakura)
                        }
                        IconButton(onClick = { fullscreen = !fullscreen }) { Icon(if (fullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen, "Fullscreen", tint = Color.White) }
                    }
                }
            }
            if (owned != null) Row(
                Modifier.align(Alignment.BottomCenter).padding(10.dp).navigationBarsPadding(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!hideUi) {
                    CozyButton(if (controller.flipped) "Front" else "Flip", { controller.flip() }, small = true, color = Palette.Lavender, textColor = Palette.Ink)
                    CozyButton("Reset", { controller.reset() }, small = true, color = Color.White, textColor = Palette.Ink)
                }
                if (fullscreen) IconButton(onClick = { hideUi = !hideUi }) { Icon(if (hideUi) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff, "Hide UI", tint = Color.White.copy(alpha = 0.7f)) }
            }
        }
        if (!fullscreen) Column(
            Modifier.fillMaxWidth().weight(1f).background(Palette.Cream).verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (owned != null) card.name else "Undiscovered card", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                RarityBadge(card.gameRarity)
            }
            if (owned != null) {
                Text("Drag to rotate · pinch to zoom · double-tap to reset" + if (settings.gyroscope) " · tilt your phone" else "", style = MaterialTheme.typography.labelSmall, color = Palette.InkSoft)
            }
            Spacer(Modifier.height(8.dp))
            val collection = catalog.collection(card.collectionId)
            InfoRow("Collection", collection?.name ?: card.collectionId)
            InfoRow("Series / album", card.series)
            InfoRow("Game rarity", card.gameRarity.label)
            InfoRow("Original rarity", card.originalRarity ?: "Unknown (not verified)")
            InfoRow("Manufacturer", card.manufacturer ?: "Unknown (not verified)")
            InfoRow("Year", card.year?.toString() ?: "Unknown (not verified)")
            InfoRow("Card number", card.cardNumber ?: "Unknown (not verified)")
            InfoRow("Data status", card.verification.label)
            InfoRow("Image", if (container.content.hasUserImage(cardId)) "Your imported image (stays on this device)" else card.imageRights.label)
            InfoRow("Source", card.source ?: "None – game-original content")
            Spacer(Modifier.height(6.dp))
            if (owned != null) Text(card.description, style = MaterialTheme.typography.bodyMedium)
            collection?.let { Text(it.disclaimer, style = MaterialTheme.typography.labelSmall, color = Palette.InkSoft, modifier = Modifier.padding(top = 6.dp)) }
            Spacer(Modifier.height(12.dp))
            if (owned != null) {
                Panel(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text("You own ${owned.count} cop${if (owned.count == 1) "y" else "ies"} · ${owned.duplicates} duplicate(s)", style = MaterialTheme.typography.titleSmall)
                        Text("First obtained ${DateFormat.getDateInstance().format(Date(owned.firstObtainedAt))}", style = MaterialTheme.typography.bodySmall, color = Palette.InkSoft)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (owned.duplicates > 0) CozyButton("Recycle duplicates", { recycleDialog = true }, small = true, color = Palette.Token)
                            CozyButton(if (container.content.hasUserImage(cardId)) "Change my image" else "Use my own image", {
                                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            }, small = true, color = Palette.Lavender, textColor = Palette.Ink)
                        }
                        if (container.content.hasUserImage(cardId)) {
                            Spacer(Modifier.height(6.dp))
                            CozyButton("Remove my image", {
                                container.content.removeCardImage(cardId); container.bitmaps.invalidate(cardId); imageVersion++
                            }, small = true, color = Color.White, textColor = Palette.Ink)
                        }
                        Text("Imported images are stored privately on your device and shown only to you. Only use images you have the right to use.", style = MaterialTheme.typography.labelSmall, color = Palette.InkSoft, modifier = Modifier.padding(top = 6.dp))
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("History", style = MaterialTheme.typography.titleSmall)
                history.take(20).forEach {
                    Text("• ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it.at))} — ${it.source}", style = MaterialTheme.typography.bodySmall)
                }
            } else {
                val price = Economy.exchangePrice(card.gameRarity)
                Panel(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text("Missing from your collection", style = MaterialTheme.typography.titleSmall)
                        Text("Find it in lootboxes, or exchange $price collector tokens for it right now.", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        CozyButton("Exchange for $price tokens", { exchangeDialog = true }, enabled = player.wallet.tokens >= price, color = Palette.Token, small = true)
                    }
                }
            }
        }
    }

    if (recycleDialog && owned != null) RecycleDialog(card.gameRarity, owned.duplicates, owned.favorite, onDismiss = { recycleDialog = false }) { n ->
        recycleDialog = false
        scope.launch {
            message = runCatching { "Recycled $n → +${container.repository.service.recycle(cardId, n, confirmRare = true)} tokens" }.getOrElse { it.message }
        }
    }
    if (exchangeDialog) AlertDialog(
        onDismissRequest = { exchangeDialog = false },
        title = { Text("Exchange tokens?") },
        text = { Text("Spend ${Economy.exchangePrice(card.gameRarity)} collector tokens to add ${card.name} to your collection.") },
        confirmButton = { TextButton({ exchangeDialog = false; scope.launch { message = runCatching { container.repository.service.exchangeForCard(cardId); "Added to your collection!" }.getOrElse { it.message } } }) { Text("Exchange") } },
        dismissButton = { TextButton({ exchangeDialog = false }) { Text("Cancel") } },
    )
    message?.let { AlertDialog(onDismissRequest = { message = null }, confirmButton = { TextButton({ message = null }) { Text("OK") } }, text = { Text(it) }) }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Palette.InkSoft, modifier = Modifier.width(130.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}

/** Recycling Epic/Legendary or favourite cards requires an explicit second confirmation. */
@Composable
private fun RecycleDialog(rarity: Rarity, max: Int, favorite: Boolean, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var amount by remember { mutableIntStateOf(max) }
    var armed by remember { mutableStateOf(false) }
    val needsCare = rarity.atLeast(Rarity.EPIC) || favorite
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Recycle duplicates") },
        text = {
            Column {
                Text("Recycle $amount of $max duplicate(s) for ${Economy.recycleValue(rarity) * amount} tokens. Your last copy is always kept.")
                if (max > 1) Slider(amount.toFloat(), { amount = it.toInt().coerceIn(1, max) }, valueRange = 1f..max.toFloat(), steps = (max - 2).coerceAtLeast(0))
                if (needsCare) Text(
                    if (armed) "Tap Recycle again to confirm." else "This is a ${if (favorite) "favourite" else rarity.label} card. You'll be asked to confirm twice.",
                    color = Palette.Danger, style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton({ if (needsCare && !armed) armed = true else onConfirm(amount) }) { Text(if (armed) "Recycle – I'm sure" else "Recycle") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
