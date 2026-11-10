package com.cozycards.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObstaclesTest {

    /** Swapping (2,4)<->(2,3) creates a 3-match at (0..2, 3). */
    private fun base(extra: Map<Pos, String> = emptyMap()) =
        pattern(7, 7, mapOf(Pos(0, 3) to "0", Pos(1, 3) to "0", Pos(2, 3) to "4", Pos(2, 4) to "0") + extra)

    private fun play(g: Match3Game) = g.applied(g.swap(Pos(2, 4), Pos(2, 3)))
    private fun clearStep(r: MoveResult.Applied) = r.steps.first { it.kind == StepKind.CLEAR }

    @Test
    fun baseLayoutIsAPlainThreeMatch() {
        val g = TestBoards.game(base())
        assertTrue(MatchFinder.findMatches(g.board).isEmpty())
        val step = clearStep(play(g))
        assertEquals(setOf(Pos(0, 3), Pos(1, 3), Pos(2, 3)), step.events.filterIsInstance<GameEvent.PieceCleared>().map { it.pos }.toSet())
    }

    @Test
    fun iceAndDoubleIce() {
        val g = TestBoards.game(base(mapOf(Pos(0, 3) to "i0", Pos(1, 3) to "I0")), goals = listOf(Goal(GoalType.CLEAR_ICE, 2)))
        val step = clearStep(play(g))
        val ice = step.events.filterIsInstance<GameEvent.IceCracked>().associate { it.pos to it.remaining }
        assertEquals(0, ice[Pos(0, 3)])
        assertEquals(1, ice[Pos(1, 3)])
        assertEquals(1, g.goals.single().current)
        assertEquals(1, g.board[Pos(1, 3)].ice)
    }

    @Test
    fun cratesTakeAdjacentDamageByLayers() {
        val g = TestBoards.game(base(mapOf(Pos(0, 2) to "X1", Pos(1, 2) to "X2", Pos(0, 4) to "X3")))
        val step = clearStep(play(g))
        val dmg = step.events.filterIsInstance<GameEvent.BoxDamaged>().associate { it.pos to it.remaining }
        assertEquals(0, dmg[Pos(0, 2)])
        assertEquals(1, dmg[Pos(1, 2)])
        assertEquals(2, dmg[Pos(0, 4)])
        assertNull(step.board[Pos(0, 2)].blocker)
        assertEquals(1, step.board[Pos(1, 2)].blocker!!.hp)
    }

    @Test
    fun stonesIgnoreMatchesButBreakOnBlasts() {
        val g = TestBoards.game(base(mapOf(Pos(0, 2) to "S1")))
        val step = clearStep(play(g))
        assertTrue(step.events.none { it is GameEvent.StoneDamaged })
        val g2 = TestBoards.game(pattern(7, 7, mapOf(Pos(0, 0) to "1h", Pos(5, 0) to "S1")))
        val r = g2.applied(g2.useHammer(Pos(0, 0)))
        assertTrue(r.events.any { it is GameEvent.StoneDamaged && it.remaining == 0 })
    }

    @Test
    fun chainKeepsPieceAndBreaks() {
        val g = TestBoards.game(base(mapOf(Pos(0, 3) to "c0")), goals = listOf(Goal(GoalType.BREAK_CHAINS, 1)))
        val step = clearStep(play(g))
        assertTrue(step.events.any { it is GameEvent.ChainBroken && it.pos == Pos(0, 3) && it.remaining == 0 })
        assertNotNull(step.board[Pos(0, 3)].piece)
        assertEquals(0, step.board[Pos(0, 3)].chain)
        assertEquals(GameStatus.WON, g.status)
    }

    @Test
    fun doubleChainNeedsTwoHits() {
        val g = TestBoards.game(base(mapOf(Pos(0, 3) to "C0")))
        val step = clearStep(play(g))
        assertEquals(1, step.board[Pos(0, 3)].chain)
        assertFalse(step.board[Pos(0, 3)].isMovable)
    }

    @Test
    fun webClearedByAdjacentMatch() {
        val g = TestBoards.game(base(mapOf(Pos(1, 2) to "w0")), goals = listOf(Goal(GoalType.CLEAR_WEBS, 1)))
        play(g)
        assertFalse(g.board[Pos(1, 2)].web)
        assertEquals(GameStatus.WON, g.status)
    }

    @Test
    fun webbedPieceCannotMoveOrFall() {
        val g = TestBoards.game(pattern(5, 5, mapOf(Pos(2, 1) to "w2")))
        assertFalse(g.canSwap(Pos(2, 1), Pos(2, 2)))
        val id = g.board[Pos(2, 1)].piece!!.id
        val r = g.applied(g.useHammer(Pos(2, 2))) // clear below the webbed piece
        assertEquals(id, r.steps[1].board[Pos(2, 1)].piece!!.id) // still there after falling phase
    }

    @Test
    fun keyOpensNearestLock() {
        val g = TestBoards.game(base(mapOf(Pos(0, 3) to "0k", Pos(5, 5) to "L", Pos(6, 6) to "L")), goals = listOf(Goal(GoalType.OPEN_LOCKS, 1)))
        val step = clearStep(play(g))
        assertTrue(step.events.any { it is GameEvent.KeyCollected })
        assertEquals(Pos(5, 5), step.events.filterIsInstance<GameEvent.LockOpened>().single().pos)
        assertEquals(BlockerType.LOCK, g.board[Pos(6, 6)].blocker?.type)
        assertEquals(GameStatus.WON, g.status)
    }

    @Test
    fun locksIgnoreBlasts() {
        val g = TestBoards.game(pattern(5, 5, mapOf(Pos(0, 0) to "1h", Pos(3, 0) to "L")))
        g.applied(g.useHammer(Pos(0, 0)))
        assertEquals(BlockerType.LOCK, g.board[Pos(3, 0)].blocker?.type)
    }

    @Test
    fun dropDeliveredAtBottom() {
        val rows = pattern(5, 5, mapOf(Pos(0, 4) to "0", Pos(1, 4) to "0", Pos(2, 4) to "4", Pos(2, 3) to "0", Pos(0, 3) to "D"))
        val g = TestBoards.game(rows, goals = listOf(Goal(GoalType.DELIVER_DROPS, 1)))
        val r = g.applied(g.swap(Pos(2, 3), Pos(2, 4)))
        assertTrue(r.events.any { it is GameEvent.DropDelivered && it.pos == Pos(0, 4) })
        assertEquals(GameStatus.WON, g.status)
    }

    @Test
    fun dropsAreImmuneToBlasts() {
        val g = TestBoards.game(pattern(5, 5, mapOf(Pos(0, 0) to "1h", Pos(3, 0) to "D")))
        val id = g.board[Pos(3, 0)].piece!!.id
        val r = g.applied(g.useHammer(Pos(0, 0)))
        assertEquals(id, r.steps.first().board[Pos(3, 0)].piece!!.id)
    }

    @Test
    fun portalMovesPiecesToAnotherColumn() {
        val g = TestBoards.game(
            listOf("1 0v", "2 4", "3 0"),
            portals = listOf(Portal(Pos(0, 2), Pos(1, 0))),
        )
        val col0 = (0..2).map { g.board[Pos(0, it)].piece!!.id }.toSet()
        val r = g.applied(g.useHammer(Pos(1, 0)))
        val fall = r.steps.first { it.kind == StepKind.FALL }
        val col1 = (0..2).map { fall.board[Pos(1, it)].piece!!.id }.toSet()
        assertEquals(col0, col1)
    }

    @Test
    fun conveyorAdvancesPiecesAfterMove() {
        val loop = listOf(Pos(0, 0), Pos(1, 0), Pos(2, 0), Pos(3, 0), Pos(4, 0), Pos(5, 0), Pos(6, 0))
        val g = TestBoards.game(base(), conveyors = listOf(loop))
        val r = play(g)
        val idx = r.steps.indexOfFirst { it.kind == StepKind.CONVEYOR }
        assertTrue(idx > 0)
        val before = r.steps[idx - 1].board
        val after = r.steps[idx].board
        for (i in loop.indices) {
            assertEquals(before[loop[i]].piece?.id, after[loop[(i + 1) % loop.size]].piece?.id)
        }
    }

    @Test
    fun upwardGravitySpawnsAtBottom() {
        val g = TestBoards.game(listOf("1 2 3", "2 3 1", "3 1 2"), gravity = Dir.UP)
        val idBelow = g.board[Pos(1, 1)].piece!!.id
        val r = g.applied(g.useHammer(Pos(1, 0)))
        val fall = r.steps.first { it.kind == StepKind.FALL }
        assertEquals(idBelow, fall.board[Pos(1, 0)].piece!!.id)
        assertTrue(fall.board[Pos(1, 2)].piece!!.id in fall.spawnedIds)
    }

    @Test
    fun sideGravityWorks() {
        val g = TestBoards.game(listOf("1 2 3", "2 3 1", "3 1 2"), gravity = Dir.RIGHT)
        val idLeft = g.board[Pos(1, 1)].piece!!.id
        val r = g.applied(g.useHammer(Pos(2, 1)))
        val fall = r.steps.first { it.kind == StepKind.FALL }
        assertEquals(idLeft, fall.board[Pos(2, 1)].piece!!.id)
    }

    @Test
    fun diagonalFillUnderCrates() {
        // Crate at (1,0) on top row; hammering (1,1) leaves a hole that can only fill diagonally.
        val g = TestBoards.game(listOf("1 X3 3", "2 3 1", "3 1 2"))
        val r = g.applied(g.useHammer(Pos(1, 2)))
        val board = r.steps.last().board
        assertNotNull(board[Pos(1, 1)].piece)
        assertNotNull(board[Pos(1, 2)].piece)
    }

    @Test
    fun voidCellsAreSkippedByGravity() {
        val g = TestBoards.game(listOf("1 2 3", "2 # 1", "3 1 2"))
        val topId = g.board[Pos(1, 0)].piece!!.id
        val r = g.applied(g.useHammer(Pos(1, 2)))
        val fall = r.steps.first { it.kind == StepKind.FALL }
        assertEquals(topId, fall.board[Pos(1, 2)].piece!!.id)
        assertNull(fall.board[Pos(1, 1)].piece)
    }
}
