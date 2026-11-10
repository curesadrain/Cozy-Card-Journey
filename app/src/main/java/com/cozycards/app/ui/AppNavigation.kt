package com.cozycards.app.ui

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
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
            if (showBar) NavigationBar(containerColor = Palette.Paper) {
                Tab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = route == tab.route,
                        onClick = { nav.navigateTab(tab.route) },
                        icon = {
                            val n = if (tab == Tab.LOOT) player.lootboxes.size else 0
                            BadgedBox(badge = { if (n > 0) Badge { Text(if (n > 99) "99+" else "$n") } }) { Icon(tab.icon, tab.label) }
                        },
                        label = { Text(tab.label, style = MaterialTheme.typography.labelSmall) },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = Palette.SakuraDeep, selectedTextColor = Palette.SakuraDeep, indicatorColor = Palette.Sakura.copy(alpha = 0.3f)),
                    )
                }
            }
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
