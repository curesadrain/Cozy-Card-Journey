package com.cozycards.engine

/**
 * Describes where pieces flow on a board for a given gravity direction and set of portals.
 * Void cells are "skipped": pieces fall through gaps in the board shape.
 * Topology does not depend on blockers – those only stop the flow temporarily.
 */
class Topology(private val board: Board, val gravity: Dir, val portals: List<Portal>) {
    private val portalByEntrance = portals.associateBy { it.entrance }
    private val portalByExit = portals.associateBy { it.exit }

    /** Natural successor ignoring portals. */
    fun naturalNext(p: Pos): Pos? = walk(p, gravity)

    /** Natural predecessor ignoring portals. */
    fun naturalPrev(p: Pos): Pos? = walk(p, gravity.opposite)

    private fun walk(p: Pos, d: Dir): Pos? {
        var q = p + d
        while (board.inBounds(q)) {
            if (!board[q].void) return q
            q += d
        }
        return null
    }

    fun next(p: Pos): Pos? = portalByEntrance[p]?.exit ?: naturalNext(p)

    fun prev(p: Pos): Pos? {
        portalByExit[p]?.let { return it.entrance }
        val q = naturalPrev(p) ?: return null
        // If the cell above is a portal entrance, its pieces go elsewhere.
        return if (portalByEntrance.containsKey(q)) null else q
    }

    /** New pieces appear in empty spawn cells. */
    fun isSpawn(p: Pos): Boolean = !board[p].void && prev(p) == null && !portalByExit.containsKey(p)

    /** Delivery exits: the last cell of a flow path. */
    fun isExit(p: Pos): Boolean = !board[p].void && next(p) == null

    /** Positions sorted so the "lowest" (in gravity direction) come first. */
    val bottomFirst: List<Pos> = board.positions.filter { !board[it].void }.sortedByDescending { depth(it) }

    private fun depth(p: Pos) = p.x * gravity.dx + p.y * gravity.dy
}

/** Result of letting pieces fall and refilling the board. */
class SettleResult(val spawnedIds: Set<Int>, val moved: Boolean)

class Spawner(
    private val rng: Rng,
    private val colors: Int,
    private val nextId: () -> Int,
) {
    var pendingDrops = 0
    var pendingKeys = 0

    /** Called for every spawn; [movesLeftRatio] lets the spawner release items steadily. */
    fun spawn(board: Board, dropsOnBoard: Int): Piece {
        if (pendingDrops > 0 && dropsOnBoard < 2 && rng.nextInt(6) == 0) {
            pendingDrops--
            return Piece(nextId(), -1, PieceKind.DROP)
        }
        if (pendingKeys > 0 && rng.nextInt(10) == 0) {
            pendingKeys--
            return Piece(nextId(), rng.nextInt(colors), PieceKind.KEY)
        }
        return Piece(nextId(), rng.nextInt(colors))
    }
}

object Gravity {
    /**
     * Moves pieces in gravity direction until stable, spawning new pieces at spawn cells and
     * sliding pieces diagonally into holes that cannot be filled straight (e.g. below crates).
     * Deterministic, and guaranteed to terminate: each move strictly lowers a piece and spawns only
     * fill empty cells.
     */
    fun settle(board: Board, topo: Topology, spawner: Spawner): SettleResult {
        val spawned = HashSet<Int>()
        var anyMove = false
        val maxIterations = board.width * board.height * 8 + 64
        var iterations = 0
        while (iterations++ < maxIterations) {
            var changed = false
            for (p in topo.bottomFirst) {
                val cell = board[p]
                if (!cell.canHold || cell.piece != null) continue
                val q = topo.prev(p)
                if (q == null) {
                    if (topo.isSpawn(p)) {
                        val drops = board.count { it.piece?.kind == PieceKind.DROP }
                        val piece = spawner.spawn(board, drops)
                        cell.piece = piece
                        spawned += piece.id
                        changed = true
                    }
                } else if (board[q].isMovable) {
                    cell.piece = board[q].piece
                    board[q].piece = null
                    changed = true
                }
            }
            if (!changed) {
                // Diagonal slide into holes whose straight supply is blocked.
                loop@ for (p in topo.bottomFirst) {
                    val cell = board[p]
                    if (!cell.canHold || cell.piece != null) continue
                    if (straightSupplyAvailable(board, topo, p)) continue
                    for (side in topo.gravity.sides) {
                        val q = p - topo.gravity + side
                        if (!board.inBounds(q)) continue
                        val src = board[q]
                        if (!src.isMovable) continue
                        // Only slide if the source cannot fall straight.
                        val qNext = topo.next(q)
                        if (qNext != null && board[qNext].canHold && board[qNext].piece == null) continue
                        cell.piece = src.piece
                        src.piece = null
                        changed = true
                        break@loop
                    }
                }
            }
            if (!changed) break
            anyMove = true
        }
        return SettleResult(spawned, anyMove)
    }

    /** True if something above [p] will eventually fall straight into it. */
    private fun straightSupplyAvailable(board: Board, topo: Topology, p: Pos): Boolean {
        var q = topo.prev(p) ?: return topo.isSpawn(p)
        var guard = 0
        while (guard++ < board.width * board.height) {
            val c = board[q]
            if (!c.canHold) return false
            if (c.piece != null) return c.isMovable
            q = topo.prev(q) ?: return topo.isSpawn(q)
        }
        return false
    }
}
