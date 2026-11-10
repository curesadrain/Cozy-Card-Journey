package com.cozycards.app

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
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
            Thread.sleep(5)
            left -= 50
        }
    }

    /** Software-draws the whole window (Robolectric native graphics) into a PNG. */
    private fun shot(name: String) {
        advance(100)
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
        clickText("Play")
        advance(1500)
        shot("03_level")
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        advance(600)
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
        clickText("Profile")
        shot("10_profile")
        clickText("Quests")
        shot("11_quests")
        clickText("Settings")
        shot("12_settings")
    }

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
