package com.cozycards.app.ui

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CardGiftcard
import androidx.compose.material.icons.rounded.Collections
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.cozycards.app.StartupState
import com.cozycards.app.audio.Sfx
import com.cozycards.app.container
import com.cozycards.app.ui.collection.CardDetailScreen
import com.cozycards.app.ui.collection.CollectionScreen
import com.cozycards.app.ui.common.ChapterLooks
import com.cozycards.app.ui.common.SceneBackground
import com.cozycards.app.ui.level.LevelScreen
import com.cozycards.app.ui.loot.LootboxScreen
import com.cozycards.app.ui.map.MapScreen
import com.cozycards.app.ui.profile.ProfileScreen
import com.cozycards.app.ui.shop.ShopScreen
import com.cozycards.app.ui.theme.Palette
import com.cozycards.game.economy.BoosterType

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    MAP("map", "Map", Icons.Rounded.Map),
    COLLECTION("collection", "Collection", Icons.Rounded.Collections),
    LOOT("lootboxes", "Lootboxes", Icons.Rounded.CardGiftcard),
    SHOP("shop", "Shop", Icons.Rounded.Storefront),
    PROFILE("profile", "Profile", Icons.Rounded.Person),
}

@Composable
fun CozyApp() {
    val container = LocalContext.current.container
    val startup by container.ready.collectAsState()
    when (val s = startup) {
        StartupState.Loading -> Splash(null)
        is StartupState.Failed -> Splash("Something went wrong while loading your progress:\n${s.message}\nYour backups are kept; please restart the app.")
        is StartupState.Ready -> {
            var notice by remember { mutableStateOf(if (s.restoredFromBackup) "Your progress was restored from the latest backup." else null) }
            MainScaffold()
            notice?.let { n ->
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { notice = null },
                    confirmButton = { androidx.compose.material3.TextButton({ notice = null }) { Text("OK") } },
                    text = { Text(n) },
                )
            }
        }
    }
}

@Composable
private fun Splash(error: String?) {
    Box(Modifier.fillMaxSize()) {
        SceneBackground(ChapterLooks.of(1))
        Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Cozy Card Journey", style = MaterialTheme.typography.displaySmall, color = Palette.Ink, textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            if (error == null) CircularProgressIndicator(color = Palette.SakuraDeep)
            else Text(error, color = Palette.Danger, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun MainScaffold() {
    val nav = rememberNavController()
    val container = LocalContext.current.container
    val player by container.repository.state.collectAsState()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val showBar = Tab.entries.any { it.route == route }
    LaunchedEffect(route) { if (route != null) container.audio.play(Sfx.SWISH) }

    Scaffold(
        containerColor = Palette.Cream,
        bottomBar = {
            if (showBar) CozyNavBar(route, player.lootboxes.size) { nav.navigateTab(it) }
        },
    ) { padding ->
        NavHost(
            nav, startDestination = Tab.MAP.route,
            modifier = Modifier.padding(bottom = if (showBar) padding.calculateBottomPadding() else 0.dp).background(Palette.Cream),
            enterTransition = { fadeIn() }, exitTransition = { fadeOut() },
        ) {
            composable(Tab.MAP.route) {
                MapScreen(onPlay = { id, boosters -> nav.navigate(levelRoute(id, boosters)) }, onOpenProfile = { nav.navigateTab(Tab.PROFILE.route) })
            }
            composable(Tab.COLLECTION.route) { CollectionScreen(onCard = { nav.navigate("card/$it") }) }
            composable(Tab.LOOT.route) { LootboxScreen(onCard = { nav.navigate("card/$it") }) }
            composable(Tab.SHOP.route) { ShopScreen() }
            composable(Tab.PROFILE.route) { ProfileScreen() }
            composable("card/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                CardDetailScreen(entry.arguments?.getString("id")!!, onBack = { nav.popBackStack() })
            }
            composable(
                "level/{id}?boosters={boosters}",
                arguments = listOf(navArgument("id") { type = NavType.StringType }, navArgument("boosters") { type = NavType.StringType; defaultValue = "" }),
            ) { entry ->
                val id = entry.arguments?.getString("id")!!
                val boosters = entry.arguments?.getString("boosters").orEmpty().split(',').filter { it.isNotBlank() }
                    .mapNotNull { runCatching { BoosterType.valueOf(it) }.getOrNull() }.toSet()
                LevelScreen(
                    levelId = id,
                    boosters = boosters,
                    onExit = { nav.popBackStack(Tab.MAP.route, false) },
                    onNextLevel = { nextId ->
                        nav.navigate(levelRoute(nextId, emptySet())) { popUpTo(Tab.MAP.route) }
                    },
                    onOpenLootboxes = { nav.popBackStack(Tab.MAP.route, false); nav.navigateTab(Tab.LOOT.route) },
                )
            }
        }
    }
}

private fun levelRoute(id: String, boosters: Set<BoosterType>) = "level/$id?boosters=" + boosters.joinToString(",") { it.name }

private fun NavHostController.navigateTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun CozyNavBar(route: String?, boxes: Int, onTab: (String) -> Unit) {
    Box(
        Modifier.fillMaxWidth()
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFFFFFAF2), Color(0xFFF6E8D6))))
            .drawBehind { drawRect(Palette.GoldRim, size = androidx.compose.ui.geometry.Size(size.width, 3.dp.toPx())) }
            .navigationBarsPadding()
            .padding(top = 6.dp, bottom = 4.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Tab.entries.forEach { tab ->
                val selected = route == tab.route
                val lift by androidx.compose.animation.core.animateFloatAsState(if (selected) 1.15f else 1f, label = "tab")
                Column(
                    Modifier.weight(1f).clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { onTab(tab.route) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        com.cozycards.app.ui.common.Medallion(
                            if (selected) 46.dp else 40.dp, Modifier.scale(lift), rim = if (selected) 3.dp else 2.dp,
                            fill = if (selected) androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFFFF8FB8), Color(0xFFD9467C)))
                            else androidx.compose.ui.graphics.Brush.radialGradient(listOf(Color(0xFFFFFCF6), Color(0xFFF2E4D2))),
                        ) { Icon(tab.icon, tab.label, tint = if (selected) Color.White else Palette.InkSoft, modifier = Modifier.size(22.dp)) }
                        if (tab == Tab.LOOT && boxes > 0) com.cozycards.app.ui.common.GoldBadge(boxes, Modifier.align(Alignment.TopEnd).offset(x = 10.dp, y = (-4).dp))
                    }
                    Text(tab.label, style = MaterialTheme.typography.labelSmall, color = if (selected) Palette.SakuraDeep else Palette.InkSoft, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                }
            }
        }
    }
}
