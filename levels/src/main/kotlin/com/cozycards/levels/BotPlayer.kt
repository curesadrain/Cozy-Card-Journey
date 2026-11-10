package com.cozycards.levels

import com.cozycards.engine.BlockerType
import com.cozycards.engine.GameSpec
import com.cozycards.engine.GameStatus
import com.cozycards.engine.GoalType
import com.cozycards.engine.Match3Game
import com.cozycards.engine.MatchFinder
import com.cozycards.engine.MatchShape
import com.cozycards.engine.PieceKind
import com.cozycards.engine.Pos
import com.cozycards.engine.Rng
import com.cozycards.engine.Special

/**
 * Greedy heuristic player used to verify that levels are winnable and to calibrate move limits.
 * It looks one move ahead with a cheap evaluation (no full cascade simulation), so it plays
 * noticeably worse than an attentive human – a deliberately conservative yardstick.
 */
class BotPlayer(private val noise: Double = 0.15) {

    data class Outcome(val won: Boolean, val movesUsed: Int, val stagesCleared: Int)

    fun play(spec: GameSpec, seed: Long, maxActions: Int = 400): Outcome {
        val game = Match3Game(spec, seed)
        game.recordSteps = false
        val rng = Rng(seed xor 0x5DEECE66DL)
        var actions = 0
        while (game.status == GameStatus.PLAYING && actions++ < maxActions) {
            val move = choose(game, rng) ?: break
            game.swap(move.first, move.second)
        }
        return Outcome(game.status == GameStatus.WON, game.movesMade, game.stageIndex)
    }

    fun choose(game: Match3Game, rng: Rng): Pair<Pos, Pos>? {
        val moves = game.validMoves()
        if (moves.isEmpty()) return null
        var best: Pair<Pos, Pos>? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (m in moves) {
            val s = evaluate(game, m.first, m.second) + rng.nextDouble() * noise * 10
            if (s > bestScore) { bestScore = s; best = m }
        }
        return best
    }

    private fun specialValue(s: Special) = when (s) {
        Special.NONE -> 0.0
        Special.ROCKET_H, Special.ROCKET_V -> 14.0
        Special.BOMB -> 18.0
        Special.PLANE -> 12.0
        Special.RAINBOW -> 30.0
    }

    fun evaluate(game: Match3Game, a: Pos, b: Pos): Double {
        val board = game.board
        val pa = board[a].piece!!
        val pb = board[b].piece!!
        val open = game.goals.filter { !it.done }.map { it.goal }
        val wantsColor = open.filter { it.type == GoalType.COLLECT_COLOR }.map { it.color }.toSet()
        val obstacleGoals = open.map { it.type }.toSet()

        if (pa.isSpecial && pb.isSpecial) return 60 + specialValue(pa.special) + specialValue(pb.special)
        if (pa.special == Special.RAINBOW || pb.special == Special.RAINBOW) {
            val other = if (pa.special == Special.RAINBOW) pb else pa
            return 40 + specialValue(other.special) * 2 + if (other.color in wantsColor) 15 else 0
        }

        // Temporarily swap to inspect the resulting groups.
        board[a].piece = pb; board[b].piece = pa
        val groups = MatchFinder.findMatches(board).filter { a in it.cells || b in it.cells }
        var score = 0.0
        for (g in groups) {
            score += g.cells.size * 2.0
            score += when (g.shape) {
                MatchShape.LINE5 -> 40.0
                MatchShape.LT -> 22.0
                MatchShape.LINE4 -> 16.0
                MatchShape.SQUARE -> 13.0
                MatchShape.LINE3 -> 0.0
            }
            if (g.color in wantsColor) score += g.cells.size * 4.0
            for (p in g.cells) {
                val c = board[p]
                if (c.piece?.isSpecial == true) score += specialValue(c.piece!!.special)
                if (c.ice > 0 && GoalType.CLEAR_ICE in obstacleGoals) score += 7.0
                if (c.chain > 0 && GoalType.BREAK_CHAINS in obstacleGoals) score += 7.0
                if (c.piece?.kind == PieceKind.KEY && GoalType.OPEN_LOCKS in obstacleGoals) score += 12.0
                for (n in p.neighbors4()) {
                    val nc = board.cellOrNull(n) ?: continue
                    if (n in g.cells) continue
                    if (nc.blocker?.type == BlockerType.BOX && GoalType.BREAK_BOXES in obstacleGoals) score += 6.0
                    if (nc.web && GoalType.CLEAR_WEBS in obstacleGoals) score += 6.0
                }
                // Clearing under a delivery item helps it fall.
                if (GoalType.DELIVER_DROPS in obstacleGoals) {
                    val above = game.topology.prev(p)
                    if (above != null && board.pieceAt(above)?.kind == PieceKind.DROP) score += 10.0
                }
            }
        }
        board[a].piece = pa; board[b].piece = pb
        // Prefer moves deeper in gravity direction: they trigger more cascades.
        val g = game.gravity
        score += ((a.x + b.x) * g.dx + (a.y + b.y) * g.dy) * 0.15
        return score
    }
}
