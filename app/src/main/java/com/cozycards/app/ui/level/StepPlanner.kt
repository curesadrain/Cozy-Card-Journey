package com.cozycards.app.ui.level

import com.cozycards.engine.Board
import com.cozycards.engine.Dir
import com.cozycards.engine.Effect
import com.cozycards.engine.GameEvent
import com.cozycards.engine.Piece
import com.cozycards.engine.Pos
import com.cozycards.engine.Special
import com.cozycards.engine.Step
import com.cozycards.engine.StepKind
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

enum class Motion { STAY, MOVE, SWAP, FALL, POP, CONVERGE, APPEAR }

/** How one piece is animated during a step. Positions are in cell units (0.5 = cell centre). */
class PieceAnim(
    val piece: Piece,
    val fromX: Float, val fromY: Float,
    val toX: Float, val toY: Float,
    val delay: Float,
    val dur: Float,
    val motion: Motion,
)

class StepPlan(
    val pieces: List<PieceAnim>,
    /** When the obstacles/ice of a cell switch from the old to the new state. */
    val cellDelay: Map<Pos, Float>,
    val totalMs: Float,
    /** Popped pieces with the time they pop, used to schedule particle bursts. */
    val pops: List<Pair<PieceAnim, Float>>,
)

object Timing {
    const val SWAP = 150f
    const val INVALID_SWAP = 260f
    const val FLASH = 70f
    const val POP = 170f
    const val CONVERGE = 140f
    const val APPEAR = 200f
    const val LINE_PER_CELL = 30f
    const val BLAST_PER_CELL = 45f
    const val WAVE_STEP = 22f
    const val WAVE_START = 120f
    const val FLIGHT = 380f
    const val HAMMER = 140f
    const val SQUASH = 110f
    const val COLUMN_STAGGER = 14f
}

object StepPlanner {

    fun plan(from: Board, step: Step, gravity: Dir, reduced: Boolean): StepPlan {
        val k = if (reduced) 0.45f else 1f
        val plan = when (step.kind) {
            StepKind.SWAP -> moves(from, step.board, Timing.SWAP, Motion.SWAP)
            StepKind.CLEAR, StepKind.BOOSTER -> clear(from, step)
            StepKind.FALL -> fall(from, step, gravity)
            StepKind.CONVEYOR -> moves(from, step.board, 260f, Motion.MOVE)
            StepKind.SHUFFLE -> moves(from, step.board, 520f, Motion.MOVE)
            StepKind.GRAVITY -> moves(from, step.board, 300f, Motion.MOVE)
            StepKind.STAGE -> StepPlan(step.board.positions.mapNotNull { p ->
                step.board[p].piece?.let { PieceAnim(it, p.x + .5f, p.y + .5f, p.x + .5f, p.y + .5f, (p.x + p.y) * 25f, Timing.APPEAR, Motion.APPEAR) }
            }, emptyMap(), 700f, emptyList())
        }
        if (k == 1f) return plan
        return StepPlan(
            plan.pieces.map { PieceAnim(it.piece, it.fromX, it.fromY, it.toX, it.toY, it.delay * k, it.dur * k, it.motion) },
            plan.cellDelay.mapValues { it.value * k }, plan.totalMs * k, plan.pops.map { it.first to it.second * k },
        )
    }

    private fun positions(b: Board): Map<Int, Pos> {
        val m = HashMap<Int, Pos>()
        for (p in b.positions) b[p].piece?.let { m[it.id] = p }
        return m
    }

    private fun moves(from: Board, to: Board, dur: Float, motion: Motion): StepPlan {
        val src = positions(from)
        val out = ArrayList<PieceAnim>()
        for (p in to.positions) {
            val piece = to[p].piece ?: continue
            val o = src[piece.id] ?: p
            out += PieceAnim(piece, o.x + .5f, o.y + .5f, p.x + .5f, p.y + .5f, 0f, dur, if (o == p) Motion.STAY else motion)
        }
        return StepPlan(out, emptyMap(), dur + 10f, emptyList())
    }

    /**
     * Matches flash, then pop. Specials spread their destruction as a wave: rockets sweep
     * along the line, bombs ripple outwards, rainbows strike targets one by one, planes
     * hit when they land. Pieces of a match that creates a special slide into it first.
     */
    private fun clear(from: Board, step: Step): StepPlan {
        val to = step.board
        val delay = HashMap<Pos, Float>()
        fun setDelay(p: Pos, d: Float) {
            val cur = delay[p]
            if (cur == null || d < cur) delay[p] = d
        }
        // Base: directly matched cells pop after a short flash.
        for (p in step.cleared) setDelay(p, Timing.FLASH)
        var activationOffset = 0f
        for (e in step.effects) when (e) {
            is Effect.Line -> for (p in from.positions) {
                val onLine = if (e.horizontal) abs(p.y - e.pos.y) <= e.width / 2 else abs(p.x - e.pos.x) <= e.width / 2
                if (onLine) setDelay(p, Timing.FLASH + activationOffset + (if (e.horizontal) abs(p.x - e.pos.x) else abs(p.y - e.pos.y)) * Timing.LINE_PER_CELL)
            }
            is Effect.Blast -> for (p in from.positions) {
                val d = abs(p.x - e.pos.x) + abs(p.y - e.pos.y)
                if (d <= e.radius * 2) setDelay(p, Timing.FLASH + activationOffset + d * Timing.BLAST_PER_CELL)
            }
            is Effect.ColorWave -> e.targets.forEachIndexed { i, p -> setDelay(p, Timing.WAVE_START + i * Timing.WAVE_STEP) }
            is Effect.Flight -> {
                setDelay(e.to, Timing.FLIGHT)
                for (n in e.to.neighbors4()) setDelay(n, Timing.FLIGHT + 40f)
            }
            is Effect.Hammer -> setDelay(e.pos, Timing.HAMMER)
        }
        // Waves of a colour-wave combo make the follow-up explosions start later.
        if (step.effects.any { it is Effect.ColorWave }) {
            val waveEnd = Timing.WAVE_START + (step.effects.filterIsInstance<Effect.ColorWave>().maxOf { it.targets.size }) * Timing.WAVE_STEP
            for (p in delay.keys.toList()) if ((delay[p] ?: 0f) < waveEnd && step.effects.none { it is Effect.ColorWave && p in it.targets }) {
                activationOffset = waveEnd
                delay[p] = (delay[p] ?: 0f) + waveEnd
            }
        }

        // Specials created in this step: matched neighbours of the same colour converge.
        val created = step.events.filterIsInstance<GameEvent.SpecialCreated>().map { it.pos }.toSet()
        val convergeTarget = HashMap<Pos, Pos>()
        for (c in created) {
            val color = to[c].piece?.color ?: continue
            val queue = ArrayDeque(listOf(c))
            val seen = hashSetOf(c)
            while (queue.isNotEmpty()) {
                val q = queue.removeFirst()
                for (n in q.neighbors4()) {
                    if (n in seen || n !in step.cleared) continue
                    if (from.pieceAt(n)?.color != color) continue
                    seen += n; queue += n
                    convergeTarget[n] = c
                }
            }
        }

        val src = positions(from)
        val dst = positions(to)
        val out = ArrayList<PieceAnim>()
        val pops = ArrayList<Pair<PieceAnim, Float>>()
        for ((id, p) in src) {
            val piece = from[p].piece!!
            val d = delay[p] ?: 0f
            val target = convergeTarget[p]
            when {
                dst.containsKey(id) && to[dst[id]!!].piece == piece -> out += PieceAnim(piece, p.x + .5f, p.y + .5f, p.x + .5f, p.y + .5f, 0f, 0f, Motion.STAY)
                dst.containsKey(id) -> {
                    // Same piece transformed in place (e.g. converted by a rainbow combo).
                    val np = to[dst[id]!!].piece!!
                    out += PieceAnim(np, p.x + .5f, p.y + .5f, p.x + .5f, p.y + .5f, d * 0.6f, Timing.APPEAR, Motion.APPEAR)
                }
                target != null && piece.special == Special.NONE -> out += PieceAnim(piece, p.x + .5f, p.y + .5f, target.x + .5f, target.y + .5f, 0f, Timing.CONVERGE, Motion.CONVERGE)
                p in created -> out += PieceAnim(piece, p.x + .5f, p.y + .5f, p.x + .5f, p.y + .5f, 0f, Timing.CONVERGE, Motion.CONVERGE)
                else -> {
                    val a = PieceAnim(piece, p.x + .5f, p.y + .5f, p.x + .5f, p.y + .5f, d, Timing.POP, Motion.POP)
                    out += a
                    pops += a to d
                }
            }
        }
        // New pieces (fresh specials) appear where the match converged.
        for ((id, p) in dst) if (!src.containsKey(id)) {
            out += PieceAnim(to[p].piece!!, p.x + .5f, p.y + .5f, p.x + .5f, p.y + .5f, Timing.CONVERGE, Timing.APPEAR, Motion.APPEAR)
        }
        // Obstacles change when the wave reaches them.
        val cellDelay = HashMap<Pos, Float>()
        for (p in to.positions) {
            val a = from[p]; val b = to[p]
            if (a.ice != b.ice || a.chain != b.chain || a.web != b.web || a.blocker != b.blocker) cellDelay[p] = delay[p] ?: Timing.FLASH
        }
        val end = max(
            (out.maxOfOrNull { it.delay + it.dur } ?: 0f),
            (cellDelay.values.maxOrNull() ?: 0f) + 60f,
        )
        return StepPlan(out, cellDelay, end + 20f, pops)
    }

    /**
     * Gravity: falling time grows with the square root of the distance (constant acceleration),
     * columns start slightly staggered, and every landing ends in a squash-and-stretch bounce.
     */
    private fun fall(from: Board, step: Step, gravity: Dir): StepPlan {
        val to = step.board
        val src = positions(from)
        val out = ArrayList<PieceAnim>()
        // Spawned pieces enter from beyond the edge, stacked by arrival order.
        val spawnedByLine = HashMap<Int, MutableList<Pair<Pos, Piece>>>()
        for (p in to.positions) {
            val piece = to[p].piece ?: continue
            if (piece.id in step.spawnedIds) spawnedByLine.getOrPut(if (gravity.dx == 0) p.x else p.y) { mutableListOf() } += p to piece
        }
        val spawnStart = HashMap<Int, Pair<Float, Float>>()
        for ((_, list) in spawnedByLine) {
            val sorted = list.sortedBy { (p, _) -> -(p.x * gravity.dx + p.y * gravity.dy) }
            sorted.forEachIndexed { i, (p, piece) ->
                val rank = i + 1
                val edgeDist = when (gravity) {
                    Dir.DOWN -> p.y; Dir.UP -> to.height - 1 - p.y; Dir.RIGHT -> p.x; Dir.LEFT -> to.width - 1 - p.x
                }
                val back = rank + edgeDist
                spawnStart[piece.id] = (p.x + .5f - gravity.dx * back) to (p.y + .5f - gravity.dy * back)
            }
        }
        var end = 0f
        for (p in to.positions) {
            val piece = to[p].piece ?: continue
            val (fx, fy) = src[piece.id]?.let { (it.x + .5f) to (it.y + .5f) } ?: spawnStart[piece.id] ?: ((p.x + .5f) to (p.y + .5f))
            val dist = abs(fx - (p.x + .5f)) + abs(fy - (p.y + .5f))
            if (dist < 0.01f) {
                out += PieceAnim(piece, fx, fy, p.x + .5f, p.y + .5f, 0f, 0f, Motion.STAY)
                continue
            }
            val lane = if (gravity.dx == 0) p.x else p.y
            val delay = lane * Timing.COLUMN_STAGGER
            val dur = 95f + 70f * sqrt(dist)
            out += PieceAnim(piece, fx, fy, p.x + .5f, p.y + .5f, delay, dur, Motion.FALL)
            end = max(end, delay + dur + Timing.SQUASH)
        }
        return StepPlan(out, emptyMap(), end, emptyList())
    }
}

object Ease {
    fun outBack(t: Float): Float {
        val c1 = 1.70158f; val c3 = c1 + 1
        val u = t - 1
        return 1 + c3 * u * u * u + c1 * u * u
    }
    fun inOut(t: Float) = if (t < 0.5f) 2 * t * t else 1 - (-2 * t + 2).let { it * it } / 2
    fun inQuad(t: Float) = t * t
}
