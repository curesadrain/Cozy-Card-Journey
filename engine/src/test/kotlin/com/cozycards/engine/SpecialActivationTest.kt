package com.cozycards.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Specials are colourless and fire when moved in any direction or double-tapped. */
class SpecialActivationTest {

    private fun colorless(rows: List<String>, at: Pos, special: Special): Match3Game {
        val g = TestBoards.game(rows)
        val old = g.board[at].piece!!
        g.board[at].piece = old.copy(color = -1, special = special)
        return g
    }

    @Test
    fun swipingASpecialFiresItAtTheDestinationWithoutAMatch() {
        val g = colorless(pattern(7, 7), Pos(3, 3), Special.ROCKET_H)
        assertTrue(g.isValidMove(Pos(3, 3), Pos(3, 2)))
        val r = g.applied(g.swap(Pos(3, 3), Pos(3, 2)))
        val clear = r.steps.first { it.kind == StepKind.CLEAR }
        // The rocket moved up one row and cleared that row.
        val cleared = clear.events.filterIsInstance<GameEvent.PieceCleared>().map { it.pos }.toSet()
        for (x in 0 until 7) assertTrue("($x,2)", Pos(x, 2) in cleared)
        assertTrue(clear.events.any { it is GameEvent.SpecialActivated && it.special == Special.ROCKET_H && it.pos == Pos(3, 2) })
        assertEquals(19, g.movesLeft)
    }

    @Test
    fun doubleTapFiresInPlace() {
        val g = colorless(pattern(7, 7), Pos(3, 3), Special.BOMB)
        assertTrue(g.canActivate(Pos(3, 3)))
        val r = g.applied(g.activate(Pos(3, 3)))
        assertTrue(r.events.any { it is GameEvent.SpecialActivated && it.special == Special.BOMB && it.pos == Pos(3, 3) })
        assertEquals(19, g.movesLeft)
        assertTrue(g.activate(Pos(0, 0)) is MoveResult.Invalid)
    }

    @Test
    fun createdSpecialsAreColourlessAndDoNotMatch() {
        val g = TestBoards.game(listOf("1 2 3 4 2", "0 0 4 0 3", "2 3 0 2 1"))
        val r = g.applied(g.swap(Pos(2, 2), Pos(2, 1)))
        val rocket = r.steps.first { it.kind == StepKind.CLEAR }.board[Pos(2, 1)].piece!!
        assertEquals(Special.ROCKET_H, rocket.special)
        assertEquals(-1, rocket.color)
        val b = TestBoards.parse(listOf("0 0 1"))
        b[Pos(2, 0)].piece = Piece(1, -1, PieceKind.NORMAL, Special.BOMB)
        assertTrue(MatchFinder.findMatches(b).isEmpty())
    }

    @Test
    fun rainbowSwappedWithTileClearsThatColour() {
        val g = TestBoards.game(pattern(6, 6, mapOf(Pos(0, 0) to "*")))
        val color = g.board[Pos(1, 0)].piece!!.color
        val r = g.applied(g.swap(Pos(0, 0), Pos(1, 0)))
        val cleared = r.steps.first { it.kind == StepKind.CLEAR }.events.filterIsInstance<GameEvent.PieceCleared>()
        assertTrue(cleared.filter { it.special == Special.NONE }.all { it.color == color })
    }

    @Test
    fun boardWithOnlyASpecialStillHasAMove() {
        val g = colorless(listOf("0 1 2", "3 4 0", "1 2 3"), Pos(1, 1), Special.PLANE)
        assertTrue(g.hasPossibleMove())
    }
}
