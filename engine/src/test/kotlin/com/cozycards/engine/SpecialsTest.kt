package com.cozycards.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Builds match-free boards: colour = (x + 2y) % 5, then individual cells are overridden. */
internal fun pattern(w: Int, h: Int, overrides: Map<Pos, String> = emptyMap()): List<String> =
    (0 until h).map { y -> (0 until w).joinToString(" ") { x -> overrides[Pos(x, y)] ?: ((x + 2 * y) % 5).toString() } }

class SpecialsTest {

    private fun clearedIn(r: MoveResult.Applied, kind: StepKind = StepKind.CLEAR) =
        r.steps.first { it.kind == kind }.events.filterIsInstance<GameEvent.PieceCleared>().map { it.pos }.toSet()

    @Test
    fun rocketInMatchClearsWholeRow() {
        val g = TestBoards.game(pattern(7, 7, mapOf(Pos(1, 3) to "0h", Pos(0, 3) to "0", Pos(2, 3) to "4", Pos(3, 3) to "0", Pos(4, 3) to "3", Pos(2, 4) to "0")))
        // swap (2,3)=4 with (2,4)=0 → row3: 0 0h 0 ...
        assertTrue(MatchFinder.findMatches(g.board).isEmpty())
        val r = g.applied(g.swap(Pos(2, 4), Pos(2, 3)))
        val cleared = clearedIn(r)
        for (x in 0 until 7) assertTrue("row cell $x cleared", Pos(x, 3) in cleared)
        assertTrue(r.events.any { it is GameEvent.SpecialActivated && it.special == Special.ROCKET_H })
    }

    @Test
    fun hammerOnBombClearsDiamond() {
        val g = TestBoards.game(pattern(7, 7, mapOf(Pos(3, 3) to "1b")))
        val r = g.applied(g.useHammer(Pos(3, 3)))
        val cleared = clearedIn(r, StepKind.BOOSTER)
        val expected = (0 until 7).flatMap { y -> (0 until 7).map { Pos(it, y) } }
            .filter { Math.abs(it.x - 3) + Math.abs(it.y - 3) <= 2 }.toSet()
        assertEquals(expected, cleared)
        assertEquals(20, g.movesLeft)
    }

    @Test
    fun rainbowWithNormalClearsColour() {
        val g = TestBoards.game(pattern(6, 6, mapOf(Pos(0, 0) to "*")))
        val colorRight = g.board[Pos(1, 0)].piece!!.color
        val expectedCount = g.board.positions.count { g.board.matchColor(it) == colorRight }
        val r = g.applied(g.swap(Pos(0, 0), Pos(1, 0)))
        val cleared = r.steps.first { it.kind == StepKind.CLEAR }.events.filterIsInstance<GameEvent.PieceCleared>()
        // all pieces of that colour plus the rainbow itself
        assertEquals(expectedCount + 1, cleared.size)
        assertTrue(cleared.filter { it.special == Special.NONE }.all { it.color == colorRight })
    }

    @Test
    fun rocketPlusRocketClearsCross() {
        val g = TestBoards.game(pattern(7, 7, mapOf(Pos(2, 3) to "0h", Pos(3, 3) to "1v")))
        val r = g.applied(g.swap(Pos(2, 3), Pos(3, 3)))
        val cleared = clearedIn(r)
        for (i in 0 until 7) {
            assertTrue(Pos(i, 3) in cleared)
            assertTrue(Pos(3, i) in cleared)
        }
        assertTrue(r.events.any { it is GameEvent.Combo })
    }

    @Test
    fun rocketPlusBombClearsThreeRowsAndColumns() {
        val g = TestBoards.game(pattern(9, 9, mapOf(Pos(4, 4) to "0h", Pos(4, 5) to "1b")))
        val r = g.applied(g.swap(Pos(4, 4), Pos(4, 5)))
        val cleared = clearedIn(r)
        for (i in 0 until 9) for (d in -1..1) {
            assertTrue(Pos(i, 5 + d) in cleared)
            assertTrue(Pos(4 + d, i) in cleared)
        }
    }

    @Test
    fun bombPlusBombClearsLargeSquare() {
        val g = TestBoards.game(pattern(9, 9, mapOf(Pos(4, 4) to "0b", Pos(5, 4) to "1b")))
        val r = g.applied(g.swap(Pos(4, 4), Pos(5, 4)))
        val cleared = clearedIn(r)
        for (dy in -3..3) for (dx in -3..3) {
            val p = Pos(5 + dx, 4 + dy)
            if (p.x in 0..8) assertTrue("$p", p in cleared)
        }
    }

    @Test
    fun rainbowPlusRocketTurnsColourIntoRockets() {
        val g = TestBoards.game(pattern(7, 7, mapOf(Pos(3, 3) to "*", Pos(4, 3) to "2h")))
        val twos = g.board.positions.count { g.board.matchColor(it) == 2 } - 1
        val r = g.applied(g.swap(Pos(3, 3), Pos(4, 3)))
        val rocketActivations = r.steps.first { it.kind == StepKind.CLEAR }.events
            .count { it is GameEvent.SpecialActivated && it.special.isRocket }
        assertTrue("expected >= $twos rocket activations, got $rocketActivations", rocketActivations >= twos)
    }

    @Test
    fun rainbowPlusBombTurnsColourIntoBombs() {
        val g = TestBoards.game(pattern(7, 7, mapOf(Pos(3, 3) to "*", Pos(4, 3) to "2b")))
        val r = g.applied(g.swap(Pos(3, 3), Pos(4, 3)))
        val bombs = r.steps.first { it.kind == StepKind.CLEAR }.events
            .count { it is GameEvent.SpecialActivated && it.special == Special.BOMB }
        assertTrue(bombs >= 5)
    }

    @Test
    fun rainbowPlusRainbowClearsEverything() {
        val g = TestBoards.game(pattern(6, 6, mapOf(Pos(2, 2) to "*", Pos(3, 2) to "*", Pos(0, 0) to "X2", Pos(5, 5) to "i1")))
        val r = g.applied(g.swap(Pos(2, 2), Pos(3, 2)))
        val step = r.steps.first { it.kind == StepKind.CLEAR }
        // All 34 coloured pieces + 2 rainbows cleared, crate (2hp) destroyed by the double pass, ice cracked
        assertEquals(35, step.events.filterIsInstance<GameEvent.PieceCleared>().size)
        assertTrue(step.events.any { it is GameEvent.BoxDamaged && it.remaining == 0 })
        assertTrue(step.events.any { it is GameEvent.IceCracked })
    }

    @Test
    fun planeFliesToGoalObstacle() {
        val g = TestBoards.game(
            pattern(7, 7, mapOf(Pos(0, 0) to "1p", Pos(6, 6) to "X1")),
            goals = listOf(Goal(GoalType.BREAK_BOXES, 1)),
        )
        val r = g.applied(g.useHammer(Pos(0, 0)))
        assertTrue(r.steps.first().effects.any { it is Effect.Flight && it.to == Pos(6, 6) })
        assertEquals(GameStatus.WON, g.status)
    }

    @Test
    fun planePlusRocketCarriesRocketToTarget() {
        val g = TestBoards.game(
            pattern(7, 7, mapOf(Pos(0, 0) to "1p", Pos(1, 0) to "2v", Pos(5, 6) to "X1")),
            goals = listOf(Goal(GoalType.BREAK_BOXES, 1)),
        )
        val r = g.applied(g.swap(Pos(0, 0), Pos(1, 0)))
        val effects = r.steps.first { it.kind == StepKind.CLEAR }.effects
        assertTrue(effects.any { it is Effect.Flight && it.to == Pos(5, 6) })
        assertTrue(effects.any { it is Effect.Line && !it.horizontal && it.pos == Pos(5, 6) })
    }

    @Test
    fun planePlusPlaneLaunchesThree() {
        val g = TestBoards.game(pattern(7, 7, mapOf(Pos(2, 2) to "1p", Pos(3, 2) to "2p")))
        val r = g.applied(g.swap(Pos(2, 2), Pos(3, 2)))
        assertEquals(3, r.steps.first { it.kind == StepKind.CLEAR }.effects.count { it is Effect.Flight })
    }

    @Test
    fun chainedSpecialsChainReact() {
        // A rocket line hits a bomb which explodes.
        val g = TestBoards.game(pattern(7, 7, mapOf(Pos(0, 3) to "1h", Pos(5, 3) to "2b")))
        val r = g.applied(g.useHammer(Pos(0, 3)))
        val acts = r.steps.first().events.filterIsInstance<GameEvent.SpecialActivated>().map { it.special }
        assertTrue(Special.ROCKET_H in acts && Special.BOMB in acts)
    }
}
