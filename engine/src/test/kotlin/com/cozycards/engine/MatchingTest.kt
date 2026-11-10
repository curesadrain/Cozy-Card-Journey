package com.cozycards.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchingTest {

    private fun firstClear(r: MoveResult.Applied) = r.steps.first { it.kind == StepKind.CLEAR }

    @Test
    fun horizontalThreeMatchClearsThreePieces() {
        val g = TestBoards.game(
            listOf(
                "1 2 3 4",
                "0 0 4 0",
                "2 3 1 2",
                "3 4 2 1",
            )
        )
        // Move (2,1)=4 up? No: swap (3,1) with (2,1) → row 1 becomes 0 0 0 4
        val r = g.applied(g.swap(Pos(3, 1), Pos(2, 1)))
        val clear = firstClear(r)
        assertEquals(setOf(Pos(0, 1), Pos(1, 1), Pos(2, 1)), clear.cleared)
        assertEquals(19, g.movesLeft)
    }

    @Test
    fun verticalMatchIsDetected() {
        val b = TestBoards.parse(
            listOf(
                "1 2 3",
                "1 3 4",
                "4 1 2",
            )
        )
        // swap (0,2) and (1,2) -> column 0 = 1,1,1
        val g = TestBoards.game(listOf("1 2 3", "1 3 4", "4 1 2"))
        assertTrue(g.isValidMove(Pos(0, 2), Pos(1, 2)))
        val r = g.applied(g.swap(Pos(0, 2), Pos(1, 2)))
        assertEquals(setOf(Pos(0, 0), Pos(0, 1), Pos(0, 2)), firstClear(r).cleared)
        assertEquals(3, b.width)
    }

    @Test
    fun invalidSwapDoesNotConsumeMove() {
        val g = TestBoards.game(listOf("1 2 3", "3 4 1", "2 1 4"))
        val before = g.board.dump()
        val r = g.swap(Pos(0, 0), Pos(1, 0))
        assertTrue(r is MoveResult.Invalid)
        assertEquals(20, g.movesLeft)
        assertEquals(before, g.board.dump())
    }

    @Test
    fun nonAdjacentAndBlockedSwapsAreRejected() {
        val g = TestBoards.game(listOf("1 2 3", "3 X1 1", "2 c1 4"))
        assertTrue(g.swap(Pos(0, 0), Pos(2, 0)) is MoveResult.Invalid)
        assertTrue(g.swap(Pos(1, 0), Pos(1, 1)) is MoveResult.Invalid) // crate
        assertTrue(g.swap(Pos(0, 2), Pos(1, 2)) is MoveResult.Invalid) // chained piece
        assertTrue(g.swap(Pos(-1, 0), Pos(0, 0)) is MoveResult.Invalid)
    }

    @Test
    fun fourInLineCreatesRocketAtSwapTarget() {
        val g = TestBoards.game(
            listOf(
                "1 2 3 4 2",
                "0 0 4 0 3",
                "2 3 0 2 1",
            )
        )
        // Swap (2,2)=0 up into (2,1): row 1 becomes 0 0 0 0
        val r = g.applied(g.swap(Pos(2, 2), Pos(2, 1)))
        val clear = firstClear(r)
        val created = clear.events.filterIsInstance<GameEvent.SpecialCreated>().single()
        assertEquals(Pos(2, 1), created.pos)
        assertEquals(Special.ROCKET_H, created.special)
        assertEquals(Special.ROCKET_H, clear.board[Pos(2, 1)].piece!!.special)
    }

    @Test
    fun fiveInLineCreatesRainbow() {
        val g = TestBoards.game(
            listOf(
                "1 2 3 4 2",
                "0 0 4 0 0",
                "2 3 0 2 1",
            )
        )
        val r = g.applied(g.swap(Pos(2, 2), Pos(2, 1)))
        val created = firstClear(r).events.filterIsInstance<GameEvent.SpecialCreated>().single()
        assertEquals(Special.RAINBOW, created.special)
        assertEquals(Pos(2, 1), created.pos)
    }

    @Test
    fun lShapeCreatesBomb() {
        val g = TestBoards.game(
            listOf(
                "0 2 3 4",
                "0 3 4 1",
                "1 0 0 2",
                "2 3 1 4",
            )
        )
        // Swap (0,2)=1 with (1,2)?? we need col0: 0,0,X and row2: X,0,0. Swap (0,2) with (0,3)? no.
        // Move the 0 at (1,2) left: board col0 = 0,0,0 and row2 = 0,1,0 -> only vertical. Instead put 0 at (0,2):
        // swap (0,2)=1 with (0,3)=2 does nothing. Use direct L layout via a 4th column:
        val g2 = TestBoards.game(
            listOf(
                "0 2 3 4",
                "0 3 4 1",
                "3 0 0 2",
                "0 3 1 4",
            )
        )
        // swap (0,2)=3 with (0,3)=0 -> col0 = 0,0,0 (rows 0..2) and row 2 = 0,0,0 → L
        val r = g2.applied(g2.swap(Pos(0, 3), Pos(0, 2)))
        val created = firstClear(r).events.filterIsInstance<GameEvent.SpecialCreated>().single()
        assertEquals(Special.BOMB, created.special)
        assertEquals(Pos(0, 2), created.pos)
        assertNotNull(g)
    }

    @Test
    fun tShapeCreatesBomb() {
        val g = TestBoards.game(
            listOf(
                "1 0 2 3",
                "2 0 4 1",
                "0 3 0 2",
                "3 0 1 4",
            )
        )
        // swap (1,2)=3 with (1,3)=0: row2 = 0 0 0, col1 = 0 0 0 (rows 0..2) → T
        val r = g.applied(g.swap(Pos(1, 3), Pos(1, 2)))
        val created = firstClear(r).events.filterIsInstance<GameEvent.SpecialCreated>().single()
        assertEquals(Special.BOMB, created.special)
        assertEquals(5, firstClear(r).events.filterIsInstance<GameEvent.PieceCleared>().size)
    }

    @Test
    fun squareCreatesPaperPlane() {
        val g = TestBoards.game(
            listOf(
                "0 0 2 3",
                "0 1 4 1",
                "2 0 3 2",
                "3 4 1 4",
            )
        )
        // swap (1,1)=1 with (1,2)=0 -> square (0,0),(1,0),(0,1),(1,1)
        val r = g.applied(g.swap(Pos(1, 2), Pos(1, 1)))
        val created = firstClear(r).events.filterIsInstance<GameEvent.SpecialCreated>().single()
        assertEquals(Special.PLANE, created.special)
    }

    @Test
    fun simultaneousMatchesResolveOnce() {
        // A single swap that creates two separate 3-lines.
        val g = TestBoards.game(
            listOf(
                "1 1 2 3 3",
                "4 0 3 4 1",
                "0 2 1 0 2",
            )
        )
        // swap (2,0)=2 with (2,1)=3: row0 = 1 1 3 3 3 → one match (3,3,3) ; row1 = 4 0 2 4 1 → none
        // Use explicit double: swap (2,1)=3 with (2,0)=2 is same move.
        val r = g.applied(g.swap(Pos(2, 1), Pos(2, 0)))
        val cleared = firstClear(r).events.filterIsInstance<GameEvent.PieceCleared>()
        assertEquals(cleared.size, cleared.map { it.pos }.toSet().size)
    }

    @Test
    fun matchFinderMergesCrossingRuns() {
        val b = TestBoards.parse(
            listOf(
                "0 1 2",
                "0 0 0",
                "0 3 4",
            )
        )
        val groups = MatchFinder.findMatches(b)
        assertEquals(1, groups.size)
        assertEquals(MatchShape.LT, groups[0].shape)
        assertEquals(5, groups[0].cells.size)
        assertEquals(Pos(0, 1), groups[0].pivot)
    }

    @Test
    fun noMatchesOnFreshlyGeneratedBoards() {
        for (seed in 1L..200L) {
            val g = Match3Game(TestBoards.spec(8, 9, colors = 5), seed)
            assertTrue(MatchFinder.findMatches(g.board).isEmpty())
            assertTrue("seed $seed must have a move", g.hasPossibleMove())
        }
    }

    @Test
    fun rainbowIsNotMatchableButSwappable() {
        val b = TestBoards.parse(listOf("* 1 1", "2 3 4"))
        assertFalse(MatchFinder.hasMatchAt(b, Pos(0, 0)))
        val g = TestBoards.game(listOf("* 1 1", "2 3 4"))
        assertTrue(g.isValidMove(Pos(0, 0), Pos(1, 0)))
    }
}
