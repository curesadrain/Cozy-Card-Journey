package com.cozycards.levels

import com.cozycards.engine.Blocker
import com.cozycards.engine.BlockerType
import com.cozycards.engine.CellSpec
import com.cozycards.engine.Dir
import com.cozycards.engine.GoalType
import com.cozycards.engine.InitialPiece
import com.cozycards.engine.PieceKind
import com.cozycards.engine.Rng
import com.cozycards.engine.Special
import kotlin.math.ceil
import kotlin.math.roundToInt

object LevelRewards {
    fun lootbox(number: Int): LootboxTier = when {
        number % 50 == 0 -> LootboxTier.LEGENDARY
        number % 10 == 0 -> LootboxTier.EPIC
        number % 5 == 0 -> LootboxTier.RARE
        else -> LootboxTier.COMMON
    }

    fun coins(number: Int): Int {
        val chapter = (number - 1) / Chapters.LEVELS_PER_CHAPTER + 1
        val base = 40 + chapter * 3
        return if (number % 10 == 0) (base * 1.5).roundToInt() else base
    }
}

/**
 * Builds level layouts from authored ingredients:
 *  - a difficulty curve per chapter (rising inside a chapter, breather every 5th, boss every 10th level),
 *  - the chapter's mechanic set (new mechanics are featured, older ones mixed in),
 *  - hand-drawn board silhouettes ([Shapes]) and named obstacle patterns (bands, corners, vaults…).
 * The output is deterministic for a level number. Move limits are filled in afterwards by
 * [LevelCalibrator] using bot simulations.
 */
class LevelGenerator {

    fun difficulty(number: Int): Double {
        val ch = (number - 1) / Chapters.LEVELS_PER_CHAPTER + 1
        val i = (number - 1) % Chapters.LEVELS_PER_CHAPTER + 1
        var d = 0.04 + 0.034 * (ch - 1) + 0.24 * (i - 1) / 49.0
        if (i % 10 == 0) d += 0.12
        if (i % 5 == 1 && i > 1) d -= 0.07
        if (ch == 1 && i <= 10) d = 0.02 + i * 0.008
        return d.coerceIn(0.0, 1.0)
    }

    fun tier(number: Int, d: Double): DifficultyTier {
        val i = (number - 1) % Chapters.LEVELS_PER_CHAPTER + 1
        val t = when {
            d < 0.25 -> DifficultyTier.EASY
            d < 0.55 -> DifficultyTier.NORMAL
            d < 0.8 -> DifficultyTier.HARD
            else -> DifficultyTier.SUPER_HARD
        }
        return if (i % 10 == 0 && t < DifficultyTier.HARD && number > 30) DifficultyTier.HARD else t
    }

    private val favored: Map<Int, List<Mechanic>> = mapOf(
        14 to listOf(Mechanic.ICE, Mechanic.DOUBLE_ICE, Mechanic.DOUBLE_CHAINS, Mechanic.CHAINS),
        15 to listOf(Mechanic.SHAPES, Mechanic.CRATES, Mechanic.STRONG_CRATES, Mechanic.WEBS),
        17 to listOf(Mechanic.STONES, Mechanic.LAYERED_CRATES, Mechanic.CRATES),
        18 to listOf(Mechanic.PORTALS, Mechanic.DROPS, Mechanic.ICE),
    )

    private val obstacleMechanics = setOf(
        Mechanic.ICE, Mechanic.DOUBLE_ICE, Mechanic.CRATES, Mechanic.STRONG_CRATES, Mechanic.LAYERED_CRATES,
        Mechanic.CHAINS, Mechanic.DOUBLE_CHAINS, Mechanic.WEBS, Mechanic.STONES, Mechanic.DROPS, Mechanic.KEYS,
    )

    fun pickMechanics(number: Int, d: Double, rng: Rng): List<Mechanic> {
        val ch = (number - 1) / Chapters.LEVELS_PER_CHAPTER + 1
        val i = (number - 1) % Chapters.LEVELS_PER_CHAPTER + 1
        if (ch == 1) return listOf(Mechanic.BASIC)
        val pool = Chapters.unlockedMechanics(ch).filter { it != Mechanic.BASIC }.toMutableList()
        val chapterNew = Chapters.all[ch - 1].introduces
        val wanted = when {
            d < 0.3 -> 1
            d < 0.62 -> 2
            else -> 3
        }
        val picked = LinkedHashSet<Mechanic>()
        // Teach: the first levels of a chapter feature its new mechanic alone.
        if (chapterNew.isNotEmpty() && (i <= 5 || rng.nextDouble() < 0.7)) picked += rng.pick(chapterNew)
        favored[ch]?.let { if (rng.nextDouble() < 0.7) picked += rng.pick(it) }
        if (i > 5) {
            var guard = 0
            while (picked.size < wanted && guard++ < 40) picked += rng.pick(pool)
        }
        if (picked.none { it in obstacleMechanics } && picked.size < 3 && rng.nextDouble() < 0.5) {
            picked += rng.pick(pool.filter { it in obstacleMechanics })
        }
        // Mechanics that don't mix well.
        if (Mechanic.GRAVITY_SHIFT in picked) { picked -= Mechanic.DROPS; picked -= Mechanic.SIDE_GRAVITY; picked -= Mechanic.CONVEYORS }
        if (Mechanic.SIDE_GRAVITY in picked) { picked -= Mechanic.PORTALS }
        if (Mechanic.DOUBLE_ICE in picked) picked += Mechanic.ICE
        if (Mechanic.DOUBLE_CHAINS in picked) picked += Mechanic.CHAINS
        if (picked.isEmpty()) picked += Mechanic.BASIC
        return picked.toList()
    }

    /**
     * [attempt] > 0 produces an easier variant (used when validation fails);
     * [variant] only changes the random layout choices (used to avoid duplicate levels).
     */
    fun generate(number: Int, attempt: Int = 0, variant: Int = 0): LevelDefinition {
        require(number in 1..Chapters.TOTAL_LEVELS)
        val rng = Rng(number * 7_919L + attempt * 104_729L + variant * 1_299_709L)
        val ch = (number - 1) / Chapters.LEVELS_PER_CHAPTER + 1
        val i = (number - 1) % Chapters.LEVELS_PER_CHAPTER + 1
        // Retries make the level progressively easier so that a valid configuration is always found.
        val d = (difficulty(number) - attempt * 0.08).coerceAtLeast(0.0)
        val mechanics = pickMechanics(number, d, rng)
        val colors = when {
            ch == 1 && i <= 15 -> 4
            d < 0.18 -> 4
            d > 0.85 && rng.nextInt(3) == 0 -> 6
            else -> 5
        }
        val multiStage = Mechanic.MULTI_STAGE in mechanics || (ch >= 13 && i % 10 == 0 && rng.nextInt(2) == 0)
        val stageCount = if (multiStage) (if (d > 0.7) 3 else 2) else 1
        val names = mutableListOf<String>()
        val stages = (0 until stageCount).map { s ->
            val stageMechanics = if (s == 0) mechanics - Mechanic.MULTI_STAGE else (mechanics - Mechanic.MULTI_STAGE).shuffledWith(rng).take(2).ifEmpty { listOf(Mechanic.ICE) }
            val (stage, name) = buildStage(number, d * if (stageCount > 1) 0.8 else 1.0, stageMechanics, colors, rng)
            names += name
            if (s > 0) stage.copy(bonusMoves = 4 + (d * 4).roundToInt()) else stage
        }
        return LevelDefinition(
            id = Chapters.levelId(number),
            number = number,
            chapter = ch,
            indexInChapter = i,
            colors = colors,
            moves = 30,
            difficulty = (d * 1000).roundToInt() / 1000.0,
            tier = tier(number, d),
            stages = stages,
            reward = LevelReward(LevelRewards.coins(number), LevelRewards.lootbox(number)),
            template = names.joinToString(" | "),
        )
    }

    private fun <T> List<T>.shuffledWith(rng: Rng): List<T> = toMutableList().also { rng.shuffle(it) }

    // -------------------------------------------------------------------------------------

    private class MCell(
        var void: Boolean,
        var ice: Int = 0,
        var chain: Int = 0,
        var web: Boolean = false,
        var blocker: Blocker? = null,
        var initial: InitialPiece? = null,
        var reserved: Boolean = false,
    ) {
        val plain get() = !void && blocker == null && ice == 0 && chain == 0 && !web && initial == null && !reserved
        fun toSpec() = CellSpec(void, ice, chain, web, blocker, initial)
    }

    private class Grid(val shape: BoardShape) {
        val w = shape.width
        val h = shape.height
        val cells = Array(h) { y -> Array(w) { x -> MCell(!shape.isCell(x, y)) } }
        operator fun get(x: Int, y: Int) = cells[y][x]
        fun all() = (0 until h).flatMap { y -> (0 until w).map { x -> Triple(x, y, cells[y][x]) } }
        fun topOfColumn(x: Int) = (0 until h).firstOrNull { !cells[it][x].void }
        fun bottomOfColumn(x: Int) = (h - 1 downTo 0).firstOrNull { !cells[it][x].void }
        fun isColumnTop(x: Int, y: Int) = topOfColumn(x) == y
        fun rows() = LayoutCodec.encode(w, all().map { it.third.toSpec() })
    }

    private data class Region(val name: String, val test: (x: Int, y: Int, w: Int, h: Int) -> Boolean)

    private val regions = listOf(
        Region("band") { _, y, _, h -> y in h / 2 - 1..h / 2 + 1 },
        Region("lower") { _, y, _, h -> y >= h / 2 },
        Region("upper") { _, y, _, h -> y in 1 until h / 2 + 1 },
        Region("center") { x, y, w, h -> Math.abs(x - (w - 1) / 2.0) + Math.abs(y - (h - 1) / 2.0) <= 2.6 },
        Region("corners") { x, y, w, h -> (x < 3 || x >= w - 3) && (y < 3 || y >= h - 3) },
        Region("border") { x, y, w, h -> x == 0 || x == w - 1 || y == h - 1 || y == 1 },
        Region("checker") { x, y, _, _ -> (x + y) % 2 == 0 },
        Region("pillars") { x, _, w, _ -> x == 1 || x == w - 2 || x == w / 2 },
        Region("stripes") { x, y, _, _ -> (x + y) % 3 == 0 },
        Region("ring") { x, y, w, h -> val dx = Math.abs(x - (w - 1) / 2.0); val dy = Math.abs(y - (h - 1) / 2.0); maxOf(dx, dy) in 1.5..2.6 },
        Region("bottom") { _, y, _, h -> y >= h - 3 },
    )

    /** Selects up to [count] symmetric cells within [region] satisfying [ok]. */
    private fun pickSymmetric(g: Grid, region: Region, count: Int, rng: Rng, ok: (Int, Int, MCell) -> Boolean): List<Pair<Int, Int>> {
        val groups = g.all()
            .filter { (x, y, c) -> region.test(x, y, g.w, g.h) && ok(x, y, c) }
            .groupBy { (x, y, _) -> minOf(x, g.w - 1 - x) to y }
            .values.map { grp -> grp.map { it.first to it.second } }
            .toMutableList()
        rng.shuffle(groups)
        val out = mutableListOf<Pair<Int, Int>>()
        for (grp in groups) {
            if (out.size >= count) break
            // Only take mirrored pairs when both halves qualify.
            val mirrorOk = grp.all { (x, y) -> val mx = g.w - 1 - x; grp.any { it.first == mx && it.second == y } }
            if (!mirrorOk) continue
            out += grp
        }
        return out
    }

    private fun buildStage(number: Int, d: Double, mechanics: List<Mechanic>, colors: Int, rng: Rng): Pair<StageDef, String> {
        val ch = (number - 1) / Chapters.LEVELS_PER_CHAPTER + 1
        val i = (number - 1) % Chapters.LEVELS_PER_CHAPTER + 1
        val shape = when {
            ch == 1 && i <= 10 -> Shapes.byName("courtyard7")
            ch == 1 && i <= 25 -> rng.pick(Shapes.regular.take(3))
            Mechanic.SHAPES in mechanics || ch == 15 -> rng.pick(Shapes.irregularShapes)
            Mechanic.PORTALS in mechanics -> rng.pick(listOf("twin_towers", "bridge", "plaza9", "ring", "cross").map { Shapes.byName(it) })
            Mechanic.CONVEYORS in mechanics -> rng.pick(listOf("plaza9", "street8x9", "courtyard8", "tatami", "cross", "frame").map { Shapes.byName(it) })
            rng.nextDouble() < 0.45 -> rng.pick(Shapes.irregularShapes)
            else -> rng.pick(Shapes.regular)
        }
        val g = Grid(shape)
        val nameParts = mutableListOf(shape.name)
        val playable = shape.cellCount
        var gravity = Dir.DOWN
        var gravityCycle = emptyList<Dir>()
        var gravityEvery = 0
        val portals = mutableListOf<PortalDef>()
        val conveyors = mutableListOf<List<PosDef>>()
        var dropSpawns = 0
        var keySpawns = 0
        val goals = mutableListOf<GoalDef>()

        if (Mechanic.SIDE_GRAVITY in mechanics) {
            gravity = rng.pick(listOf(Dir.LEFT, Dir.RIGHT, Dir.LEFT, Dir.RIGHT, Dir.UP))
            nameParts += "wind-${gravity.name.lowercase()}"
        }
        if (Mechanic.GRAVITY_SHIFT in mechanics) {
            gravityCycle = if (d > 0.75) listOf(Dir.DOWN, Dir.RIGHT, Dir.UP, Dir.LEFT) else listOf(Dir.DOWN, Dir.LEFT, Dir.DOWN, Dir.RIGHT)
            gravityEvery = if (d > 0.75) 3 else 4
            nameParts += "turning-sky"
        }

        // Structural features first (they reserve cells).
        if (Mechanic.CONVEYORS in mechanics && gravity == Dir.DOWN) {
            val loop = conveyorLoop(g, rng)
            if (loop != null) {
                loop.forEach { (x, y) -> g[x, y].reserved = true }
                conveyors += loop.map { PosDef(it.first, it.second) }
                nameParts += "belt"
            }
        }
        if (Mechanic.PORTALS in mechanics && gravity == Dir.DOWN) {
            val pairs = 1 + (if (d > 0.5) 1 else 0)
            val columns = (0 until g.w / 2).filter { g.topOfColumn(it) != null && g.topOfColumn(g.w - 1 - it) != null }.toMutableList()
            rng.shuffle(columns)
            for ((n, a) in columns.take(pairs).withIndex()) {
                val b = g.w - 1 - a
                // Alternate direction so both halves feed each other.
                val (from, to) = if (n % 2 == 0) a to b else b to a
                val ey = g.bottomOfColumn(from)!!
                val xy = g.topOfColumn(to)!!
                g[from, ey].reserved = true
                g[to, xy].reserved = true
                portals += PortalDef(PosDef(from, ey), PosDef(to, xy))
            }
            if (portals.isNotEmpty()) nameParts += "tide-portals"
        }

        val notTop: (Int, Int, MCell) -> Boolean = { x, y, c -> c.plain && !g.isColumnTop(x, y) }
        val amount = { lo: Double, hi: Double -> ((lo + (hi - lo) * d) * playable).roundToInt().coerceAtLeast(2) }

        if (Mechanic.KEYS in mechanics) {
            val region = regions.first { it.name == "bottom" }
            val locks = pickSymmetric(g, region, 2 + (d * 3).roundToInt(), rng, notTop)
            locks.forEach { (x, y) -> g[x, y].blocker = Blocker(BlockerType.LOCK, 1) }
            if (locks.isNotEmpty()) {
                val keySpot = pickSymmetric(g, regions.first { it.name == "upper" }, 1, rng) { _, _, c -> c.plain }.firstOrNull()
                keySpot?.let { (x, y) -> g[x, y].initial = InitialPiece(PieceKind.KEY) }
                keySpawns = locks.size + 1 - (if (keySpot != null) 1 else 0)
                goals += GoalDef(GoalType.OPEN_LOCKS, locks.size)
                nameParts += "vault"
            }
        }
        if (Mechanic.STONES in mechanics) {
            val r = rng.pick(regions.filter { it.name in setOf("pillars", "center", "band", "corners") })
            val cells = pickSymmetric(g, r, 2 + (d * 5).roundToInt(), rng, notTop)
            val hp = if (d > 0.75 && rng.nextInt(2) == 0) 2 else 1
            cells.forEach { (x, y) -> g[x, y].blocker = Blocker(BlockerType.STONE, hp) }
            if (cells.isNotEmpty()) { goals += GoalDef(GoalType.BREAK_STONES, cells.size); nameParts += "stones-${r.name}" }
        }
        if (Mechanic.CRATES in mechanics || Mechanic.STRONG_CRATES in mechanics || Mechanic.LAYERED_CRATES in mechanics) {
            val r = rng.pick(regions.filter { it.name != "checker" })
            val cells = pickSymmetric(g, r, amount(0.06, 0.2), rng, notTop)
            val hps = buildList {
                if (Mechanic.CRATES in mechanics) add(1)
                if (Mechanic.STRONG_CRATES in mechanics) add(2)
                if (Mechanic.LAYERED_CRATES in mechanics) add(3)
            }
            // Mirror pairs share a hp so that the layout stays symmetric.
            val byRow = cells.groupBy { minOf(it.first, g.w - 1 - it.first) to it.second }
            byRow.values.forEach { pair -> val hp = rng.pick(hps); pair.forEach { (x, y) -> g[x, y].blocker = Blocker(BlockerType.BOX, hp) } }
            if (cells.isNotEmpty()) { goals += GoalDef(GoalType.BREAK_BOXES, cells.size); nameParts += "crates-${r.name}" }
        }
        if (Mechanic.CHAINS in mechanics) {
            val r = rng.pick(regions)
            val cells = pickSymmetric(g, r, amount(0.06, 0.18), rng) { _, _, c -> !c.void && c.blocker == null && !c.reserved && c.initial?.kind != PieceKind.DROP }
            val double = Mechanic.DOUBLE_CHAINS in mechanics
            cells.forEach { (x, y) -> g[x, y].chain = if (double && (x + y) % 2 == 0) 2 else 1 }
            if (cells.isNotEmpty()) { goals += GoalDef(GoalType.BREAK_CHAINS, cells.size); nameParts += "chains-${r.name}" }
        }
        if (Mechanic.WEBS in mechanics) {
            val r = rng.pick(regions)
            val cells = pickSymmetric(g, r, amount(0.05, 0.16), rng) { _, _, c -> !c.void && c.blocker == null && c.chain == 0 && !c.reserved && c.initial == null }
            cells.forEach { (x, y) -> g[x, y].web = true }
            if (cells.isNotEmpty()) { goals += GoalDef(GoalType.CLEAR_WEBS, cells.size); nameParts += "webs-${r.name}" }
        }
        if (Mechanic.ICE in mechanics) {
            val r = rng.pick(regions)
            val cells = pickSymmetric(g, r, amount(0.18, 0.5), rng) { _, _, c -> !c.void && c.blocker == null }
            val double = Mechanic.DOUBLE_ICE in mechanics
            cells.forEach { (x, y) -> g[x, y].ice = if (double && rng.nextDouble() < 0.35 + d * 0.4) 2 else 1 }
            if (cells.isNotEmpty()) { goals += GoalDef(GoalType.CLEAR_ICE, cells.size); nameParts += "ice-${r.name}" }
        }
        if (Mechanic.DROPS in mechanics) {
            val total = 1 + (d * 3).roundToInt().coerceAtMost(3)
            val spot = (0 until g.w).toList().shuffledWith(rng).firstNotNullOfOrNull { x ->
                val y = g.topOfColumn(x) ?: return@firstNotNullOfOrNull null
                if (g[x, y].plain) x to y else null
            }
            spot?.let { (x, y) -> g[x, y].initial = InitialPiece(PieceKind.DROP) }
            dropSpawns = total - (if (spot != null) 1 else 0)
            goals += GoalDef(GoalType.DELIVER_DROPS, total)
            nameParts += "charms"
        }

        // Occasional gift specials (teaching in chapter 1).
        val giftChance = if (ch == 1) (if (i % 3 == 0) 1.0 else 0.0) else 0.12
        if (rng.nextDouble() < giftChance) {
            val spot = g.all().filter { (x, y, c) -> c.plain && y in 2 until g.h - 1 }.let { if (it.isEmpty()) null else rng.pick(it) }
            if (spot != null) {
                val special = rng.pick(listOf(Special.ROCKET_H, Special.ROCKET_V, Special.BOMB, Special.PLANE))
                spot.third.initial = InitialPiece(special = special)
                nameParts += "gift"
            }
        }

        // Colour goals: always when there's no obstacle goal, sometimes as an extra.
        if (goals.isEmpty() || (goals.size < 3 && rng.nextDouble() < 0.25)) {
            val n = if (goals.isEmpty() && d > 0.3) 2 else 1
            val palette = (0 until colors).toList().shuffledWith(rng).take(n)
            for (c in palette) goals += GoalDef(GoalType.COLLECT_COLOR, (18 + d * 30).roundToInt() + rng.nextInt(6), c)
        }
        // At most three goals keep the HUD readable.
        val finalGoals = goals.take(3)
        if (finalGoals.none { it.type == GoalType.OPEN_LOCKS }) keySpawns = 0
        if (finalGoals.none { it.type == GoalType.DELIVER_DROPS }) {
            dropSpawns = 0
            g.all().forEach { (_, _, c) -> if (c.initial?.kind == PieceKind.DROP) c.initial = null }
        }

        val stage = StageDef(
            rows = g.rows(),
            goals = finalGoals,
            gravity = gravity,
            portals = portals,
            conveyors = conveyors,
            gravityCycle = gravityCycle,
            gravitySwitchEvery = gravityEvery,
            dropSpawns = dropSpawns,
            keySpawns = keySpawns,
        )
        return stage to nameParts.joinToString("+")
    }

    /** Finds a 2-row rectangular ring in the middle of the board that is fully playable. */
    private fun conveyorLoop(g: Grid, rng: Rng): List<Pair<Int, Int>>? {
        val candidates = mutableListOf<List<Pair<Int, Int>>>()
        for (y0 in 2 until g.h - 2) {
            val y1 = y0 + 1
            var x0 = 0
            while (x0 < g.w) {
                var x1 = x0
                while (x1 < g.w && g[x1, y0].plain && g[x1, y1].plain) x1++
                val len = x1 - x0
                if (len >= 4) {
                    val loop = (x0 until x1).map { it to y0 } + (x1 - 1 downTo x0).map { it to y1 }
                    candidates += loop
                }
                x0 = x1 + 1
            }
        }
        return if (candidates.isEmpty()) null else rng.pick(candidates)
    }
}

/**
 * Fills in move limits from simulated play and rejects levels the bot cannot finish.
 */
class LevelCalibrator(private val bot: BotPlayer = BotPlayer(), private val runs: Int = 12) {

    fun minWinRate(d: Double) = (0.75 - 0.35 * d).coerceIn(0.4, 0.75)

    fun calibrate(def: LevelDefinition): LevelDefinition? {
        val unlimited = def.copy(moves = 250)
        val results = (0 until runs).map { bot.play(unlimited.toGameSpec(), def.number * 1_000L + it) }
        val wins = results.filter { it.won }.map { it.movesUsed }.sorted()
        if (wins.size < runs * 3 / 4) return null
        val bonus = def.stages.drop(1).sumOf { it.bonusMoves }
        val q = (0.85 - 0.35 * def.difficulty).coerceIn(0.5, 0.85)
        val slack = (1.3 - 0.3 * def.difficulty).coerceIn(1.0, 1.3)
        val idx = ((wins.size - 1) * q).roundToInt()
        val floor = (20 - 10 * def.difficulty).toInt()
        var moves = (ceil(wins[idx] * slack).toInt() + 2 - bonus).coerceIn(floor, 75)
        var winRate = 0.0
        repeat(5) {
            val spec = def.copy(moves = moves).toGameSpec()
            val verify = (0 until runs).map { bot.play(spec, def.number * 1_000L + 500 + it) }
            winRate = verify.count { it.won }.toDouble() / runs
            if (winRate >= minWinRate(def.difficulty)) {
                return def.copy(
                    moves = moves,
                    metrics = LevelMetrics((winRate * 100).roundToInt() / 100.0, wins[wins.size / 2], runs * 2),
                )
            }
            moves = (ceil(moves * 1.12).toInt() + 1).coerceAtMost(80)
        }
        return null
    }
}
