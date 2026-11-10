package com.cozycards.app.ui.profile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.cozycards.app.container
import com.cozycards.app.data.AppSettings
import com.cozycards.app.data.EffectsQuality
import com.cozycards.app.ui.common.CozyButton
import com.cozycards.app.ui.common.Panel
import com.cozycards.app.ui.common.ProgressBar
import com.cozycards.app.ui.common.SectionTitle
import com.cozycards.app.ui.map.describe
import com.cozycards.app.ui.theme.Palette
import com.cozycards.game.DailyStatus
import com.cozycards.game.GameService
import com.cozycards.game.QuestStatus
import com.cozycards.game.economy.Cosmetic
import com.cozycards.game.economy.CosmeticSlot
import com.cozycards.game.progression.DailyRewards
import com.cozycards.game.progression.EventCalendar
import com.cozycards.game.progression.QuestPeriod
import com.cozycards.game.progression.Stat
import com.cozycards.game.progression.TimeMath
import com.cozycards.levels.Chapters
import kotlinx.coroutines.launch

@Composable
fun ProfileScreen() {
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Progress", "Quests", "Awards", "Settings")
    Column(Modifier.fillMaxSize().background(Palette.Cream).statusBarsPadding()) {
        Text("Profile", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(12.dp))
        TabRow(tab, containerColor = Palette.Cream) {
            tabs.forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t) }) }
        }
        when (tab) {
            0 -> ProgressTab()
            1 -> QuestsTab()
            2 -> AwardsTab()
            else -> SettingsTab()
        }
    }
}

@Composable
private fun ProgressTab() {
    val container = LocalContext.current.container
    val player by container.repository.state.collectAsState()
    val catalog = container.content.catalog
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Panel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("Journey", style = MaterialTheme.typography.titleLarge)
                    val cleared = player.progress.values.count { it.firstClearedAt != null }
                    Text("$cleared / ${Chapters.TOTAL_LEVELS} levels cleared", style = MaterialTheme.typography.bodyMedium)
                    ProgressBar(cleared / Chapters.TOTAL_LEVELS.toFloat(), Modifier.fillMaxWidth().padding(vertical = 6.dp))
                    Text("Stars: ${player.progress.values.sumOf { it.bestStars }} / 3000", style = MaterialTheme.typography.bodySmall)
                    Text("Chapters completed: ${player.stats[Stat.CHAPTERS_COMPLETED] ?: 0} / 20", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            Panel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("Collections", style = MaterialTheme.typography.titleLarge)
                    catalog.collections.forEach { c ->
                        val cards = catalog.inCollection(c.id)
                        val have = cards.count { player.cards.containsKey(it.id) }
                        val dupes = cards.sumOf { player.cards[it.id]?.duplicates ?: 0 }
                        Text("${c.name}: $have/${cards.size} · $dupes duplicate(s)", style = MaterialTheme.typography.bodySmall)
                        ProgressBar(have.toFloat() / cards.size, Modifier.fillMaxWidth().padding(vertical = 4.dp))
                    }
                }
            }
        }
        item {
            Panel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("Statistics", style = MaterialTheme.typography.titleLarge)
                    Stat.entries.forEach { s ->
                        Row(Modifier.fillMaxWidth()) {
                            Text(s.label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            Text("${player.stats[s] ?: 0}", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
        item {
            Panel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("Reward history", style = MaterialTheme.typography.titleLarge)
                    if (player.recentRewards.isEmpty()) Text("Nothing yet.", style = MaterialTheme.typography.bodySmall)
                    player.recentRewards.forEach { r ->
                        Text("• ${r.description}" + (if (r.coins > 0) " +${r.coins}c" else "") + (if (r.tokens > 0) " +${r.tokens}t" else ""), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun QuestsTab() {
    val container = LocalContext.current.container
    val service = container.repository.service
    val player by container.repository.state.collectAsState()
    val scope = rememberCoroutineScope()
    var quests by remember { mutableStateOf<List<QuestStatus>>(emptyList()) }
    var daily by remember { mutableStateOf<DailyStatus?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(player) {
        quests = runCatching { service.quests() }.getOrDefault(emptyList())
        daily = runCatching { service.dailyStatus() }.getOrNull()
    }
    val today = TimeMath.localDate(TimeMath.dayIndex(System.currentTimeMillis(), java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()).toLong()))
    val events = EventCalendar.active(today)
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Panel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("Daily reward", style = MaterialTheme.typography.titleLarge)
                    val d = daily
                    if (d != null) {
                        Text("Streak: ${d.streak} day(s). 7-day cycle; missing a day restarts it.", style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            DailyRewards.cycle.forEachIndexed { i, _ ->
                                val done = i < (d.streak % 7).let { if (it == 0 && d.streak > 0 && !d.canClaim) 7 else it }
                                Text("${i + 1}", Modifier.background(if (done) Palette.Matcha else Palette.Paper).padding(horizontal = 8.dp, vertical = 4.dp))
                            }
                        }
                        if (d.clockTampered) Text("Your device clock moved backwards. Daily rewards resume when it catches up.", color = Palette.Danger, style = MaterialTheme.typography.bodySmall)
                        CozyButton(if (d.canClaim) "Claim: ${describe(d.nextReward).removePrefix("Daily reward: ")}" else "Come back tomorrow", {
                            scope.launch { message = runCatching { describe(service.claimDaily()) }.getOrElse { it.message } }
                        }, enabled = d.canClaim, small = true)
                    }
                }
            }
        }
        if (events.isNotEmpty()) item {
            Panel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("Events now", style = MaterialTheme.typography.titleLarge)
                    events.forEach { Text("• ${it.title}: ${it.description}", style = MaterialTheme.typography.bodySmall) }
                    Text("Events follow your device's calendar and work offline.", style = MaterialTheme.typography.labelSmall, color = Palette.InkSoft)
                }
            }
        }
        for (period in QuestPeriod.entries) {
            val list = quests.filter { it.def.period == period }
            if (list.isEmpty()) continue
            item { SectionTitle(service.questPeriodLabel(period) + " quests") }
            items(list, key = { it.periodKey + it.def.id }) { q ->
                Panel(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(q.def.title, style = MaterialTheme.typography.titleSmall)
                            ProgressBar(q.progress.toFloat() / q.def.target, Modifier.fillMaxWidth().padding(vertical = 4.dp))
                            Text("${minOf(q.progress, q.def.target)}/${q.def.target} · reward: " + listOfNotNull(
                                q.def.coins.takeIf { it > 0 }?.let { "$it coins" },
                                q.def.tokens.takeIf { it > 0 }?.let { "$it tokens" },
                                q.def.box?.let { "${it.name.lowercase()} box" },
                            ).joinToString(), style = MaterialTheme.typography.labelSmall, color = Palette.InkSoft)
                        }
                        Spacer(Modifier.width(8.dp))
                        CozyButton(if (q.claimed) "Done" else "Claim", {
                            scope.launch { message = runCatching { service.claimQuest(q.def.id); "Quest reward claimed!" }.getOrElse { it.message } }
                        }, small = true, enabled = q.complete && !q.claimed)
                    }
                }
            }
        }
    }
    message?.let { AlertDialog(onDismissRequest = { message = null }, confirmButton = { TextButton({ message = null }) { Text("OK") } }, text = { Text(it) }) }
}

@Composable
private fun AwardsTab() {
    val container = LocalContext.current.container
    val service = container.repository.service
    val player by container.repository.state.collectAsState()
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<GameService.AchievementStatus>>(emptyList()) }
    LaunchedEffect(player) { list = runCatching { service.achievements() }.getOrDefault(emptyList()) }
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("${list.count { it.claimed }} / ${list.size} achievements", style = MaterialTheme.typography.titleMedium) }
        items(list, key = { it.def.id }) { a ->
            Panel(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(a.def.title, style = MaterialTheme.typography.titleSmall)
                        Text(a.def.description, style = MaterialTheme.typography.bodySmall, color = Palette.InkSoft)
                        ProgressBar(a.progress.toFloat() / a.def.threshold, Modifier.fillMaxWidth().padding(top = 4.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    CozyButton(
                        when { a.claimed -> "✓"; a.unlocked -> "Claim"; else -> "${minOf(a.progress, a.def.threshold)}/${a.def.threshold}" },
                        { scope.launch { runCatching { service.claimAchievement(a.def.id) } } },
                        small = true, enabled = a.unlocked && !a.claimed,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsTab() {
    val container = LocalContext.current.container
    val settings by container.settings.settings.collectAsState(initial = AppSettings())
    val player by container.repository.state.collectAsState()
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var confirmImport by remember { mutableStateOf<android.net.Uri?>(null) }
    fun update(f: (AppSettings) -> AppSettings) = scope.launch { container.settings.update(f) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch { message = runCatching { container.repository.exportTo(uri); "Progress exported." }.getOrElse { "Export failed: ${it.message}" } }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) confirmImport = uri }
    val packImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val issues = container.content.importPack(uri)
            message = if (issues.isEmpty()) "Card pack saved. Restart the app to load it." else "Pack rejected:\n" + issues.take(5).joinToString("\n")
        }
    }

    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Panel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("Sound", style = MaterialTheme.typography.titleLarge)
                    Row(verticalAlignment = Alignment.CenterVertically) { Text("Mute everything", Modifier.weight(1f)); Switch(settings.muted, { m -> update { it.copy(muted = m) } }) }
                    Text("Music volume", style = MaterialTheme.typography.labelMedium)
                    Slider(settings.musicVolume, { v -> update { it.copy(musicVolume = v) } })
                    Text("Effects volume", style = MaterialTheme.typography.labelMedium)
                    Slider(settings.sfxVolume, { v -> update { it.copy(sfxVolume = v) } })
                }
            }
        }
        item {
            Panel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("Graphics & feel", style = MaterialTheme.typography.titleLarge)
                    Text("Card effects quality (Auto picked ${container.autoQuality.label} for this device)", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        EffectsQuality.entries.forEach { q -> FilterChip(settings.quality == q, { update { it.copy(quality = q) } }, { Text(q.label) }) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) { Text("Tilt cards with gyroscope", Modifier.weight(1f)); Switch(settings.gyroscope, { g -> update { it.copy(gyroscope = g) } }) }
                    Row(verticalAlignment = Alignment.CenterVertically) { Text("Haptic feedback", Modifier.weight(1f)); Switch(settings.haptics, { h -> update { it.copy(haptics = h) } }) }
                    Row(verticalAlignment = Alignment.CenterVertically) { Text("Reduced motion", Modifier.weight(1f)); Switch(settings.reducedMotion, { r -> update { it.copy(reducedMotion = r) } }) }
                }
            }
        }
        item {
            Panel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("Cosmetics", style = MaterialTheme.typography.titleLarge)
                    CosmeticSlot.entries.forEach { slot ->
                        Text(slot.name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                            Cosmetic.entries.filter { it.slot == slot && it in player.ownedCosmetics }.forEach { c ->
                                FilterChip(player.equippedOrDefault(slot) == c, { scope.launch { runCatching { container.repository.service.equip(c) } } }, { Text(c.title) })
                            }
                        }
                    }
                    Text("More styles are available in the Shop.", style = MaterialTheme.typography.labelSmall, color = Palette.InkSoft)
                }
            }
        }
        item {
            Panel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("Save data", style = MaterialTheme.typography.titleLarge)
                    Text("Progress is saved automatically on this device after every reward, with rolling backups. Export a copy to move it or keep it safe.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CozyButton("Export", { exporter.launch("cozy-card-journey-save.json") }, small = true)
                        CozyButton("Import", { importer.launch(arrayOf("application/json", "text/plain", "*/*")) }, small = true, color = Palette.Lavender, textColor = Palette.Ink)
                    }
                }
            }
        }
        item {
            Panel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("Card packs", style = MaterialTheme.typography.titleLarge)
                    Text("Add a collection from a card-pack JSON file. Packs are validated: real-card data must cite a source, and images without cleared rights are refused.", style = MaterialTheme.typography.bodySmall)
                    container.content.packWarnings.forEach { Text("⚠ $it", style = MaterialTheme.typography.labelSmall, color = Palette.Danger) }
                    Spacer(Modifier.height(6.dp))
                    CozyButton("Import card pack", { packImporter.launch(arrayOf("application/json", "*/*")) }, small = true, color = Palette.Token)
                }
            }
        }
        item {
            Panel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("About", style = MaterialTheme.typography.titleLarge)
                    Text("Cozy Card Journey works fully offline. No lives, no energy timers, no ads, no real-money purchases.", style = MaterialTheme.typography.bodySmall)
                    Text("Card art is original and procedurally generated. Collections are fan tributes and are not affiliated with or endorsed by the rights holders; they are not real trading cards.", style = MaterialTheme.typography.bodySmall)
                    Text("Music and sound effects are original, synthesised for this game.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    confirmImport?.let { uri ->
        AlertDialog(
            onDismissRequest = { confirmImport = null },
            title = { Text("Replace progress?") },
            text = { Text("Importing replaces your current progress with the file's contents. Your current state is backed up first.") },
            confirmButton = {
                TextButton({
                    confirmImport = null
                    scope.launch { message = runCatching { container.repository.importFrom(uri); "Progress imported." }.getOrElse { "Import failed: ${it.message}" } }
                }) { Text("Import") }
            },
            dismissButton = { TextButton({ confirmImport = null }) { Text("Cancel") } },
        )
    }
    message?.let { AlertDialog(onDismissRequest = { message = null }, confirmButton = { TextButton({ message = null }) { Text("OK") } }, text = { Text(it) }) }
}
