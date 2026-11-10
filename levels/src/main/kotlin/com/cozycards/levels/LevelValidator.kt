package com.cozycards.levels

import com.cozycards.engine.Board
import com.cozycards.engine.BlockerType
import com.cozycards.engine.CellSpec
import com.cozycards.engine.Dir
import com.cozycards.engine.GameStatus
import com.cozycards.engine.GoalType
import com.cozycards.engine.Match3Game
import com.cozycards.engine.MatchFinder
import com.cozycards.engine.PieceKind
import com.cozycards.engine.Pos
import com.cozycards.engine.StageSpec
import com.cozycards.engine.Topology

/**
 * Static and dynamic checks for level definitions. Any level that fails must never ship:
 * the unit test suite runs this validator over every bundled level.
 */
class LevelValidator(private val bot: BotPlayer = BotPlayer()) {

    data class Issue(val levelId: String, val message: String) {
        override fun toString() = "$levelId: $message"
    }

    companion object {
        const val MIN_SIZE = 5
        const val MAX_WIDTH = 9
        const val MAX_HEIGHT = 10
        const val MIN_PLAYABLE_CELLS = 25
        const val MIN_MOVES = 5
        const val MAX_MOVES = 90
    }

    fun validateStructure(def: LevelDefinition): List<Issue> {
        val issues = mutableListOf<Issue>()
        fun fail(msg: String) { issues += Issue(def.id, msg) }

        if (def.id != Chapters.levelId(def.number)) fail("id ${def.id} does not match number ${def.number}")
        if (def.number !in 1..Chapters.TOTAL_LEVELS) fail("number out of range")
        if (def.chapter != (def.number - 1) / Chapters.LEVELS_PER_CHAPTER + 1) fail("wrong chapter")
        if (def.indexInChapter != (def.number - 1) % Chapters.LEVELS_PER_CHAPTER + 1) fail("wrong index in chapter")
        if (def.colors !in 3..6) fail("colors ${def.colors} not in 3..6")
        if (def.moves !in MIN_MOVES..MAX_MOVES) fail("moves ${def.moves} not in $MIN_MOVES..$MAX_MOVES")
        if (def.difficulty !in 0.0..1.0) fail("difficulty out of range")
        if (def.stages.isEmpty()) fail("no stages")
        if (def.reward.coins <= 0) fail("reward coins must be positive")
        if (def.reward.lootbox != LevelRewards.lootbox(def.number)) fail("lootbox tier does not follow reward rules")

        def.stages.forEachIndexed { si, st -> validateStage(def, si, st, ::fail) }
        return issues
    }

    private fun validateStage(def: LevelDefinition, si: Int, st: StageDef, fail: (String) -> Unit) {
        val prefix = "stage $si:"
        val spec: StageSpec = try {
            st.toStageSpec()
        } catch (e: IllegalArgumentException) {
            fail("$prefix layout error: ${e.message}")
            return
        }
        if (spec.width !in MIN_SIZE..MAX_WIDTH) fail("$prefix width ${spec.width} out of range")
        if (spec.height !in MIN_SIZE..MAX_HEIGHT) fail("$prefix height ${spec.height} out of range")
        val playable = spec.cells.count { !it.void }
        if (playable < MIN_PLAYABLE_CELLS) fail("$prefix only $playable playable cells")
        if (st.goals.isEmpty()) fail("$prefix no goals")
        if (st.goals.size > 3) fail("$prefix more than 3 goals")
        if (st.dropSpawns < 0 || st.keySpawns < 0) fail("$prefix negative spawn counts")
        if (si > 0 && st.bonusMoves !in 0..20) fail("$prefix bonus moves out of range")

        fun count(pred: (CellSpec) -> Boolean) = spec.cells.count(pred)
        for (gd in st.goals) {
            if (gd.target <= 0) fail("$prefix goal ${gd.type} has non-positive target")
            val available = when (gd.type) {
                GoalType.COLLECT_COLOR -> {
                    if (gd.color !in 0 until def.colors) fail("$prefix colour goal uses colour ${gd.color} but level has ${def.colors}")
                    Int.MAX_VALUE
                }
                GoalType.CLEAR_ICE -> count { it.ice > 0 }
                GoalType.BREAK_BOXES -> count { it.blocker?.type == BlockerType.BOX }
                GoalType.BREAK_STONES -> count { it.blocker?.type == BlockerType.STONE }
                GoalType.BREAK_CHAINS -> count { it.chain > 0 }
                GoalType.CLEAR_WEBS -> count { it.web }
                GoalType.OPEN_LOCKS -> {
                    val locks = count { it.blocker?.type == BlockerType.LOCK }
                    val keys = count { it.initial?.kind == PieceKind.KEY } + st.keySpawns
                    if (keys < gd.target) fail("$prefix needs ${gd.target} keys but only $keys available")
                    locks
                }
                GoalType.DELIVER_DROPS -> count { it.initial?.kind == PieceKind.DROP } + st.dropSpawns
                GoalType.SCORE -> Int.MAX_VALUE
            }
            if (gd.target > available) fail("$prefix goal ${gd.type} target ${gd.target} exceeds available $available")
        }
        if (st.goals.map { it.type to it.color }.toSet().size != st.goals.size) fail("$prefix duplicate goals")
        if (count { it.ice > 0 && it.blocker != null } > 0) fail("$prefix ice under a blocker")

        // Topology checks on an empty board of the right shape.
        val board = Board(spec.width, spec.height)
        spec.cells.forEachIndexed { i, c -> board[i % spec.width, i / spec.width].void = c.void }
        val baseTopo = Topology(board, spec.gravity, emptyList())
        val portalCells = HashSet<Pos>()
        for (p in spec.portals) {
            if (!board.inBounds(p.entrance) || !board.inBounds(p.exit)) { fail("$prefix portal out of bounds"); continue }
            if (board[p.entrance].void || board[p.exit].void) fail("$prefix portal on void cell")
            if (baseTopo.naturalNext(p.entrance) != null) fail("$prefix portal entrance ${p.entrance} is not at the end of a column")
            if (baseTopo.naturalPrev(p.exit) != null) fail("$prefix portal exit ${p.exit} is not at the start of a column")
            if (!portalCells.add(p.entrance) || !portalCells.add(p.exit)) fail("$prefix portal cells reused")
            if (spec.cell(p.entrance.x, p.entrance.y).blocker != null || spec.cell(p.exit.x, p.exit.y).blocker != null) fail("$prefix blocker on portal")
        }
        if (spec.portals.isNotEmpty() && spec.gravitySwitchEvery > 0) fail("$prefix portals cannot be combined with shifting gravity")
        val used = HashSet<Pos>()
        for (loop in spec.conveyors) {
            if (loop.size < 4) fail("$prefix conveyor too short")
            for ((i, p) in loop.withIndex()) {
                if (!board.inBounds(p)) { fail("$prefix conveyor out of bounds"); continue }
                val c = spec.cell(p.x, p.y)
                if (c.void || c.blocker != null || c.chain > 0 || c.web) fail("$prefix conveyor cell $p is not free")
                if (!used.add(p)) fail("$prefix conveyor cell $p used twice")
                val next = loop[(i + 1) % loop.size]
                if (!p.isAdjacent(next)) fail("$prefix conveyor not a closed loop at $p")
            }
        }
        if (spec.gravitySwitchEvery < 0) fail("$prefix negative gravity switch")
        if (spec.gravitySwitchEvery > 0 && spec.gravityCycle.size < 2) fail("$prefix gravity cycle needs at least two directions")
        if (spec.gravityCycle.isNotEmpty() && spec.gravityCycle.first() != spec.gravity) fail("$prefix gravity cycle must start with stage gravity")

        val topo = Topology(board, spec.gravity, spec.portals)
        val spawns = board.positions.filter { !board[it].void && topo.isSpawn(it) }
        val openSpawns = spawns.filter { val c = spec.cell(it.x, it.y); c.blocker == null }
        if (spawns.isEmpty()) fail("$prefix no spawn cells")
        if (openSpawns.size * 2 < spawns.size) fail("$prefix most spawn cells are blocked")
        if (st.goals.any { it.type == GoalType.DELIVER_DROPS }) {
            val exits = board.positions.filter { !board[it].void && topo.isExit(it) }
            if (exits.isEmpty()) fail("$prefix delivery goal without exit cells")
            if (spec.gravitySwitchEvery > 0) fail("$prefix delivery goal with shifting gravity")
        }
        // Every cell must be supplied by a spawn eventually (follow predecessors).
        for (p in board.positions) {
            if (board[p].void) continue
            var q = p
            var guard = 0
            while (guard++ < spec.width * spec.height + 2) {
                val prev = topo.prev(q) ?: break
                q = prev
            }
            if (guard > spec.width * spec.height + 1) fail("$prefix flow loop at $p (portals form a cycle)")
            else if (!topo.isSpawn(q)) fail("$prefix cell $p has no spawn upstream")
        }
        val movable = count { !it.void && it.blocker == null && it.chain == 0 && !it.web }
        if (movable < 15) fail("$prefix only $movable freely movable cells")
        if (spec.gravity !in Dir.entries) fail("$prefix bad gravity")
    }

    /** Builds games with several seeds and checks they start playable. */
    fun validateStart(def: LevelDefinition, seeds: Int = 3): List<Issue> {
        val issues = mutableListOf<Issue>()
        val spec = try { def.toGameSpec() } catch (e: IllegalArgumentException) {
            return listOf(Issue(def.id, "spec error: ${e.message}"))
        }
        for (s in 0 until seeds) {
            val g = Match3Game(spec, def.number * 31L + s)
            if (g.status != GameStatus.PLAYING) issues += Issue(def.id, "seed $s: not playing at start")
            if (MatchFinder.findMatches(g.board).isNotEmpty()) issues += Issue(def.id, "seed $s: board starts with a match")
            if (!g.hasPossibleMove()) issues += Issue(def.id, "seed $s: no valid move at start")
        }
        return issues
    }

    /** Bot simulation with the real move limit. Returns the win rate. */
    fun simulate(def: LevelDefinition, runs: Int, seedBase: Long = 77_000): Double {
        val spec = def.toGameSpec()
        return (0 until runs).count { bot.play(spec, seedBase + def.number * 97L + it).won }.toDouble() / runs
    }

    fun validateCatalog(levels: List<LevelDefinition>): List<Issue> {
        val issues = mutableListOf<Issue>()
        if (levels.size != Chapters.TOTAL_LEVELS) issues += Issue("catalog", "expected ${Chapters.TOTAL_LEVELS} levels, found ${levels.size}")
        val ids = levels.map { it.id }
        if (ids.toSet().size != ids.size) issues += Issue("catalog", "duplicate level ids")
        val numbers = levels.map { it.number }.sorted()
        if (numbers != (1..levels.size).toList()) issues += Issue("catalog", "level numbers are not contiguous")
        val layouts = levels.map { fingerprint(it) }
        val dupes = layouts.size - layouts.toSet().size
        if (dupes > 0) issues += Issue("catalog", "$dupes levels duplicate another level's layout and goals")
        return issues
    }
}
