package com.cozycards.app

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.core.app.ApplicationProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import com.cozycards.app.cards.CardArt
import com.cozycards.app.ui.CozyApp
import com.cozycards.app.ui.theme.CozyTheme
import com.cozycards.game.LevelResult
import com.cozycards.game.economy.Cosmetic
import com.cozycards.levels.Chapters
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import androidx.compose.ui.test.performScrollToKey

/**
 * Renders real screens with Robolectric's native graphics and writes PNGs to
 * app/build/screenshots for visual review. Also acts as a smoke test of navigation.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xhdpi")
class ScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val dir = File(System.getProperty("screenshots.dir") ?: "build/screenshots").apply { mkdirs() }

    private fun advance(ms: Long) {
        var left = ms
        while (left > 0) {
            compose.mainClock.advanceTimeBy(50)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(5)
            left -= 50
        }
    }

    /** Software-draws the whole window (Robolectric native graphics) into a PNG. */
    private fun shot(name: String, settle: Boolean = true) {
        if (settle) advance(100) else compose.waitForIdle()
        compose.activityRule.scenario.onActivity { act ->
            val v = act.window.decorView
            val bmp = Bitmap.createBitmap(v.width.coerceAtLeast(1), v.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            v.draw(android.graphics.Canvas(bmp))
            File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun clickText(text: String) {
        compose.onAllNodesWithText(text)[0].performClick()
        advance(900)
    }

    @Test
    fun mapScenery() {
        val app = ApplicationProvider.getApplicationContext<CozyApp>()
        val container = app.container
        compose.mainClock.autoAdvance = false
        val deadline = System.currentTimeMillis() + 60_000
        while (container.ready.value !is StartupState.Ready && System.currentTimeMillis() < deadline) Thread.sleep(50)
        compose.setContent { CozyTheme { CozyApp() } }
        advance(1500)
        shot("map_ch1")
        val list = compose.onNode(androidx.compose.ui.test.hasScrollToKeyAction())
        for ((key, name) in listOf("n30" to "map_ch1_mid", "g5" to "map_gate5", "n440" to "map_ch9", "g10" to "map_gate10", "n480" to "map_ch10", "n640" to "map_ch13")) {
            list.performScrollToKey(key)
            advance(600)
            shot(name)
        }
    }

    @Test
    fun captureMainScreens() {
        val app = ApplicationProvider.getApplicationContext<CozyApp>()
        val container = app.container
        compose.mainClock.autoAdvance = false
        // Wait for startup (assets + database) on real background threads.
        val deadline = System.currentTimeMillis() + 60_000
        while (container.ready.value !is StartupState.Ready && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue("startup: ${container.ready.value}", container.ready.value is StartupState.Ready)
        // Give the player some progress so screens have content.
        runBlocking {
            val s = container.repository.service
            for (n in 1..12) s.completeLevel(LevelResult(Chapters.levelId(n), true, 1 + n % 3, 1000, 3, 100, 3, 1, 4))
            s.openLootbox(container.repository.state.value.lootboxes.firstOrNull()?.id ?: return@runBlocking)
            s.openAll(5)
            container.repository.refresh()
        }
        compose.setContent { CozyTheme { CozyApp() } }
        advance(1500)
        shot("01_map")
        clickText("13")
        shot("02_prelevel")
        compose.onAllNodesWithText("Play").let { it[it.fetchSemanticsNodes().size - 1] }.performClick()
        advance(3000)
        shot("03_level")
        // Play a real move on the level screen and capture the animation.
        // Swipe the first valid move found by trying neighbours through the UI is fragile; use a drag on the board centre instead.
        compose.onRoot().performTouchInput {
            val cx = centerX; val cy = centerY - height * 0.02f
            swipe(androidx.compose.ui.geometry.Offset(cx, cy), androidx.compose.ui.geometry.Offset(cx + width * 0.12f, cy), 120)
        }
        for (i in 0 until 10) { shot("03_level_move_%02d".format(i), settle = false); compose.mainClock.advanceTimeBy(80); org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle() }
        advance(2500)
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        advance(1200)
        shot("04_pause")
        clickText("Back to map")
        clickText("Collection")
        shot("05_collection")
        clickText("Lootboxes")
        shot("06_lootboxes")
        compose.onAllNodesWithText("Open")[0].performClick()
        advance(2500)
        shot("07_opening")
        clickText("Reveal all")
        advance(800)
        shot("08_revealed")
        clickText("Done")
        clickText("Shop")
        shot("09_shop")
        clickText("Boosters")
        shot("09_shop_boosters")
        clickText("Styles")
        shot("09_shop_styles")
        clickText("Profile")
        shot("10_profile")
        clickText("Quests")
        shot("11_quests")
        clickText("Settings")
        shot("12_settings")
    }

    /** Plays engine steps through the real BoardView + effects layer and saves frames. */
    @Test
    fun boardAnimationFrames() {
        val app = ApplicationProvider.getApplicationContext<CozyApp>()
        val deadline = System.currentTimeMillis() + 60_000
        while (app.container.ready.value !is StartupState.Ready && System.currentTimeMillis() < deadline) Thread.sleep(20)
        val w = 8; val h = 8
        val cells = List(w * h) { i ->
            val x = i % w; val y = i / w
            when {
                y == 6 && x in 2..5 -> com.cozycards.engine.CellSpec(blocker = com.cozycards.engine.Blocker(com.cozycards.engine.BlockerType.BOX, 1 + x % 3))
                y == 1 && x in 1..6 -> com.cozycards.engine.CellSpec(ice = 1 + x % 2)
                y == 4 && (x == 0 || x == 7) -> com.cozycards.engine.CellSpec(chain = 1)
                else -> com.cozycards.engine.CellSpec()
            }
        }
        val spec = com.cozycards.engine.GameSpec(5, 20, listOf(com.cozycards.engine.StageSpec(w, h, cells,
            listOf(com.cozycards.engine.Goal(com.cozycards.engine.GoalType.COLLECT_COLOR, 30, 0), com.cozycards.engine.Goal(com.cozycards.engine.GoalType.BREAK_BOXES, 4), com.cozycards.engine.Goal(com.cozycards.engine.GoalType.CLEAR_ICE, 6)))))
        val game = com.cozycards.engine.Match3Game(spec, 11)
        val placed = game.placeSpecials(listOf(com.cozycards.engine.Special.BOMB, com.cozycards.engine.Special.ROCKET_H))
        val start = game.board.copy()
        val result = game.useHammer(placed.first()) as com.cozycards.engine.MoveResult.Applied
        val steps = result.steps
        var index by androidx.compose.runtime.mutableStateOf(0)
        var shown by androidx.compose.runtime.mutableStateOf(start)
        val fx = com.cozycards.app.ui.gfx.Fx()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CozyTheme {
                val text = androidx.compose.ui.text.rememberTextMeasurer()
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    var last = 0L
                    while (true) androidx.compose.runtime.withFrameNanos { n -> val dt = if (last == 0L) 0.016f else (n - last) / 1e9f; last = n; fx.tick(dt) }
                }
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().androidxBg()) {
                    val anim = steps.getOrNull(index)?.let { com.cozycards.app.ui.level.StepAnim(index + 1, shown, it) }
                    com.cozycards.app.ui.level.BoardView(
                        state = com.cozycards.app.ui.level.LevelUiState(board = shown, anim = anim, movesLeft = 10, score = 0, goals = game.goals, stageIndex = 0, stageCount = 1, gravity = com.cozycards.engine.Dir.DOWN),
                        portals = emptyList(), conveyors = emptyList(), reducedMotion = false, haptics = false, fx = fx,
                        goalTargets = mapOf(com.cozycards.app.ui.level.GoalKey(com.cozycards.engine.GoalType.COLLECT_COLOR, 0) to androidx.compose.ui.geometry.Offset(100f, 40f),
                            com.cozycards.app.ui.level.GoalKey(com.cozycards.engine.GoalType.BREAK_BOXES, -1) to androidx.compose.ui.geometry.Offset(400f, 40f),
                            com.cozycards.app.ui.level.GoalKey(com.cozycards.engine.GoalType.CLEAR_ICE, -1) to androidx.compose.ui.geometry.Offset(700f, 40f)),
                        onGoalLaunch = {}, onGoalHit = {}, onSwap = { _, _ -> }, onTap = {},
                        onStepDone = { id -> if (id == index + 1) { shown = steps[index].board; index++ } },
                        modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                    )
                    androidx.compose.foundation.Canvas(androidx.compose.ui.Modifier.fillMaxSize()) { fx.frame.longValue; fx.draw(this, text) }
                }
            }
        }
        for (i in 0 until 24) {
            shot("anim_%02d".format(i), settle = false)
            compose.mainClock.advanceTimeBy(64)
        }
    }

    private fun androidx.compose.ui.Modifier.androidxBg() = this.then(androidx.compose.ui.Modifier.background(androidx.compose.ui.graphics.Color(0xFF6B5A8E)))

    @Test
    fun renderCardArt() {
        val app = ApplicationProvider.getApplicationContext<CozyApp>()
        val catalog = app.container.content.catalog
        val picks = listOf("bts_001", "bts_027", "spiderman_028", "tmnt_004", "tmnt_027", "spiderman_017")
        for (id in picks) {
            val card = catalog[id] ?: continue
            val front = CardArt.renderFront(card, catalog.collection(card.collectionId), 500, 700, false, null)
            File(dir, "card_$id.png").outputStream().use { front.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        for (b in listOf(Cosmetic.BACK_CLASSIC, Cosmetic.BACK_LANTERN, Cosmetic.BACK_SAKURA)) {
            val back = CardArt.renderBack(b, 500, 700)
            File(dir, "back_${b.name}.png").outputStream().use { back.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        // GL texture layers per rarity (checked in the WebGL shader harness).
        for (id in listOf("spiderman_028", "bts_027", "tmnt_004", "bts_001")) {
            val card = catalog[id]!!
            CardArt.renderFrame(card, null, 720, 1008, false, false).let { f -> File(dir, "layer_frame_$id.png").outputStream().use { f.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            CardArt.renderArt(card, 720, 1008, null).let { f -> File(dir, "layer_art_$id.png").outputStream().use { f.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        }
    }
}
