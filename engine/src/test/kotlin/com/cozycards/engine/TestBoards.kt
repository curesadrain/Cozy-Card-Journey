package com.cozycards.engine

/**
 * Tiny text format for exact test boards. Tokens are separated by spaces:
 *  `#` void, `.` empty, `X1`..`X3` crate, `S1`/`S2` stone, `L` lock, `D` delivery item, `*` rainbow,
 *  `<digit>[h|v|b|p|k]` coloured piece (rocket H/V, bomb, plane, key).
 *  Prefixes: `i`/`I` ice 1/2, `c`/`C` chain 1/2, `w` web, e.g. `i0`, `C3h`, `w2`.
 */
object TestBoards {
    private var nextId = 10_000

    fun parse(rows: List<String>): Board {
        val grid = rows.map { it.trim().split(Regex("\\s+")) }
        val h = grid.size
        val w = grid.first().size
        require(grid.all { it.size == w }) { "ragged rows" }
        val b = Board(w, h)
        for (y in 0 until h) for (x in 0 until w) apply(b[x, y], grid[y][x])
        return b
    }

    private fun apply(c: Cell, token: String) {
        var t = token
        while (t.isNotEmpty() && t[0] in "iIcCw" && t.length > 1) {
            when (t[0]) {
                'i' -> c.ice = 1
                'I' -> c.ice = 2
                'c' -> c.chain = 1
                'C' -> c.chain = 2
                'w' -> c.web = true
            }
            t = t.substring(1)
        }
        when {
            t == "#" -> c.void = true
            t == "." -> Unit
            t == "L" -> c.blocker = Blocker(BlockerType.LOCK, 1)
            t.startsWith("X") -> c.blocker = Blocker(BlockerType.BOX, t.substring(1).toInt())
            t.startsWith("S") -> c.blocker = Blocker(BlockerType.STONE, t.substring(1).toInt())
            t == "D" -> c.piece = Piece(nextId++, -1, PieceKind.DROP)
            t == "*" -> c.piece = Piece(nextId++, -1, PieceKind.NORMAL, Special.RAINBOW)
            t[0].isDigit() -> {
                val color = t[0] - '0'
                val mod = t.getOrNull(1)
                val special = when (mod) {
                    'h' -> Special.ROCKET_H; 'v' -> Special.ROCKET_V; 'b' -> Special.BOMB; 'p' -> Special.PLANE
                    else -> Special.NONE
                }
                val kind = if (mod == 'k') PieceKind.KEY else PieceKind.NORMAL
                c.piece = Piece(nextId++, color, kind, special)
            }
            else -> error("bad token $token")
        }
    }

    fun spec(
        w: Int,
        h: Int,
        colors: Int = 5,
        moves: Int = 20,
        goals: List<Goal> = listOf(Goal(GoalType.SCORE, 10_000_000)),
        gravity: Dir = Dir.DOWN,
        portals: List<Portal> = emptyList(),
        conveyors: List<List<Pos>> = emptyList(),
        stages: Int = 1,
    ): GameSpec {
        val stage = StageSpec(w, h, List(w * h) { CellSpec() }, goals, gravity, portals, conveyors)
        return GameSpec(colors, moves, List(stages) { stage })
    }

    fun game(
        rows: List<String>,
        colors: Int = 5,
        moves: Int = 20,
        goals: List<Goal> = listOf(Goal(GoalType.SCORE, 10_000_000)),
        gravity: Dir = Dir.DOWN,
        portals: List<Portal> = emptyList(),
        conveyors: List<List<Pos>> = emptyList(),
        seed: Long = 7,
    ): Match3Game {
        val board = parse(rows)
        val g = Match3Game(spec(board.width, board.height, colors, moves, goals, gravity, portals, conveyors), seed)
        g.overrideBoard(board)
        return g
    }
}

fun Match3Game.applied(r: MoveResult): MoveResult.Applied {
    check(r is MoveResult.Applied) { "expected applied move but got $r" }
    return r
}
