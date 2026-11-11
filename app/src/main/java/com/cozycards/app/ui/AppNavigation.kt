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
import androidx.compose.ui.graphics.asImageBitmap
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
import com.cozycards.app.ui.gfx.drawImageCentered
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

private enum class Tab(val route: String, val label: String, val sprite: String) {
    MAP("map", "Map", "nav_map"),
    COLLECTION("collection", "Collection", "nav_collection"),
    LOOT("lootboxes", "Lootboxes", "nav_loot"),
    SHOP("shop", "Shop", "nav_shop"),
    PROFILE("profile", "Profile", "nav_profile"),
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
        val context = LocalContext.current
        val logo = remember {
            runCatching { context.assets.open("sprites/logo.webp").use { android.graphics.BitmapFactory.decodeStream(it).asImageBitmap() } }.getOrNull()
        }
        Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (logo != null) androidx.compose.foundation.Image(logo, null, Modifier.size(220.dp))
            Spacer(Modifier.height(8.dp))
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

    Box(Modifier.fillMaxSize().background(Palette.Cream)) {
        val navBar: @Composable () -> Unit = {
            if (showBar) CozyNavBar(route, player.lootboxes.size) { nav.navigateTab(it) }
        }
        NavHost(
            nav, startDestination = Tab.MAP.route,
            modifier = Modifier.fillMaxSize(),
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
        androidx.compose.animation.AnimatedVisibility(
            com.cozycards.app.ui.common.NavBarState.visible, Modifier.align(Alignment.BottomCenter),
            enter = androidx.compose.animation.slideInVertically { it }, exit = androidx.compose.animation.slideOutVertically { it },
        ) { navBar() }
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
    Row(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 4.dp, top = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.Bottom,
    ) {
        Tab.entries.forEach { tab ->
            val selected = route == tab.route
            val lift by androidx.compose.animation.core.animateDpAsState(if (selected) (-8).dp else 0.dp, label = "lift")
            val size by androidx.compose.animation.core.animateDpAsState(if (selected) 62.dp else 52.dp, label = "size")
            Column(
                Modifier.weight(1f).clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { onTab(tab.route) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.offset(y = lift), contentAlignment = Alignment.Center) {
                    com.cozycards.app.ui.common.Medallion(
                        size, rim = if (selected) 3.dp else 2.5.dp,
                        fill = if (selected) androidx.compose.ui.graphics.Brush.radialGradient(listOf(Color(0xFFFFD0E2), Color(0xFFF07AA6)))
                        else androidx.compose.ui.graphics.Brush.radialGradient(listOf(Color(0xFFFFFCF6), Color(0xFFF2E4D2))),
                    ) {
                        androidx.compose.foundation.Canvas(Modifier.size(size * 0.72f)) {
                            drawImageCentered(com.cozycards.app.ui.gfx.Sprites[tab.sprite], center, this.size.minDimension, 1f, null, false)
                        }
                    }
                    if (tab == Tab.LOOT && boxes > 0) com.cozycards.app.ui.common.GoldBadge(boxes, Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-2).dp))
                }
                Text(
                    tab.label, maxLines = 1,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold,
                        shadow = androidx.compose.ui.graphics.Shadow(Color.White, androidx.compose.ui.geometry.Offset.Zero, 6f),
                    ),
                    color = if (selected) Palette.SakuraDeep else Palette.Ink,
                    modifier = Modifier.offset(y = lift / 2),
                )
            }
        }
    }
}
