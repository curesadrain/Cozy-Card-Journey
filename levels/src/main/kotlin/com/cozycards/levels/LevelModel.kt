package com.cozycards.levels

import com.cozycards.engine.Dir
import com.cozycards.engine.GameSpec
import com.cozycards.engine.Goal
import com.cozycards.engine.GoalType
import com.cozycards.engine.Portal
import com.cozycards.engine.Pos
import com.cozycards.engine.StageSpec
import kotlinx.serialization.Serializable

/** Lootbox tiers. Lives here because level rewards reference it. */
@Serializable
enum class LootboxTier(val cardCount: Int) {
    COMMON(3), RARE(5), EPIC(7), LEGENDARY(10)
}

@Serializable
data class PosDef(val x: Int, val y: Int) {
    fun toPos() = Pos(x, y)
}

@Serializable
data class PortalDef(val entrance: PosDef, val exit: PosDef)

@Serializable
data class GoalDef(val type: GoalType, val target: Int, val color: Int = -1) {
    fun toGoal() = Goal(type, target, color)
}

/**
 * One board of a level. [rows] use the layout token format described in [LayoutCodec].
 */
@Serializable
data class StageDef(
    val rows: List<String>,
    val goals: List<GoalDef>,
    val gravity: Dir = Dir.DOWN,
    val portals: List<PortalDef> = emptyList(),
    val conveyors: List<List<PosDef>> = emptyList(),
    val gravityCycle: List<Dir> = emptyList(),
    val gravitySwitchEvery: Int = 0,
    val dropSpawns: Int = 0,
    val keySpawns: Int = 0,
    val bonusMoves: Int = 0,
)

@Serializable
data class LevelReward(val coins: Int, val lootbox: LootboxTier)

/** Measured by automated simulation when the level was generated. */
@Serializable
data class LevelMetrics(
    /** Share of simulated greedy-bot runs that won within the move limit. */
    val botWinRate: Double,
    /** Median moves the bot needed when given unlimited moves. */
    val botMedianMoves: Int,
    val simulatedRuns: Int,
)

@Serializable
data class LevelDefinition(
    val id: String,
    /** Global level number, 1..1000. */
    val number: Int,
    val chapter: Int,
    val indexInChapter: Int,
    val colors: Int,
    val moves: Int,
    /** Designed difficulty 0..1. */
    val difficulty: Double,
    val tier: DifficultyTier,
    val stages: List<StageDef>,
    val reward: LevelReward,
    /** Name of the authored design template the level was built from. */
    val template: String,
    val metrics: LevelMetrics? = null,
) {
    fun toGameSpec(): GameSpec = GameSpec(colors, moves, stages.map { it.toStageSpec() })
}

@Serializable
enum class DifficultyTier { EASY, NORMAL, HARD, SUPER_HARD }

fun StageDef.toStageSpec(): StageSpec {
    val layout = LayoutCodec.decode(rows)
    return StageSpec(
        width = layout.width,
        height = layout.height,
        cells = layout.cells,
        goals = goals.map { it.toGoal() },
        gravity = gravity,
        portals = portals.map { Portal(it.entrance.toPos(), it.exit.toPos()) },
        conveyors = conveyors.map { loop -> loop.map { it.toPos() } },
        gravityCycle = gravityCycle,
        gravitySwitchEvery = gravitySwitchEvery,
        dropSpawns = dropSpawns,
        keySpawns = keySpawns,
        bonusMoves = bonusMoves,
    )
}

@Serializable
data class ChapterFile(val chapter: Int, val levels: List<LevelDefinition>)
