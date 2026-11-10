package com.cozycards.levels

import com.cozycards.engine.BlockerType
import com.cozycards.engine.Dir
import com.cozycards.engine.GoalType
import com.cozycards.engine.PieceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LevelValidatorTest {
    private val validator = LevelValidator()

    private fun level(
        rows: List<String> = List(7) { List(7) { "." }.joinToString(" ") },
        goals: List<GoalDef> = listOf(GoalDef(GoalType.COLLECT_COLOR, 20, 0)),
        moves: Int = 20,
        colors: Int = 5,
        stage: (StageDef) -> StageDef = { it },
    ) = LevelDefinition(
        id = "c01_l01", number = 1, chapter = 1, indexInChapter = 1, colors = colors, moves = moves,
        difficulty = 0.1, tier = DifficultyTier.EASY, stages = listOf(stage(StageDef(rows, goals))),
        reward = LevelReward(50, LootboxTier.COMMON), template = "test",
    )

    private fun messages(def: LevelDefinition) = validator.validateStructure(def).map { it.message }

    @Test
    fun validLevelPasses() {
        assertEquals(emptyList<String>(), messages(level()))
        assertTrue(validator.validateStart(level()).isEmpty())
    }

    @Test
    fun detectsBadTokensAndRaggedRows() {
        assertTrue(messages(level(rows = List(7) { ". . . . . . Q" })).any { "layout error" in it })
        assertTrue(messages(level(rows = listOf(". . .", ". .") + List(5) { ". . ." })).any { "layout error" in it })
    }

    @Test
    fun detectsSizeAndMoveLimits() {
        assertTrue(messages(level(rows = List(4) { ". . . ." })).any { "height" in it })
        assertTrue(messages(level(moves = 0)).any { "moves" in it })
        assertTrue(messages(level(colors = 8)).any { "colors" in it })
    }

    @Test
    fun detectsUnreachableGoals() {
        assertTrue(messages(level(goals = listOf(GoalDef(GoalType.CLEAR_ICE, 3)))).any { "exceeds" in it })
        assertTrue(messages(level(goals = listOf(GoalDef(GoalType.COLLECT_COLOR, 10, 7)))).any { "colour" in it })
        assertTrue(messages(level(goals = emptyList())).any { "no goals" in it })
        val lockRows = List(6) { ". . . . . . ." } + listOf(". . L L . . .")
        assertTrue(messages(level(rows = lockRows, goals = listOf(GoalDef(GoalType.OPEN_LOCKS, 2)))).any { "keys" in it })
        assertFalse(messages(level(rows = lockRows, goals = listOf(GoalDef(GoalType.OPEN_LOCKS, 2))) { it.copy(keySpawns = 2) }).any { "keys" in it })
        assertTrue(messages(level(goals = listOf(GoalDef(GoalType.DELIVER_DROPS, 1)))).any { "exceeds" in it })
    }

    @Test
    fun detectsBadPortals() {
        val bad = level { it.copy(portals = listOf(PortalDef(PosDef(0, 3), PosDef(6, 0)))) }
        assertTrue(messages(bad).any { "entrance" in it })
        val cycle = level {
            it.copy(portals = listOf(PortalDef(PosDef(0, 6), PosDef(6, 0)), PortalDef(PosDef(6, 6), PosDef(0, 0))))
        }
        assertTrue(messages(cycle).any { "loop" in it || "no spawn" in it })
        val ok = level { it.copy(portals = listOf(PortalDef(PosDef(0, 6), PosDef(6, 0)))) }
        assertEquals(emptyList<String>(), messages(ok))
    }

    @Test
    fun detectsBadConveyors() {
        val broken = level { it.copy(conveyors = listOf(listOf(PosDef(0, 2), PosDef(1, 2), PosDef(3, 2), PosDef(3, 3)))) }
        assertTrue(messages(broken).any { "closed loop" in it })
        val good = level {
            it.copy(conveyors = listOf(listOf(PosDef(0, 2), PosDef(1, 2), PosDef(2, 2), PosDef(2, 3), PosDef(1, 3), PosDef(0, 3))))
        }
        assertEquals(emptyList<String>(), messages(good))
    }

    @Test
    fun detectsBlockedSpawnsAndTinyBoards() {
        val blocked = level(rows = listOf("X1 X1 X1 X1 X1 . .") + List(6) { ". . . . . . ." })
        assertTrue(messages(blocked).any { "spawn" in it })
        val tiny = level(rows = listOf("# # # # # # #", "# . . . . . #") + List(5) { "# # # . # # #" })
        assertTrue(messages(tiny).any { "playable" in it })
    }

    @Test
    fun detectsBadGravityCycle() {
        val bad = level { it.copy(gravitySwitchEvery = 3, gravityCycle = listOf(Dir.DOWN)) }
        assertTrue(messages(bad).any { "gravity cycle" in it })
    }

    @Test
    fun codecRoundTrip() {
        val rows = listOf("i. Ic. wK X3 S2 L D", "# * R V B P C.")
        val decoded = LayoutCodec.decode(rows)
        assertEquals(rows, LayoutCodec.encode(decoded.width, decoded.cells))
        assertEquals(BlockerType.BOX, decoded.cells[3].blocker!!.type)
        assertEquals(3, decoded.cells[3].blocker!!.hp)
        assertEquals(PieceKind.KEY, decoded.cells[2].initial!!.kind)
        assertTrue(decoded.cells[2].web)
        assertEquals(2, decoded.cells[1].ice)
        assertEquals(1, decoded.cells[1].chain)
    }

    @Test
    fun codecRejectsOverlaysOnBlockers() {
        try {
            LayoutCodec.decode(listOf("iX1 ."))
            error("should fail")
        } catch (e: LayoutCodec.LayoutException) {
            assertTrue("iX1" in e.message!!)
        }
    }

    @Test
    fun generatorIsDeterministic() {
        val g = LevelGenerator()
        for (n in listOf(1, 77, 350, 999)) assertEquals(g.generate(n), g.generate(n))
    }

    @Test
    fun botWinsEasyLevelAndLosesImpossibleOne() {
        val bot = BotPlayer()
        val easy = level(moves = 40, goals = listOf(GoalDef(GoalType.COLLECT_COLOR, 10, 0)))
        assertTrue(bot.play(easy.toGameSpec(), 1).won)
        val impossible = level(moves = 2, goals = listOf(GoalDef(GoalType.COLLECT_COLOR, 200, 0)))
        assertFalse(bot.play(impossible.toGameSpec(), 1).won)
    }
}
