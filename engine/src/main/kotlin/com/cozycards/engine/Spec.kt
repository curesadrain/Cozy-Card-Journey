package com.cozycards.engine

/** What a cell of a stage layout initially contains. */
data class CellSpec(
    val void: Boolean = false,
    val ice: Int = 0,
    val chain: Int = 0,
    val web: Boolean = false,
    val blocker: Blocker? = null,
    /** Optional pre-placed piece. */
    val initial: InitialPiece? = null,
)

/** Pre-placed piece; [color] = -1 means "pick a colour that doesn't form a match". */
data class InitialPiece(val kind: PieceKind = PieceKind.NORMAL, val special: Special = Special.NONE, val color: Int = -1)

enum class GoalType {
    COLLECT_COLOR, CLEAR_ICE, BREAK_BOXES, BREAK_CHAINS, CLEAR_WEBS, BREAK_STONES, DELIVER_DROPS, OPEN_LOCKS, SCORE
}

data class Goal(val type: GoalType, val target: Int, val color: Int = -1)

data class StageSpec(
    val width: Int,
    val height: Int,
    /** Row-major, size = width * height. */
    val cells: List<CellSpec>,
    val goals: List<Goal>,
    val gravity: Dir = Dir.DOWN,
    val portals: List<Portal> = emptyList(),
    /** Each conveyor is a closed loop of positions; pieces advance one step after every player move. */
    val conveyors: List<List<Pos>> = emptyList(),
    /** Gravity rotates through [gravityCycle] every [gravitySwitchEvery] moves (0 = never). */
    val gravityCycle: List<Dir> = emptyList(),
    val gravitySwitchEvery: Int = 0,
    /** Total delivery items spawned from the top during this stage (in addition to pre-placed ones). */
    val dropSpawns: Int = 0,
    /** Total key pieces spawned during this stage (in addition to pre-placed ones). */
    val keySpawns: Int = 0,
    /** Moves added when this stage starts (ignored for the first stage). */
    val bonusMoves: Int = 0,
) {
    fun cell(x: Int, y: Int) = cells[y * width + x]
}

data class GameSpec(
    val colors: Int,
    val moves: Int,
    val stages: List<StageSpec>,
) {
    init {
        require(colors in 3..6) { "colors must be 3..6" }
        require(stages.isNotEmpty()) { "at least one stage" }
    }
}
