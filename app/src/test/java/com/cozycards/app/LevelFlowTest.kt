package com.cozycards.app

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.cozycards.app.ui.level.LevelViewModel
import com.cozycards.engine.GameStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** Plays real levels through the ViewModel exactly as the UI does (minus drawing). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LevelFlowTest {
    private lateinit var container: AppContainer

    @Before
    fun setUp() {
        container = ApplicationProvider.getApplicationContext<CozyApp>().container
        val deadline = System.currentTimeMillis() + 60_000
        while (container.ready.value !is StartupState.Ready && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue(container.ready.value is StartupState.Ready)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun playingALevelToTheEndGrantsRewardsOnce() {
        val def = container.repository.service.level(1)!!
        runBlocking { container.repository.service.startLevel(def.id, emptySet()) }
        val vm = LevelViewModel(container, def, emptySet())
        var guard = 0
        while (!vm.state.value.finished && guard++ < 5_000) {
            val s = vm.state.value
            val anim = s.anim
            if (anim != null) {
                vm.stepDone(anim.id) // what BoardView calls when an animation ends
            } else {
                val move = vm.validMovesForTest().maxByOrNull { (a, b) -> a.y + b.y } ?: break
                vm.onSwap(move.first, move.second)
            }
            idle()
        }
        assertTrue("level should end", vm.state.value.finished)
        // Reward transaction runs asynchronously; wait for it.
        val deadline = System.currentTimeMillis() + 10_000
        while (vm.state.value.reward == null && System.currentTimeMillis() < deadline) { idle(); Thread.sleep(10) }
        val reward = vm.state.value.reward
        assertNotNull(reward)
        runBlocking { container.repository.refresh() }
        val player = container.repository.state.value
        if (vm.state.value.status == GameStatus.WON) {
            assertTrue(reward!!.firstClear)
            assertEquals(1, reward.lootboxes.size)
            assertTrue(player.progress[def.id]?.firstClearedAt != null)
            assertTrue(player.isUnlocked(2))
        } else {
            assertEquals(0, reward!!.lootboxes.size)
        }
    }

    @Test
    fun animationWatchdogNeverBlocksTheGame() {
        val def = container.repository.service.level(1)!!
        val vm = LevelViewModel(container, def, emptySet())
        val move = vm.validMovesForTest().first()
        vm.onSwap(move.first, move.second)
        assertNotNull(vm.state.value.anim)
        // Nobody reports animation completion (e.g. the screen went to background).
        var guard = 0
        while (vm.state.value.anim != null && guard++ < 200) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2600))
        }
        assertEquals(null, vm.state.value.anim)
        assertTrue(vm.state.value.status != GameStatus.PLAYING || vm.validMovesForTest().isNotEmpty())
    }

    @Test
    fun preLevelBoostersArePlacedOnTheBoard() {
        val def = container.repository.service.level(1)!!
        val vm = LevelViewModel(container, def, setOf(com.cozycards.game.economy.BoosterType.BOMB, com.cozycards.game.economy.BoosterType.EXTRA_MOVES))
        val board = vm.state.value.board
        assertEquals(1, board.positions.count { board[it].piece?.special == com.cozycards.engine.Special.BOMB })
        assertEquals(def.moves + 5, vm.state.value.movesLeft)
    }
}
