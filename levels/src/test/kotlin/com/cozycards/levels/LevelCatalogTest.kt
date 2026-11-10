package com.cozycards.levels

import com.cozycards.engine.GoalType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.util.concurrent.Executors

/** Validates every bundled level exactly as the app will load it. */
class LevelCatalogTest {

    companion object {
        private val dir = File("../app/src/main/assets/levels")
        lateinit var levels: List<LevelDefinition>

        @BeforeClass
        @JvmStatic
        fun load() {
            levels = LevelCatalog.loadAll { File(dir, it).readText() }
        }
    }

    private val validator = LevelValidator()

    @Test
    fun catalogHasThousandUniqueLevels() {
        assertEquals(1000, levels.size)
        assertEquals(emptyList<LevelValidator.Issue>(), validator.validateCatalog(levels))
        assertEquals(20, levels.map { it.chapter }.toSet().size)
        levels.groupBy { it.chapter }.values.forEach { assertEquals(50, it.size) }
    }

    @Test
    fun everyLevelPassesStructuralValidation() {
        val issues = levels.flatMap { validator.validateStructure(it) }
        assertTrue(issues.take(20).joinToString("\n"), issues.isEmpty())
    }

    @Test
    fun everyLevelStartsPlayable() {
        val issues = parallel(levels) { validator.validateStart(it, seeds = 3) }.flatten()
        assertTrue(issues.take(20).joinToString("\n"), issues.isEmpty())
    }

    /**
     * Release gate: with the real move limit the greedy bot must win every level at least once
     * on the gate seeds. A won game is a constructive proof that the level is solvable.
     */
    @Test
    fun everyLevelIsWinnableBySimulation() {
        val rates = parallel(levels) { it to validator.simulate(it, RELEASE_GATE_RUNS, RELEASE_GATE_SEED) }
        val unwinnable = rates.filter { it.second == 0.0 }.map { it.first.id }
        assertTrue("never won: $unwinnable", unwinnable.isEmpty())
    }

    /** Robustness on seeds never seen during generation: report and require a healthy average. */
    @Test
    fun freshSeedWinRatesAreHealthy() {
        val rates = parallel(levels) { validator.simulate(it, runs = 4, seedBase = 5_550_000) }
        val avg = rates.average()
        val zero = rates.count { it == 0.0 }
        println("Fresh-seed bot win rate: avg %.2f, levels with 0/4: %d".format(avg, zero))
        assertTrue(avg > 0.6)
        assertTrue(zero < 30)
    }

    @Test
    fun rewardsFollowRules() {
        for (l in levels) {
            val expected = when {
                l.number % 50 == 0 -> LootboxTier.LEGENDARY
                l.number % 10 == 0 -> LootboxTier.EPIC
                l.number % 5 == 0 -> LootboxTier.RARE
                else -> LootboxTier.COMMON
            }
            assertEquals(l.id, expected, l.reward.lootbox)
        }
    }

    @Test
    fun chaptersFeatureTheirMechanics() {
        fun chapterHas(ch: Int, pred: (LevelDefinition) -> Boolean) = levels.filter { it.chapter == ch }.count(pred)
        assertTrue(chapterHas(2) { l -> l.stages.any { s -> s.goals.any { it.type == GoalType.CLEAR_ICE } } } >= 25)
        assertTrue(chapterHas(3) { l -> l.stages.any { s -> s.goals.any { it.type == GoalType.BREAK_BOXES } } } >= 25)
        assertTrue(chapterHas(8) { l -> l.stages.any { it.portals.isNotEmpty() } } >= 20)
        assertTrue(chapterHas(10) { l -> l.stages.any { it.conveyors.isNotEmpty() } } >= 20)
        assertTrue(chapterHas(11) { l -> l.stages.any { it.gravity != com.cozycards.engine.Dir.DOWN } } >= 20)
        assertTrue(chapterHas(12) { l -> l.stages.size > 1 } >= 20)
        assertTrue(chapterHas(16) { l -> l.stages.any { it.gravitySwitchEvery > 0 } } >= 20)
        assertTrue(chapterHas(7) { l -> l.stages.any { s -> s.goals.any { it.type == GoalType.OPEN_LOCKS } } } >= 20)
        assertTrue(chapterHas(5) { l -> l.stages.any { s -> s.goals.any { it.type == GoalType.DELIVER_DROPS } } } >= 10)
    }

    @Test
    fun difficultyRisesAcrossChapters() {
        val byChapter = levels.groupBy { it.chapter }.mapValues { e -> e.value.map { it.difficulty }.average() }
        for (ch in 2..20) assertTrue("chapter $ch", byChapter[ch]!! >= byChapter[ch - 1]!! - 0.02)
    }

    @Test
    fun jsonRoundTripIsLossless() {
        val file = ChapterFile(3, levels.filter { it.chapter == 3 })
        val text = LevelCatalog.encodeChapter(file)
        assertEquals(file, LevelCatalog.decodeChapter(text))
    }

    private fun <T, R> parallel(items: List<T>, f: (T) -> R): List<R> {
        val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
        try {
            return items.map { pool.submit<R> { f(it) } }.map { it.get() }
        } finally {
            pool.shutdown()
        }
    }
}
