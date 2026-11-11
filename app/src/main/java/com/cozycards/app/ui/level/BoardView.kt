package com.cozycards.app.ui.level

import androidx.compose.animation.core.Animatable
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import com.cozycards.app.ui.gfx.Fx
import com.cozycards.app.ui.gfx.Sprites
import com.cozycards.app.ui.gfx.drawImageCentered
import com.cozycards.app.ui.gfx.drawImageIn
import com.cozycards.app.ui.theme.Palette
import com.cozycards.engine.Blocker
import com.cozycards.engine.BlockerType
import com.cozycards.engine.Board
import com.cozycards.engine.Cell
import com.cozycards.engine.Dir
import com.cozycards.engine.Effect
import com.cozycards.engine.GameEvent
import com.cozycards.engine.GoalProgress
import com.cozycards.engine.GoalType
import com.cozycards.engine.Piece
import com.cozycards.engine.PieceKind
import com.cozycards.engine.Portal
import com.cozycards.engine.Pos
import com.cozycards.engine.Special
import com.cozycards.engine.StepKind
import com.cozycards.game.economy.BoosterType
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** Identifies a goal counter in the HUD (type + colour for colour goals). */
data class GoalKey(val type: GoalType, val color: Int)

fun GoalProgress.key() = GoalKey(goal.type, if (goal.type == GoalType.COLLECT_COLOR) goal.color else -1)

/** Invalid swap feedback: the two pieces nudge towards each other and back. */
data class Wiggle(val a: Pos, val b: Pos, val id: Int)

/** Themed particle for each tile colour. */
private fun bitFor(color: Int) = when (color) {
    0 -> Sprites["p_petal"]
    1 -> Sprites["p_leaf"]
    4 -> Sprites["p_shard"]
    3 -> Sprites["p_coin"]
    else -> Sprites["sparkle"]
}

@Composable
fun BoardView(
    state: LevelUiState,
    portals: List<Portal>,
    conveyors: List<List<Pos>>,
    reducedMotion: Boolean,
    haptics: Boolean,
    fx: Fx,
    goalTargets: Map<GoalKey, Offset>,
    onGoalLaunch: (GoalKey) -> Unit,
    onGoalHit: (GoalKey) -> Unit,
    onSwap: (Pos, Pos) -> Unit,
    onTap: (Pos) -> Unit,
    onStepDone: (Int) -> Unit,
    modifier: Modifier = Modifier,
    wiggle: Wiggle? = null,
) {
    val anim = state.anim
    val clock = remember { Animatable(0f) }
    val wiggleT = remember { Animatable(1f) }
    val haptic = LocalHapticFeedback.current
    val stepDone by rememberUpdatedState(onStepDone)
    val targets by rememberUpdatedState(goalTargets)
    val launch by rememberUpdatedState(onGoalLaunch)
    val hit by rememberUpdatedState(onGoalHit)
    var origin by remember { mutableStateOf(Offset.Zero) }
    val plan = remember(anim?.id) { anim?.let { StepPlanner.plan(it.from, it.step, state.gravity, reducedMotion) } }
    val board = anim?.step?.board ?: state.board
    val w = board.width
    val h = board.height

    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val density = LocalDensity.current
        val cellPx = minOf(constraints.maxWidth.toFloat() / w, constraints.maxHeight.toFloat() / h)

        LaunchedEffect(anim?.id) {
            if (anim == null || plan == null) return@LaunchedEffect
            clock.snapTo(0f)
            scheduleFx(anim, plan, fx, origin, cellPx, state.goals, targets, launch, hit)
            if (haptics && (anim.step.effects.isNotEmpty() || anim.step.events.any { it is GameEvent.Combo })) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            }
            clock.animateTo(plan.totalMs, tween(plan.totalMs.toInt().coerceAtLeast(1), easing = LinearEasing))
            stepDone(anim.id)
        }
        LaunchedEffect(wiggle?.id) {
            if (wiggle == null) return@LaunchedEffect
            wiggleT.snapTo(0f)
            wiggleT.animateTo(1f, tween(Timing.INVALID_SWAP.toInt(), easing = LinearEasing))
        }

        val idle = rememberInfiniteTransition(label = "idle")
        val pulse by idle.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart), label = "pulse")

        val swap by rememberUpdatedState(onSwap)
        val tap by rememberUpdatedState(onTap)
        val boardForInput by rememberUpdatedState(board)
        Canvas(
            Modifier
                .size(with(density) { (cellPx * w).toDp() }, with(density) { (cellPx * h).toDp() })
                .clipToBounds()
                .onGloballyPositioned { origin = it.positionInRoot() }
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
                            if (maxOf(abs(acc.x), abs(acc.y)) > cellPx * 0.3f) {
                                val d = if (abs(acc.x) > abs(acc.y)) (if (acc.x > 0) Dir.RIGHT else Dir.LEFT) else (if (acc.y > 0) Dir.DOWN else Dir.UP)
                                fired = true
                                val t = s + d
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
            val t = clock.value
            val s = cellPx
            drawBackdrop(board, s)
            drawCells(board, s, conveyors)
            drawPortals(portals, s, pulse)
            val fromBoard = anim?.from ?: board
            if (anim == null || plan == null || anim.step.kind == StepKind.STAGE && (fromBoard.width != board.width || fromBoard.height != board.height)) {
                drawUnder(board, board, emptyMap(), 0f, s)
                if (plan == null) drawStaticPieces(board, s, pulse, wiggle, wiggleT.value) else drawPlannedPieces(plan, t, s, pulse)
                drawOver(board, board, emptyMap(), 0f, s)
            } else {
                drawUnder(fromBoard, board, plan.cellDelay, t, s)
                drawPlannedPieces(plan, t, s, pulse)
                drawOver(fromBoard, board, plan.cellDelay, t, s)
            }
            drawSelection(state, s, pulse)
        }
    }
}

// ---------------------------------------------------------------------- FX scheduling

private fun scheduleFx(
    anim: StepAnim, plan: StepPlan, fx: Fx, origin: Offset, s: Float,
    goals: List<GoalProgress>, targets: Map<GoalKey, Offset>,
    onLaunch: (GoalKey) -> Unit, onHit: (GoalKey) -> Unit,
) {
    val step = anim.step
    fun c(p: Pos) = origin + Offset((p.x + .5f) * s, (p.y + .5f) * s)
    fun c(x: Float, y: Float) = origin + Offset(x * s, y * s)
    val open = goals.filter { !it.done }.map { it.key() }.toSet()
    var flyers = 0
    fun flyToGoal(key: GoalKey, image: androidx.compose.ui.graphics.ImageBitmap?, from: Offset, at: Float) {
        val target = targets[key] ?: return
        if (key !in open || flyers >= 14) return
        flyers++
        onLaunch(key)
        fx.at(at.toDouble() + 40.0) { fx.fly(image, from, target, s * 0.8f, 0.5f + (flyers % 5) * 0.04f) { onHit(key) } }
    }

    for ((a, d) in plan.pops) {
        val piece = a.piece
        val pos = c(a.fromX, a.fromY)
        if (piece.kind == PieceKind.DROP) {
            flyToGoal(GoalKey(GoalType.DELIVER_DROPS, -1), Sprites["charm"], pos, d)
            fx.at(d.toDouble()) { fx.glow(pos.x, pos.y, s * 1.5f, Palette.Gold, 0.5f) }
            continue
        }
        val color = if (piece.color >= 0) TileStyle.color(piece.color) else Color.White
        fx.at(d.toDouble()) { fx.burst(pos.x, pos.y, s, color, bitFor(piece.color)) }
        if (piece.color >= 0) flyToGoal(GoalKey(GoalType.COLLECT_COLOR, piece.color), Sprites.tile(piece.color), pos, d)
    }
    // Obstacles.
    for ((p, d) in plan.cellDelay) {
        val a = anim.from[p]; val b = step.board[p]
        val pos = c(p)
        fx.at(d.toDouble()) {
            if (a.ice > b.ice) fx.debris(pos.x, pos.y, s, Sprites["p_shard"], null, 6, false)
            if (a.chain > b.chain) fx.debris(pos.x, pos.y, s, Sprites["p_shard"], Color(0xFFB8BCC8), 5, false)
            if (a.web && !b.web) fx.debris(pos.x, pos.y, s, Sprites["p_smoke"], null, 3, true)
            val ab = a.blocker
            if (ab != null && ab != b.blocker) when (ab.type) {
                BlockerType.BOX -> fx.debris(pos.x, pos.y, s, Sprites["p_splinter"], null, 8, true)
                BlockerType.STONE -> fx.debris(pos.x, pos.y, s, Sprites["p_shard"], Color(0xFF8E8F99), 8, true)
                BlockerType.LOCK -> { fx.glow(pos.x, pos.y, s * 2f, Palette.Gold, 0.5f); fx.burst(pos.x, pos.y, s, Palette.Gold, Sprites["sparkle"], 10) }
            }
        }
        if (a.ice > 0 && b.ice == 0) flyToGoal(GoalKey(GoalType.CLEAR_ICE, -1), Sprites["ice_1"], pos, d)
        if (a.chain > 0 && b.chain == 0) flyToGoal(GoalKey(GoalType.BREAK_CHAINS, -1), Sprites["chain_1"], pos, d)
        if (a.web && !b.web) flyToGoal(GoalKey(GoalType.CLEAR_WEBS, -1), Sprites["web"], pos, d)
        val ab = a.blocker
        if (ab != null && b.blocker == null) when (ab.type) {
            BlockerType.BOX -> flyToGoal(GoalKey(GoalType.BREAK_BOXES, -1), Sprites["crate_1"], pos, d)
            BlockerType.STONE -> flyToGoal(GoalKey(GoalType.BREAK_STONES, -1), Sprites["stone"], pos, d)
            BlockerType.LOCK -> flyToGoal(GoalKey(GoalType.OPEN_LOCKS, -1), Sprites["lock"], pos, d)
        }
    }
    // Specials.
    for (e in step.effects) when (e) {
        is Effect.Line -> fx.at(Timing.FLASH.toDouble()) {
            val p = c(e.pos)
            fx.glow(p.x, p.y, s * 2.2f, Color(0xFFFFD27F), 0.4f)
            fx.shake(s * 0.06f * e.width)
            val board = step.board
            if (e.horizontal) {
                val left = c(-1f, e.pos.y + .5f); val right = c(board.width + 1f, e.pos.y + .5f)
                fx.rocket(p, right, Sprites["rocket_h"], s, (board.width - e.pos.x + 1) * Timing.LINE_PER_CELL / 1000f)
                fx.rocket(p, left, Sprites["rocket_h"], s, (e.pos.x + 2) * Timing.LINE_PER_CELL / 1000f)
            } else {
                val top = c(e.pos.x + .5f, -1f); val bottom = c(e.pos.x + .5f, board.height + 1f)
                fx.rocket(p, top, Sprites["rocket_h"], s, (e.pos.y + 2) * Timing.LINE_PER_CELL / 1000f)
                fx.rocket(p, bottom, Sprites["rocket_h"], s, (board.height - e.pos.y + 1) * Timing.LINE_PER_CELL / 1000f)
            }
        }
        is Effect.Blast -> fx.at(Timing.FLASH.toDouble()) {
            val p = c(e.pos)
            fx.glow(p.x, p.y, s * (e.radius + 1.5f) * 1.6f, Color(0xFFFFE08A), 0.5f)
            fx.ring(p.x, p.y, s * (e.radius + 1f) * 1.4f, Color.White, 0.5f, s * 0.35f)
            fx.burst(p.x, p.y, s * 1.4f, Color(0xFFFFB04A), Sprites["sparkle"], 14)
            fx.shake(s * 0.1f * (e.radius + 1))
        }
        is Effect.ColorWave -> {
            val o = c(e.origin)
            fx.at(0.0) { fx.glow(o.x, o.y, s * 2.5f, Color(0xFFE6C8FF), 0.9f) }
            e.targets.take(40).forEachIndexed { i, tp ->
                fx.at((Timing.WAVE_START - 70f + i * Timing.WAVE_STEP).toDouble()) { fx.beam(o, c(tp), Color(0xFFD9C2FF), 0.3f) }
            }
            if (e.targets.size > 20) fx.at(Timing.WAVE_START.toDouble() + e.targets.size * Timing.WAVE_STEP) {
                fx.ring(o.x, o.y, s * 6f, Color.White, 0.6f, s * 0.5f); fx.shake(s * 0.2f)
            }
        }
        is Effect.Flight -> {
            val a = c(e.from); val b = c(e.to)
            fx.at(0.0) { fx.fly(Sprites["plane"], a, b, s * 0.9f, Timing.FLIGHT / 1000f, Color(0xFFFFC1D9)) {} }
            fx.at(Timing.FLIGHT.toDouble()) { fx.glow(b.x, b.y, s * 1.8f, Color(0xFFFFC1D9), 0.4f); fx.ring(b.x, b.y, s * 1.4f, Color.White, 0.35f, s * 0.2f) }
        }
        is Effect.Hammer -> fx.at((Timing.HAMMER - 50f).toDouble()) {
            val p = c(e.pos)
            fx.ring(p.x, p.y, s * 1.3f, Color.White, 0.35f, s * 0.25f); fx.glow(p.x, p.y, s * 1.6f, Color.White, 0.3f); fx.shake(s * 0.08f)
        }
    }
    for (ev in step.events) when (ev) {
        is GameEvent.SpecialCreated -> fx.at(Timing.CONVERGE.toDouble()) {
            val p = c(ev.pos)
            fx.glow(p.x, p.y, s * 1.8f, Color(0xFFFFF3C4), 0.45f)
            fx.ring(p.x, p.y, s * 1.1f, Color.White, 0.35f, s * 0.15f)
        }
        is GameEvent.Combo -> fx.at(0.0) {
            val p = c(ev.pos)
            fx.glow(p.x, p.y, s * 4f, Color(0xFFFFF3C4), 0.6f)
            fx.shake(s * 0.25f)
            fx.text("Combo!", p.x, p.y - s, Color(0xFFFFD27F))
        }
        else -> Unit
    }
    if (step.kind == StepKind.CLEAR && step.cascade >= 3) {
        val words = listOf("Nice!", "Great!", "Amazing!", "Spectacular!", "Legendary!")
        val word = words[(step.cascade - 3).coerceAtMost(words.lastIndex)]
        val mid = c(step.board.width / 2f, step.board.height / 2f)
        fx.at(Timing.FLASH.toDouble()) { fx.text(word, mid.x, mid.y, Color(0xFFFFE6F0)) }
    }
}

// ---------------------------------------------------------------------- drawing

private fun DrawScope.cellOrigin(p: Pos, s: Float) = Offset(p.x * s, p.y * s)

private fun DrawScope.drawBackdrop(board: Board, s: Float) {
    // A soft, slightly darker tray behind the playable cells separates the board from the scene.
    for (p in board.positions) {
        if (board[p].void) continue
        drawRoundRect(Color(0x66251A35), cellOrigin(p, s) - Offset(s * 0.06f, s * 0.06f), Size(s * 1.12f, s * 1.12f), CornerRadius(s * 0.22f))
    }
}

private fun DrawScope.drawCells(board: Board, s: Float, conveyors: List<List<Pos>>) {
    val belt = HashMap<Pos, Pos>()
    for (loop in conveyors) loop.forEachIndexed { i, p -> belt[p] = loop[(i + 1) % loop.size] }
    for (p in board.positions) {
        if (board[p].void) continue
        val o = cellOrigin(p, s) + Offset(s * 0.02f, s * 0.02f)
        val next = belt[p]
        if (next != null) {
            val angle = when { next.x > p.x -> 0f; next.x < p.x -> 180f; next.y > p.y -> 90f; else -> -90f }
            rotate(angle, o + Offset(s * 0.48f, s * 0.48f)) { drawImageIn(Sprites["conveyor"], o, Size(s * 0.96f, s * 0.96f), 0.9f) }
        } else {
            drawImageIn(Sprites[if ((p.x + p.y) % 2 == 0) "cell_light" else "cell_pink"], o, Size(s * 0.96f, s * 0.96f), 0.92f)
        }
    }
}

private fun DrawScope.drawPortals(portals: List<Portal>, s: Float, t: Float) {
    for (pr in portals) {
        val e = Offset((pr.entrance.x + .5f) * s, (pr.entrance.y + .9f) * s)
        val x = Offset((pr.exit.x + .5f) * s, (pr.exit.y + .1f) * s)
        rotate(t * 360f, e) { drawImageCentered(Sprites["portal"], e, s * 0.6f, 0.95f, null, false) }
        rotate(-t * 360f, x) { drawImageCentered(Sprites["portal"], x, s * 0.6f, 0.95f, null, false) }
    }
}

private fun DrawScope.drawIceSprite(o: Offset, s: Float, layers: Int, alpha: Float) {
    if (layers <= 0 || alpha <= 0.01f) return
    drawImageIn(Sprites[if (layers >= 2) "ice_2" else "ice_1"], o + Offset(s * 0.03f, s * 0.03f), Size(s * 0.94f, s * 0.94f), alpha * (if (layers >= 2) 0.85f else 0.7f))
}

private fun switchAlpha(t: Float, at: Float?) = if (at == null) 1f else ((t - at) / 90f).coerceIn(0f, 1f)

private fun DrawScope.drawUnder(from: Board, to: Board, delays: Map<Pos, Float>, t: Float, s: Float) {
    for (p in to.positions) {
        val b = to[p]
        if (b.void) continue
        val o = cellOrigin(p, s)
        val a = if (from.inBounds(p)) from[p] else b
        val k = switchAlpha(t, delays[p])
        if (a.ice == b.ice) drawIceSprite(o, s, b.ice, 1f) else { drawIceSprite(o, s, a.ice, 1f - k); drawIceSprite(o, s, b.ice, k) }
    }
}

private fun DrawScope.drawBlockerSprite(b: Blocker, o: Offset, s: Float, alpha: Float, scale: Float = 1f) {
    val name = when (b.type) {
        BlockerType.BOX -> "crate_${b.hp.coerceIn(1, 3)}"
        BlockerType.STONE -> "stone"
        BlockerType.LOCK -> "lock"
    }
    val c = o + Offset(s / 2, s / 2)
    scale(scale, c) { drawImageCentered(Sprites[name], c, s * 0.98f, alpha, if (b.type == BlockerType.STONE && b.hp >= 2) Color(0xFFB0B4C0) else null, false) }
}

private fun DrawScope.drawOver(from: Board, to: Board, delays: Map<Pos, Float>, t: Float, s: Float) {
    for (p in to.positions) {
        val b = to[p]
        if (b.void) continue
        val a = if (from.inBounds(p)) from[p] else b
        val o = cellOrigin(p, s)
        val at = delays[p]
        val k = switchAlpha(t, at)
        val c = o + Offset(s / 2, s / 2)
        fun chain(cell: Cell, alpha: Float) {
            if (cell.chain > 0) drawImageCentered(Sprites[if (cell.chain >= 2) "chain_2" else "chain_1"], c, s * 1.02f, alpha, null, false)
        }
        if (a.chain == b.chain) chain(b, 1f) else { chain(a, 1f - k); chain(b, k) }
        if (a.web || b.web) drawImageCentered(Sprites["web"], c, s, if (a.web && b.web) 0.92f else if (a.web) (1f - k) * 0.92f else k * 0.92f, null, false)
        val ab = a.blocker; val bb = b.blocker
        if (ab == bb) { if (bb != null) drawBlockerSprite(bb, o, s, 1f) }
        else {
            // Damage: the old state shakes and shrinks a little while the new one fades in.
            val hitWobble = if (at != null && t >= at && t < at + 120f) 1f + 0.08f * sin((t - at) / 120f * PI.toFloat()) else 1f
            if (ab != null) drawBlockerSprite(ab, o, s, 1f - k, hitWobble)
            if (bb != null) drawBlockerSprite(bb, o, s, k, hitWobble)
        }
    }
}

/** Draws one piece with optional squash/stretch and white flash. */
fun DrawScope.drawPieceSprite(piece: Piece, c: Offset, s: Float, alpha: Float = 1f, sx: Float = 1f, sy: Float = 1f, flash: Float = 0f, idle: Float = 0f) {
    if (alpha <= 0.01f || sx <= 0.01f || sy <= 0.01f) return
    val size = s * 0.9f
    scale(sx, sy, c) {
        when {
            piece.kind == PieceKind.DROP -> drawImageCentered(Sprites["charm"], c, size * 0.95f, alpha, null, false)
            piece.special == Special.RAINBOW -> {
                drawImageCentered(Sprites["glow"], c, size * 1.4f, alpha * (0.5f + 0.3f * sin(idle * 2 * PI.toFloat())), Color(0xFFE6C8FF), true)
                rotate(idle * 360f, c) { drawImageCentered(Sprites["rainbow"], c, size, alpha, null, false) }
            }
            piece.special != Special.NONE -> {
                val col = TileStyle.color(piece.color)
                val breathe = 1f + 0.05f * sin(idle * 2 * PI.toFloat())
                drawImageCentered(Sprites["glow"], c, size * 1.35f, alpha * 0.85f, col, false)
                val name = when (piece.special) {
                    Special.ROCKET_H -> "rocket_h"; Special.ROCKET_V -> "rocket_v"; Special.BOMB -> "bomb"; else -> "plane"
                }
                scale(breathe, c) { drawImageCentered(Sprites[name], c, size * 0.95f, alpha, null, false) }
                // Small colour badge so the special's match colour is always readable.
                drawImageCentered(Sprites.tile(piece.color), c + Offset(size * 0.3f, size * 0.3f), size * 0.38f, alpha, null, false)
            }
            else -> {
                drawImageCentered(Sprites.tile(piece.color), c, size, alpha, null, false)
                if (piece.kind == PieceKind.KEY) drawImageCentered(Sprites["key"], c + Offset(size * 0.26f, size * 0.26f), size * 0.5f, alpha, null, false)
            }
        }
        if (flash > 0.01f) drawImageCentered(Sprites["glow"], c, size * 1.2f, flash * alpha, Color.White, true)
    }
}

private fun DrawScope.drawStaticPieces(board: Board, s: Float, idle: Float, wiggle: Wiggle?, wt: Float) {
    for (p in board.positions) {
        val piece = board[p].piece ?: continue
        var c = Offset((p.x + .5f) * s, (p.y + .5f) * s)
        if (wiggle != null && wt < 1f && (p == wiggle.a || p == wiggle.b)) {
            val other = if (p == wiggle.a) wiggle.b else wiggle.a
            val k = sin(wt * PI.toFloat()) * 0.32f
            c += Offset((other.x - p.x) * s * k, (other.y - p.y) * s * k)
        }
        drawPieceSprite(piece, c, s, idle = idle)
    }
}

private fun DrawScope.drawPlannedPieces(plan: StepPlan, t: Float, s: Float, idle: Float) {
    for (a in plan.pieces) {
        val local = if (a.dur <= 0f) 1f else ((t - a.delay) / a.dur).coerceIn(0f, 1f)
        val fromC = Offset(a.fromX * s, a.fromY * s)
        val toC = Offset(a.toX * s, a.toY * s)
        when (a.motion) {
            Motion.STAY -> drawPieceSprite(a.piece, toC, s, idle = idle)
            Motion.SWAP -> drawPieceSprite(a.piece, fromC + (toC - fromC) * Ease.outBack(local), s, idle = idle)
            Motion.MOVE -> drawPieceSprite(a.piece, fromC + (toC - fromC) * Ease.inOut(local), s, idle = idle)
            Motion.FALL -> {
                val pos = fromC + (toC - fromC) * Ease.inQuad(local)
                val after = t - (a.delay + a.dur)
                var sx = 1f; var sy = 1f
                if (local < 1f && local > 0f) { sy = 1.06f; sx = 0.96f }
                if (after > 0f && after < Timing.SQUASH) {
                    val q = after / Timing.SQUASH
                    val squash = sin(q * PI.toFloat()) * (1f - q * 0.5f)
                    sy = 1f - 0.16f * squash; sx = 1f + 0.12f * squash
                }
                // Keep the squash anchored to the bottom of the cell.
                val anchor = pos + Offset(0f, s * 0.45f * (1f - sy))
                drawPieceSprite(a.piece, anchor, s, sx = sx, sy = sy, idle = idle)
            }
            Motion.POP -> {
                val pre = ((t - (a.delay - 70f)) / 70f).coerceIn(0f, 1f)
                if (t < a.delay) {
                    drawPieceSprite(a.piece, fromC, s, flash = pre * 0.8f, idle = idle)
                } else {
                    val q = local
                    val scale = if (q < 0.35f) 1f + 0.25f * (q / 0.35f) else 1.25f * (1f - (q - 0.35f) / 0.65f)
                    drawPieceSprite(a.piece, fromC, s, alpha = 1f - q * 0.3f, sx = scale, sy = scale, flash = 0.9f * (1f - q))
                }
            }
            Motion.CONVERGE -> if (local < 1f) {
                val e = Ease.inQuad(local)
                drawPieceSprite(a.piece, fromC + (toC - fromC) * e, s, sx = 1f - 0.35f * e, sy = 1f - 0.35f * e, flash = e * 0.7f)
            }
            Motion.APPEAR -> if (t >= a.delay) {
                val scale = if (local < 0.6f) 1.3f * (local / 0.6f) else 1.3f - 0.3f * ((local - 0.6f) / 0.4f)
                drawPieceSprite(a.piece, toC, s, sx = scale, sy = scale, flash = (1f - local) * 0.8f, idle = idle)
            }
        }
    }
}

private fun DrawScope.drawSelection(state: LevelUiState, s: Float, pulse: Float) {
    val glow = 0.5f + 0.5f * sin(pulse * 2 * PI.toFloat())
    fun frame(p: Pos, color: Color, width: Float) = drawRoundRect(color, cellOrigin(p, s) + Offset(s * 0.05f, s * 0.05f), Size(s * 0.9f, s * 0.9f), CornerRadius(s * 0.22f), style = Stroke(width))
    state.selected?.let { frame(it, Color.White, s * 0.07f) }
    state.gloveFirst?.let { frame(it, Palette.Lavender, s * 0.08f) }
    state.hint?.let { (a, b) ->
        if (state.anim == null && state.activeBooster == null) {
            for (p in listOf(a, b)) {
                val c = Offset((p.x + .5f) * s, (p.y + .5f) * s)
                drawImageCentered(Sprites["glow"], c, s * 1.3f, 0.25f + 0.35f * glow, Color.White, true)
            }
        }
    }
    if (state.activeBooster in setOf(BoosterType.HAMMER, BoosterType.SUPER_HAMMER, BoosterType.AREA_CLEAR, BoosterType.SWAP_GLOVE)) {
        drawRect(Palette.SakuraDeep.copy(alpha = 0.06f + 0.05f * glow))
    }
}
