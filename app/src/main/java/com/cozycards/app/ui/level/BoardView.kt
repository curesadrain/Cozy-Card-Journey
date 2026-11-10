package com.cozycards.app.ui.level

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import com.cozycards.app.ui.theme.Palette
import com.cozycards.engine.Board
import com.cozycards.engine.Dir
import com.cozycards.engine.Effect
import com.cozycards.engine.Piece
import com.cozycards.engine.Pos
import com.cozycards.engine.Portal
import com.cozycards.engine.StepKind
import com.cozycards.game.economy.BoosterType
import kotlin.math.abs

private fun durationFor(kind: StepKind, reduced: Boolean): Int {
    val base = when (kind) {
        StepKind.SWAP -> 170
        StepKind.CLEAR, StepKind.BOOSTER -> 260
        StepKind.FALL -> 240
        StepKind.CONVEYOR -> 260
        StepKind.SHUFFLE -> 450
        StepKind.GRAVITY -> 300
        StepKind.STAGE -> 600
    }
    return if (reduced) base / 3 else base
}

/**
 * Renders the board and plays [StepAnim]s by interpolating piece positions between two board
 * snapshots (pieces are matched by id). Calls [onStepDone] when an animation completes.
 */
@Composable
fun BoardView(
    state: LevelUiState,
    portals: List<Portal>,
    conveyors: List<List<Pos>>,
    reducedMotion: Boolean,
    haptics: Boolean,
    onSwap: (Pos, Pos) -> Unit,
    onTap: (Pos) -> Unit,
    onStepDone: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val anim = state.anim
    val progress = remember { Animatable(1f) }
    val haptic = LocalHapticFeedback.current
    val stepDone by rememberUpdatedState(onStepDone)
    LaunchedEffect(anim?.id) {
        if (anim == null) return@LaunchedEffect
        progress.snapTo(0f)
        if (haptics && (anim.step.effects.any { it !is Effect.Hammer } || anim.step.kind == StepKind.STAGE)) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
        progress.animateTo(1f, tween(durationFor(anim.step.kind, reducedMotion), easing = if (anim.step.kind == StepKind.FALL) LinearEasing else FastOutSlowInEasing))
        stepDone(anim.id)
    }
    val pulse = rememberInfiniteTransition(label = "pulse")
    val pulseT by pulse.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "p")

    val board = anim?.step?.board ?: state.board
    val w = board.width
    val h = board.height
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val density = LocalDensity.current
        val cellPx = minOf(constraints.maxWidth.toFloat() / w, constraints.maxHeight.toFloat() / h)
        val widthDp = with(density) { (cellPx * w).toDp() }
        val heightDp = with(density) { (cellPx * h).toDp() }
        val swap by rememberUpdatedState(onSwap)
        val tap by rememberUpdatedState(onTap)
        val boardForInput by rememberUpdatedState(board)
        Canvas(
            Modifier
                .size(widthDp, heightDp)
                .pointerInput(cellPx) {
                    var start: Pos? = null
                    var acc = Offset.Zero
                    var fired = false
                    detectDragGestures(
                        onDragStart = { o -> start = Pos((o.x / cellPx).toInt(), (o.y / cellPx).toInt()); acc = Offset.Zero; fired = false },
                        onDragEnd = { start = null },
                        onDragCancel = { start = null },
                        onDrag = { change, delta ->
                            change.consume()
                            val s = start ?: return@detectDragGestures
                            if (fired) return@detectDragGestures
                            acc += delta
                            if (maxOf(abs(acc.x), abs(acc.y)) > cellPx * 0.35f) {
                                val d = if (abs(acc.x) > abs(acc.y)) (if (acc.x > 0) Dir.RIGHT else Dir.LEFT) else (if (acc.y > 0) Dir.DOWN else Dir.UP)
                                val t = s + d
                                fired = true
                                if (boardForInput.inBounds(s) && boardForInput.inBounds(t)) swap(s, t)
                            }
                        },
                    )
                }
                .pointerInput(cellPx) {
                    detectTapGestures { o ->
                        val p = Pos((o.x / cellPx).toInt(), (o.y / cellPx).toInt())
                        if (boardForInput.inBounds(p)) tap(p)
                    }
                },
        ) {
            val t = if (anim == null) 1f else progress.value
            drawBoardBase(board, cellPx, conveyors)
            drawPortals(portals, cellPx, pulseT)
            if (anim == null) {
                drawStatic(board, cellPx)
            } else {
                drawTransition(anim.from, anim.step.board, anim.step.kind, anim.step.spawnedIds, t, cellPx, state.gravity)
                drawEffects(anim.step.effects, t, cellPx)
            }
            drawSelection(state, cellPx, pulseT)
        }
    }
}

private fun DrawScope.cellOrigin(p: Pos, s: Float) = Offset(p.x * s, p.y * s)
private fun DrawScope.cellCenter(p: Pos, s: Float) = Offset((p.x + 0.5f) * s, (p.y + 0.5f) * s)

private fun DrawScope.drawBoardBase(board: Board, s: Float, conveyors: List<List<Pos>>) {
    for (p in board.positions) {
        val c = board[p]
        if (c.void) continue
        val shade = if ((p.x + p.y) % 2 == 0) Color(0xE6FFF8F0) else Color(0xE6F7E8EE)
        drawRoundRect(shade, cellOrigin(p, s) + Offset(s * 0.02f, s * 0.02f), Size(s * 0.96f, s * 0.96f), CornerRadius(s * 0.18f))
    }
    for (loop in conveyors) for ((i, p) in loop.withIndex()) {
        val n = loop[(i + 1) % loop.size]
        drawRoundRect(Color(0x335B5068), cellOrigin(p, s) + Offset(s * 0.06f, s * 0.06f), Size(s * 0.88f, s * 0.88f), CornerRadius(s * 0.14f))
        drawConveyorArrow(cellCenter(p, s), s, n.x - p.x, n.y - p.y)
    }
}

private fun DrawScope.drawPortals(portals: List<Portal>, s: Float, t: Float) {
    val colors = listOf(Color(0xFF7FC8C2), Color(0xFFB39DDB), Color(0xFFFF9F59))
    portals.forEachIndexed { i, pr ->
        val col = colors[i % colors.size]
        drawPortal(cellCenter(pr.entrance, s) + Offset(0f, s * 0.45f), s, col, t)
        drawPortal(cellCenter(pr.exit, s) - Offset(0f, s * 0.45f), s, col, -t)
    }
}

private fun DrawScope.drawStatic(board: Board, s: Float) {
    for (p in board.positions) {
        val c = board[p]
        if (c.void) continue
        drawIce(cellOrigin(p, s), s, c.ice)
    }
    for (p in board.positions) {
        val c = board[p]
        c.piece?.let { drawPiece(it, cellCenter(p, s), s) }
    }
    for (p in board.positions) {
        val c = board[p]
        if (c.void) continue
        drawChain(cellOrigin(p, s), s, c.chain)
        if (c.web) drawWeb(cellOrigin(p, s), s)
        c.blocker?.let { drawBlocker(it, cellOrigin(p, s), s) }
    }
}

private fun gravityOffset(g: Dir) = Offset(-g.dx.toFloat(), -g.dy.toFloat())

private fun DrawScope.drawTransition(from: Board, to: Board, kind: StepKind, spawned: Set<Int>, t: Float, s: Float, gravity: Dir) {
    // A new stage can have a different board size: no per-cell interpolation is possible.
    if (kind == StepKind.STAGE || from.width != to.width || from.height != to.height) {
        drawStatic(to, s)
        drawRect(Color.White.copy(alpha = (1f - t) * 0.8f))
        return
    }
    val fromPos = HashMap<Int, Pos>()
    for (p in from.positions) from[p].piece?.let { fromPos[it.id] = p }
    val toPieces = HashMap<Int, Pair<Pos, Piece>>()
    for (p in to.positions) to[p].piece?.let { toPieces[it.id] = p to it }

    // Under-layer: ice crossfades from old to new layer count.
    for (p in to.positions) {
        val a = from[p]; val b = to[p]
        if (b.void) continue
        if (a.ice == b.ice) drawIce(cellOrigin(p, s), s, b.ice)
        else { drawIce(cellOrigin(p, s), s, a.ice, 1f - t); drawIce(cellOrigin(p, s), s, b.ice, t) }
    }

    // Spawned pieces enter from beyond the board edge, stacked by their order in the column.
    val spawnRank = HashMap<Int, Int>()
    if (kind == StepKind.FALL) {
        val byLine = spawned.mapNotNull { id -> toPieces[id] }.groupBy { (pos, _) -> if (gravity.dx == 0) pos.x else pos.y }
        for ((_, list) in byLine) {
            val sorted = list.sortedBy { (pos, _) -> -(pos.x * gravity.dx + pos.y * gravity.dy) }
            sorted.forEachIndexed { i, (_, piece) -> spawnRank[piece.id] = i + 1 }
        }
    }

    for ((id, pair) in toPieces) {
        val (pos, piece) = pair
        val target = cellCenter(pos, s)
        val origin = fromPos[id]
        val center: Offset
        var alpha = 1f
        var scale = 1f
        if (origin != null) {
            val o = cellCenter(origin, s)
            center = o + (target - o) * t
            val old = from[origin].piece
            if (old != null && old.special != piece.special && kind != StepKind.FALL) {
                // Newly created special: grow in.
                scale = 0.7f + 0.3f * t + 0.25f * kotlin.math.sin(t * Math.PI).toFloat()
            }
        } else {
            val rank = spawnRank[id]
            if (rank != null) {
                val back = gravityOffset(gravity)
                val distanceCells = rank + (if (gravity.dx == 0) (if (gravity.dy > 0) pos.y else to.height - 1 - pos.y) else (if (gravity.dx > 0) pos.x else to.width - 1 - pos.x))
                val start = target + back * (distanceCells * s)
                center = start + (target - start) * t
            } else {
                center = target
                alpha = t
                scale = 0.6f + 0.4f * t
            }
        }
        drawPiece(piece, center, s, alpha, scale)
    }
    // Pieces that disappeared: shrink and fade, with a sparkle burst.
    for ((id, pos) in fromPos) {
        if (toPieces.containsKey(id)) continue
        val piece = from[pos].piece ?: continue
        val c = cellCenter(pos, s)
        drawPiece(piece, c, s, 1f - t, 1f + 0.25f * t - 0.9f * t * t)
        val burst = TileStyle.color(piece.color.coerceAtLeast(0))
        for (k in 0 until 6) {
            val a = Math.PI / 3 * k
            val d = s * 0.5f * t
            drawCircle(burst.copy(alpha = (1f - t) * 0.9f), s * 0.06f * (1f - t), c + Offset((kotlin.math.cos(a) * d).toFloat(), (kotlin.math.sin(a) * d).toFloat()))
        }
    }
    // Over-layer obstacles crossfade.
    for (p in to.positions) {
        val a = from[p]; val b = to[p]
        if (b.void) continue
        val o = cellOrigin(p, s)
        if (a.chain == b.chain) drawChain(o, s, b.chain) else { drawChain(o, s, a.chain, 1f - t); drawChain(o, s, b.chain, t) }
        if (a.web && b.web) drawWeb(o, s) else if (a.web) drawWeb(o, s, 1f - t) else if (b.web) drawWeb(o, s, t)
        val ab = a.blocker; val bb = b.blocker
        if (ab == bb) { if (bb != null) drawBlocker(bb, o, s) }
        else {
            if (ab != null) drawBlocker(ab, o, s, 1f - t)
            if (bb != null) drawBlocker(bb, o, s, t)
        }
    }
}

private fun DrawScope.drawEffects(effects: List<Effect>, t: Float, s: Float) {
    val fade = (1f - t)
    for (e in effects) when (e) {
        is Effect.Line -> {
            val c = cellCenter(e.pos, s)
            val thick = s * 0.5f * e.width
            if (e.horizontal) {
                drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.85f * fade), Color.Transparent), c.x - size.width * t, c.x + size.width * t),
                    Offset(0f, c.y - thick / 2), Size(size.width, thick))
            } else {
                drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.85f * fade), Color.Transparent), c.y - size.height * t, c.y + size.height * t),
                    Offset(c.x - thick / 2, 0f), Size(thick, size.height))
            }
        }
        is Effect.Blast -> {
            val c = cellCenter(e.pos, s)
            val r = s * (e.radius + 0.5f) * (0.4f + 0.8f * t)
            drawCircle(Brush.radialGradient(listOf(Color(0xFFFFF3C4).copy(alpha = 0.9f * fade), Palette.Lantern.copy(alpha = 0.4f * fade), Color.Transparent), c, r), r, c)
        }
        is Effect.ColorWave -> {
            val o = cellCenter(e.origin, s)
            for (target in e.targets) {
                val c = cellCenter(target, s)
                drawLine(Color.White.copy(alpha = 0.7f * fade), o, o + (c - o) * minOf(1f, t * 1.6f), s * 0.05f)
            }
            drawCircle(Color.White.copy(alpha = 0.5f * fade), s * (0.5f + t * 2f), o, style = Stroke(s * 0.08f))
        }
        is Effect.Flight -> {
            val a = cellCenter(e.from, s); val b = cellCenter(e.to, s)
            val mid = (a + b) / 2f - Offset(0f, s * 1.5f)
            val q = (1 - t) * (1 - t)
            val p = a * q + mid * (2 * (1 - t) * t) + b * (t * t)
            drawPlane(p, s * 0.32f, Color.White, Palette.Ink)
        }
        is Effect.Hammer -> {
            val c = cellCenter(e.pos, s)
            drawCircle(Color.White.copy(alpha = 0.7f * fade), s * (0.3f + 0.7f * t), c, style = Stroke(s * 0.1f))
        }
    }
}

private fun DrawScope.drawSelection(state: LevelUiState, s: Float, pulse: Float) {
    val glow = 0.5f + 0.5f * kotlin.math.sin(pulse * 2 * Math.PI).toFloat()
    state.selected?.let { drawRoundRect(Color.White.copy(alpha = 0.9f), cellOrigin(it, s) + Offset(s * 0.04f, s * 0.04f), Size(s * 0.92f, s * 0.92f), CornerRadius(s * 0.2f), style = Stroke(s * 0.07f)) }
    state.gloveFirst?.let { drawRoundRect(Palette.Lavender, cellOrigin(it, s) + Offset(s * 0.04f, s * 0.04f), Size(s * 0.92f, s * 0.92f), CornerRadius(s * 0.2f), style = Stroke(s * 0.08f)) }
    state.hint?.let { (a, b) ->
        if (state.anim == null && state.activeBooster == null) {
            for (p in listOf(a, b)) drawRoundRect(Color.White.copy(alpha = 0.35f + 0.5f * glow), cellOrigin(p, s) + Offset(s * 0.06f, s * 0.06f), Size(s * 0.88f, s * 0.88f), CornerRadius(s * 0.2f), style = Stroke(s * 0.05f))
        }
    }
    if (state.activeBooster in setOf(BoosterType.HAMMER, BoosterType.SUPER_HAMMER, BoosterType.AREA_CLEAR, BoosterType.SWAP_GLOVE)) {
        drawRect(Palette.SakuraDeep.copy(alpha = 0.08f + 0.06f * glow))
    }
}
