package com.cozycards.engine

enum class HitKind {
    /** Cell is part of a match. */
    MATCH,

    /** Neighbour of a matched cell: damages crates and webs only. */
    ADJACENT,

    /** Special piece or booster explosion: damages everything except locks. */
    BLAST,
}

/**
 * Applies damage and special-piece activations to a board. One instance lives for the duration
 * of a single clear phase so that every special activates at most once per phase.
 */
internal class Resolver(
    private val board: Board,
    private val rng: Rng,
    private val goalsHint: () -> List<GoalProgress>,
    private val nextId: () -> Int,
) {
    val events = mutableListOf<GameEvent>()
    val effects = mutableListOf<Effect>()
    val touched = LinkedHashSet<Pos>()
    var clearedPieces = 0
        private set

    private val activated = HashSet<Int>()
    private val queue = ArrayDeque<Activation>()

    private class Activation(val pos: Pos, val piece: Piece, val carried: Special = Special.NONE)

    fun hit(p: Pos, kind: HitKind) {
        if (!board.inBounds(p)) return
        val cell = board[p]
        if (cell.void) return
        val blocker = cell.blocker
        if (blocker != null) {
            when (blocker.type) {
                BlockerType.BOX -> if (kind != HitKind.MATCH) {
                    val hp = blocker.hp - 1
                    cell.blocker = if (hp > 0) blocker.copy(hp = hp) else null
                    events += GameEvent.BoxDamaged(p, hp)
                    touched += p
                }
                BlockerType.STONE -> if (kind == HitKind.BLAST) {
                    val hp = blocker.hp - 1
                    cell.blocker = if (hp > 0) blocker.copy(hp = hp) else null
                    events += GameEvent.StoneDamaged(p, hp)
                    touched += p
                }
                BlockerType.LOCK -> Unit
            }
            return
        }
        if (cell.web) {
            cell.web = false
            events += GameEvent.WebCleared(p)
            touched += p
            return
        }
        if (kind == HitKind.ADJACENT) return
        val piece = cell.piece
        if (piece == null) {
            if (kind == HitKind.BLAST) crackIce(p, cell)
            return
        }
        if (cell.chain > 0) {
            cell.chain--
            events += GameEvent.ChainBroken(p, cell.chain)
            touched += p
            return
        }
        if (piece.kind == PieceKind.DROP) return
        removePiece(p, cell, piece)
    }

    private fun removePiece(p: Pos, cell: Cell, piece: Piece) {
        cell.piece = null
        touched += p
        clearedPieces++
        events += GameEvent.PieceCleared(p, piece.color, piece.special)
        if (piece.kind == PieceKind.KEY) {
            events += GameEvent.KeyCollected(p)
            openLockNear(p)
        }
        crackIce(p, cell)
        if (piece.isSpecial && activated.add(piece.id)) queue += Activation(p, piece)
    }

    private fun crackIce(p: Pos, cell: Cell) {
        if (cell.ice > 0) {
            cell.ice--
            events += GameEvent.IceCracked(p, cell.ice)
            touched += p
        }
    }

    private fun openLockNear(from: Pos) {
        val locks = board.find { it.blocker?.type == BlockerType.LOCK }
        val target = locks.minWithOrNull(compareBy<Pos>({ Math.abs(it.x - from.x) + Math.abs(it.y - from.y) }, { it.y }, { it.x }))
            ?: return
        board[target].blocker = null
        events += GameEvent.LockOpened(target)
        touched += target
    }

    /** Clears a match group, optionally converting [specialAt] into a new special piece. */
    fun clearGroup(group: MatchGroup, specialAt: Pos?) {
        val newSpecial = group.createdSpecial
        val adjacent = LinkedHashSet<Pos>()
        for (p in group.cells) {
            if (p == specialAt && newSpecial != Special.NONE) {
                val cell = board[p]
                val old = cell.piece ?: continue
                if (cell.chain > 0) { hit(p, HitKind.MATCH); continue }
                if (old.isSpecial && activated.add(old.id)) queue += Activation(p, old)
                crackIce(p, cell)
                // Specials are colourless: they never take part in colour matches, only fire.
                cell.piece = Piece(nextId(), -1, old.kind.let { if (it == PieceKind.KEY) PieceKind.NORMAL else it }, newSpecial)
                if (old.kind == PieceKind.KEY) { events += GameEvent.KeyCollected(p); openLockNear(p) }
                clearedPieces++
                events += GameEvent.PieceCleared(p, group.color, Special.NONE)
                events += GameEvent.SpecialCreated(p, newSpecial)
                touched += p
            } else {
                hit(p, HitKind.MATCH)
            }
            for (n in p.neighbors4()) if (n !in group.cells) adjacent += n
        }
        for (n in adjacent) hit(n, HitKind.ADJACENT)
    }

    /** Activates a special that is currently on the board at [p] (e.g. by booster). */
    fun detonate(p: Pos) {
        val piece = board.pieceAt(p) ?: return
        if (!piece.isSpecial) { hit(p, HitKind.BLAST); return }
        hit(p, HitKind.BLAST)
    }

    /** Runs queued activations until none remain. */
    fun drain() {
        var guard = 0
        while (queue.isNotEmpty() && guard++ < 10_000) {
            val a = queue.removeFirst()
            activate(a.pos, a.piece, a.carried)
        }
    }

    private fun activate(p: Pos, piece: Piece, carried: Special) {
        events += GameEvent.SpecialActivated(p, piece.special)
        when (piece.special) {
            Special.ROCKET_H -> line(p, horizontal = true)
            Special.ROCKET_V -> line(p, horizontal = false)
            Special.BOMB -> diamond(p, 2)
            Special.RAINBOW -> {
                val color = mostCommonColor()
                if (color >= 0) colorWave(p, color)
            }
            Special.PLANE -> plane(p, carried)
            Special.NONE -> Unit
        }
    }

    fun line(p: Pos, horizontal: Boolean) {
        effects += Effect.Line(p, horizontal)
        if (horizontal) for (x in 0 until board.width) hit(Pos(x, p.y), HitKind.BLAST)
        else for (y in 0 until board.height) hit(Pos(p.x, y), HitKind.BLAST)
    }

    fun diamond(p: Pos, r: Int) {
        effects += Effect.Blast(p, r)
        for (dy in -r..r) for (dx in -r..r) if (Math.abs(dx) + Math.abs(dy) <= r) hit(Pos(p.x + dx, p.y + dy), HitKind.BLAST)
    }

    fun square(p: Pos, r: Int) {
        effects += Effect.Blast(p, r)
        for (dy in -r..r) for (dx in -r..r) hit(Pos(p.x + dx, p.y + dy), HitKind.BLAST)
    }

    fun colorWave(origin: Pos, color: Int) {
        val targets = board.positions.filter { board.matchColor(it) == color }
        effects += Effect.ColorWave(origin, targets)
        for (t in targets) hit(t, HitKind.BLAST)
    }

    fun mostCommonColor(): Int {
        val counts = HashMap<Int, Int>()
        for (p in board.positions) {
            val c = board.matchColor(p)
            if (c >= 0) counts[c] = (counts[c] ?: 0) + 1
        }
        return counts.entries.sortedWith(compareBy<Map.Entry<Int, Int>>({ -it.value }, { it.key })).firstOrNull()?.key ?: -1
    }

    fun plane(p: Pos, carried: Special) {
        for (n in p.neighbors4()) hit(n, HitKind.BLAST)
        val target = planeTarget(p)
        if (target != null) {
            effects += Effect.Flight(p, target)
            when (carried) {
                Special.ROCKET_H -> line(target, true)
                Special.ROCKET_V -> line(target, false)
                Special.BOMB -> diamond(target, 2)
                else -> hit(target, HitKind.BLAST)
            }
        }
    }

    /**
     * Chooses the most useful cell for a paper plane: an unfinished goal obstacle first,
     * then a piece of a colour still needed, otherwise any piece. Ties broken by RNG.
     */
    fun planeTarget(from: Pos): Pos? {
        val goals = goalsHint().filter { !it.done }
        fun score(p: Pos): Int {
            val c = board[p]
            if (c.void || p == from) return -1
            var s = 0
            for (g in goals) {
                when (g.goal.type) {
                    GoalType.CLEAR_ICE -> if (c.ice > 0 && c.blocker == null) s += 50
                    GoalType.BREAK_BOXES -> if (c.blocker?.type == BlockerType.BOX) s += 60
                    GoalType.BREAK_STONES -> if (c.blocker?.type == BlockerType.STONE) s += 60
                    GoalType.BREAK_CHAINS -> if (c.chain > 0) s += 55
                    GoalType.CLEAR_WEBS -> if (c.web) s += 55
                    GoalType.COLLECT_COLOR -> if (board.matchColor(p) == g.goal.color) s += 20
                    GoalType.DELIVER_DROPS -> {
                        // Clear the cell directly below a delivery item.
                        val above = Pos(p.x, p.y - 1)
                        if (board.pieceAt(above)?.kind == PieceKind.DROP) s += 45
                    }
                    else -> Unit
                }
            }
            if (s == 0 && c.piece != null && c.piece!!.kind != PieceKind.DROP) s = 1
            return s
        }
        val scored = board.positions.map { it to score(it) }.filter { it.second > 0 }
        if (scored.isEmpty()) return null
        val best = scored.maxOf { it.second }
        return rng.pick(scored.filter { it.second == best }.map { it.first })
    }

    // ---- Combos -------------------------------------------------------------------------

    /** Resolves swapping two specials (or a rainbow with anything). Both pieces are consumed. */
    fun combo(a: Pos, pa: Piece, b: Pos, pb: Piece) {
        activated += pa.id
        activated += pb.id
        events += GameEvent.Combo(b, pa.special, pb.special)
        // Remove both pieces first (they're consumed by the combo).
        consume(a)
        consume(b)
        val sa = pa.special
        val sb = pb.special
        when {
            sa == Special.RAINBOW && sb == Special.RAINBOW -> {
                effects += Effect.ColorWave(b, board.positions)
                for (p in board.positions) hit(p, HitKind.BLAST)
                // Second pass clears what was revealed under webs/chains on the first one.
                for (p in board.positions) hit(p, HitKind.BLAST)
            }
            sa == Special.RAINBOW || sb == Special.RAINBOW -> {
                val other = if (sa == Special.RAINBOW) pb else pa
                val origin = if (sa == Special.RAINBOW) a else b
                rainbowWith(origin, other)
            }
            sa.isRocket && sb.isRocket -> {
                line(b, true); line(b, false)
            }
            (sa.isRocket && sb == Special.BOMB) || (sb.isRocket && sa == Special.BOMB) -> {
                effects += Effect.Line(b, true, 3)
                effects += Effect.Line(b, false, 3)
                for (d in -1..1) {
                    for (x in 0 until board.width) hit(Pos(x, b.y + d), HitKind.BLAST)
                    for (y in 0 until board.height) hit(Pos(b.x + d, y), HitKind.BLAST)
                }
            }
            sa == Special.BOMB && sb == Special.BOMB -> square(b, 3)
            sa == Special.PLANE && sb == Special.PLANE -> {
                repeat(3) { plane(b, Special.NONE) }
            }
            sa == Special.PLANE || sb == Special.PLANE -> {
                val carried = if (sa == Special.PLANE) sb else sa
                plane(b, carried)
            }
        }
        drain()
    }

    private fun consume(p: Pos) {
        val cell = board[p]
        val piece = cell.piece ?: return
        cell.piece = null
        clearedPieces++
        touched += p
        events += GameEvent.PieceCleared(p, piece.color, piece.special)
        crackIce(p, cell)
    }

    private fun rainbowWith(origin: Pos, other: Piece) {
        val color = if (other.color >= 0) other.color else mostCommonColor()
        if (color < 0) return
        if (!other.isSpecial) {
            colorWave(origin, color)
            drain()
            return
        }
        // Convert every plain piece of that colour into the other special, then fire them all.
        val targets = board.positions.filter { board.matchColor(it) == color }
        effects += Effect.ColorWave(origin, targets)
        for (t in targets) {
            val cell = board[t]
            val piece = cell.piece ?: continue
            val newSpecial = when {
                other.special.isRocket -> if (rng.nextInt(2) == 0) Special.ROCKET_H else Special.ROCKET_V
                else -> other.special
            }
            val converted = if (piece.isSpecial) piece else piece.copy(special = newSpecial, color = -1)
            cell.piece = converted
        }
        for (t in targets) hit(t, HitKind.BLAST)
    }
}
