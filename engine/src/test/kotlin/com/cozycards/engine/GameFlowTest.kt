package com.cozycards.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GameFlowTest {

    private fun base() = pattern(7, 7, mapOf(Pos(0, 3) to "0", Pos(1, 3) to "0", Pos(2, 3) to "4", Pos(2, 4) to "0"))

    @Test
    fun winsWhenGoalsComplete() {
        val g = TestBoards.game(base(), goals = listOf(Goal(GoalType.COLLECT_COLOR, 3, color = 0)))
        val r = g.applied(g.swap(Pos(2, 4), Pos(2, 3)))
        assertEquals(GameStatus.WON, r.status)
        assertTrue(g.stars() in 1..3)
        assertTrue(g.swap(Pos(0, 0), Pos(1, 0)) is MoveResult.Invalid)
    }

    @Test
    fun losesWhenOutOfMoves() {
        val g = TestBoards.game(base(), moves = 1)
        val r = g.applied(g.swap(Pos(2, 4), Pos(2, 3)))
        assertEquals(GameStatus.LOST, r.status)
        assertEquals(LossReason.OUT_OF_MOVES, g.lossReason)
        assertEquals(0, g.movesLeft)
    }

    @Test
    fun boostersDoNotSpendMoves() {
        val g = TestBoards.game(base(mapOf()), moves = 5)
        g.applied(g.useHammer(Pos(0, 0)))
        g.applied(g.useAreaClear(Pos(3, 3)))
        g.applied(g.useSuperHammer(Pos(4, 4)))
        g.applied(g.useShuffle())
        assertEquals(5, g.movesLeft)
        g.addMoves(5)
        assertEquals(10, g.movesLeft)
    }

    @Test
    fun swapGloveSwapsWithoutMatch() {
        val g = TestBoards.game(listOf("1 2 3 4", "2 3 4 1", "3 4 1 2", "4 1 2 3"), moves = 5)
        val a = g.board[Pos(0, 0)].piece!!.id
        val r = g.applied(g.useSwapGlove(Pos(0, 0), Pos(1, 0)))
        assertEquals(a, r.steps.first().board[Pos(1, 0)].piece!!.id)
        assertEquals(5, g.movesLeft)
    }

    private fun base(extra: Map<Pos, String>) = pattern(7, 7, extra)

    @Test
    fun placeSpecialsAddsPreLevelBoosters() {
        val g = Match3Game(TestBoards.spec(8, 8), 3)
        val cells = g.placeSpecials(listOf(Special.ROCKET_H, Special.BOMB, Special.RAINBOW))
        assertEquals(3, cells.size)
        assertEquals(3, g.board.count { it.piece?.isSpecial == true })
        assertTrue(MatchFinder.findMatches(g.board).isEmpty())
    }

    @Test
    fun deterministicForSameSeedAndMoves() {
        fun run(): List<String> {
            val g = Match3Game(TestBoards.spec(8, 9, colors = 5, moves = 40), 42)
            val dumps = mutableListOf(g.board.dump())
            repeat(40) {
                if (g.status != GameStatus.PLAYING) return dumps
                val m = g.validMoves().first()
                g.swap(m.first, m.second)
                dumps += g.board.dump() + g.score
            }
            return dumps
        }
        assertEquals(run(), run())
    }

    @Test
    fun differentSeedsGiveDifferentBoards() {
        val a = Match3Game(TestBoards.spec(8, 8), 1).board.dump()
        val b = Match3Game(TestBoards.spec(8, 8), 2).board.dump()
        assertFalse(a == b)
    }

    @Test
    fun multiStageLevelAdvances() {
        val stage = StageSpec(7, 7, List(49) { CellSpec() }, listOf(Goal(GoalType.COLLECT_COLOR, 10, 0)))
        val stage2 = stage.copy(goals = listOf(Goal(GoalType.COLLECT_COLOR, 10, 1)), bonusMoves = 5)
        val g = Match3Game(GameSpec(4, 200, listOf(stage, stage2)), 9)
        var sawStage = false
        var guard = 0
        while (g.status == GameStatus.PLAYING && guard++ < 200) {
            val m = g.validMoves().first()
            val r = g.applied(g.swap(m.first, m.second))
            if (r.events.any { it is GameEvent.StageCleared }) {
                sawStage = true
                assertEquals(1, g.stageIndex)
            }
        }
        assertTrue(sawStage)
        assertEquals(GameStatus.WON, g.status)
    }

    @Test
    fun deadBoardIsShuffledIntoPlayableState() {
        // 3x3 board with no possible move.
        val g = TestBoards.game(listOf("0 1 2", "3 4 0", "1 2 3"), colors = 5)
        assertFalse(g.hasPossibleMove())
        assertTrue(g.shuffleBoard())
        assertTrue(g.hasPossibleMove())
        assertTrue(MatchFinder.findMatches(g.board).isEmpty())
    }

    @Test
    fun tinyBoardWithoutRoomForMovesReportsFailure() {
        val g = TestBoards.game(listOf("0 X1", "X1 1"), colors = 5)
        assertFalse(g.shuffleBoard())
    }

    /**
     * Fuzz test: random boards with every obstacle type, random legal moves and boosters.
     * Checks invariants after each action and that every game terminates.
     */
    @Test
    fun randomPlayKeepsInvariants() {
        for (seed in 1L..120L) {
            val rng = Rng(seed * 7919)
            val spec = randomSpec(rng)
            val g = Match3Game(spec, seed)
            var actions = 0
            while (g.status == GameStatus.PLAYING && actions < 80) {
                actions++
                val roll = rng.nextInt(20)
                val result = when {
                    roll == 0 -> g.useHammer(rng.pick(g.board.positions))
                    roll == 1 -> g.useAreaClear(rng.pick(g.board.positions))
                    else -> {
                        val moves = g.validMoves()
                        if (moves.isEmpty()) fail("seed $seed: PLAYING without a valid move\n${g.board.dump()}")
                        val m = rng.pick(moves)
                        g.swap(m.first, m.second)
                    }
                }
                if (result is MoveResult.Applied) checkInvariants(g, seed)
            }
            assertTrue("seed $seed did not terminate", g.status != GameStatus.PLAYING || actions >= 80)
        }
    }

    private fun checkInvariants(g: Match3Game, seed: Long) {
        val b = g.board
        assertTrue("seed $seed: matches left on board\n${b.dump()}", MatchFinder.findMatches(b).isEmpty())
        val ids = HashSet<Int>()
        for (p in b.positions) {
            val c = b[p]
            if (c.void || c.blocker != null) assertTrue("seed $seed: piece inside void/blocker at $p", c.piece == null)
            c.piece?.let { assertTrue("seed $seed: duplicate piece id", ids.add(it.id)) }
            assertTrue(c.ice in 0..2 && c.chain in 0..2)
        }
        assertTrue(g.movesLeft >= 0)
        if (g.status == GameStatus.PLAYING) assertTrue("seed $seed: no move while playing", g.hasPossibleMove())
    }

    private fun randomSpec(rng: Rng): GameSpec {
        val w = 6 + rng.nextInt(4)
        val h = 6 + rng.nextInt(4)
        val cells = List(w * h) { i ->
            val x = i % w
            val y = i / w
            val r = rng.nextInt(100)
            when {
                (x == 0 || x == w - 1) && y == 0 && rng.nextInt(3) == 0 -> CellSpec(void = true)
                r < 8 -> CellSpec(ice = 1 + rng.nextInt(2))
                r < 12 -> CellSpec(blocker = Blocker(BlockerType.BOX, 1 + rng.nextInt(3)))
                r < 14 -> CellSpec(blocker = Blocker(BlockerType.STONE, 1))
                r < 17 -> CellSpec(chain = 1 + rng.nextInt(2))
                r < 19 -> CellSpec(web = true)
                r < 20 && y > 2 -> CellSpec(blocker = Blocker(BlockerType.LOCK, 1))
                else -> CellSpec()
            }
        }
        val locks = cells.count { it.blocker?.type == BlockerType.LOCK }
        val gravity = if (rng.nextInt(6) == 0) rng.pick(Dir.entries) else Dir.DOWN
        val stage = StageSpec(
            w, h, cells,
            goals = listOf(Goal(GoalType.SCORE, 1_000_000)),
            gravity = gravity,
            dropSpawns = rng.nextInt(3),
            keySpawns = locks,
            gravityCycle = if (rng.nextInt(5) == 0) listOf(Dir.DOWN, Dir.LEFT, Dir.UP, Dir.RIGHT) else emptyList(),
            gravitySwitchEvery = 3,
        )
        return GameSpec(3 + rng.nextInt(3), 60, listOf(stage))
    }
}
