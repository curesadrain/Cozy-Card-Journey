package com.cozycards.engine

/** Gameplay events emitted by the engine. Used for goals, scoring, sound and animation. */
sealed interface GameEvent {
    data class PieceCleared(val pos: Pos, val color: Int, val special: Special) : GameEvent
    data class IceCracked(val pos: Pos, val remaining: Int) : GameEvent
    data class BoxDamaged(val pos: Pos, val remaining: Int) : GameEvent
    data class StoneDamaged(val pos: Pos, val remaining: Int) : GameEvent
    data class ChainBroken(val pos: Pos, val remaining: Int) : GameEvent
    data class WebCleared(val pos: Pos) : GameEvent
    data class KeyCollected(val pos: Pos) : GameEvent
    data class LockOpened(val pos: Pos) : GameEvent
    data class DropDelivered(val pos: Pos) : GameEvent
    data class SpecialCreated(val pos: Pos, val special: Special) : GameEvent
    data class SpecialActivated(val pos: Pos, val special: Special) : GameEvent
    data class Combo(val pos: Pos, val a: Special, val b: Special) : GameEvent
    data object Shuffled : GameEvent
    data class StageCleared(val stageIndex: Int) : GameEvent
    data class GravityChanged(val dir: Dir) : GameEvent
}

/** Visual effect hints for the UI layer. Purely descriptive. */
sealed interface Effect {
    data class Line(val pos: Pos, val horizontal: Boolean, val width: Int = 1) : Effect
    data class Blast(val pos: Pos, val radius: Int) : Effect
    data class ColorWave(val origin: Pos, val targets: List<Pos>) : Effect
    data class Flight(val from: Pos, val to: Pos) : Effect
    data class Hammer(val pos: Pos) : Effect
}

enum class StepKind { SWAP, CLEAR, FALL, CONVEYOR, SHUFFLE, STAGE, GRAVITY, BOOSTER }

/**
 * One visual phase of a move. [board] is the board state *after* this step.
 * [cleared] are positions whose contents were removed/damaged in this step.
 */
class Step(
    val kind: StepKind,
    val board: Board,
    val cleared: Set<Pos> = emptySet(),
    val effects: List<Effect> = emptyList(),
    val events: List<GameEvent> = emptyList(),
    val cascade: Int = 0,
    val spawnedIds: Set<Int> = emptySet(),
)

sealed interface MoveResult {
    data class Invalid(val reason: String) : MoveResult
    data class Applied(val steps: List<Step>, val events: List<GameEvent>, val status: GameStatus) : MoveResult
}

enum class GameStatus { PLAYING, WON, LOST }

enum class LossReason { OUT_OF_MOVES, NO_POSSIBLE_MOVES }
