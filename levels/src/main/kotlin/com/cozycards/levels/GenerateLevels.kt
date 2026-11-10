package com.cozycards.levels

import java.io.File
import java.util.concurrent.Executors

/** Seeds of the release gate: every shipped level must be won by the bot at least once with these. */
const val RELEASE_GATE_SEED = 991_000L
const val RELEASE_GATE_RUNS = 8

fun fingerprint(def: LevelDefinition) = def.stages.joinToString("/") { it.rows.joinToString("|") + it.goals }

/**
 * Offline build tool: generates, calibrates and validates all levels, then writes one JSON file
 * per chapter. Run with `./gradlew :levels:generateLevels`.
 * Levels that fail validation or simulation are regenerated as an easier variant; levels that
 * duplicate an earlier level are regenerated with a different layout variant.
 */
fun main(args: Array<String>) {
    val outDir = File(args.getOrElse(0) { "app/src/main/assets/levels" })
    outDir.mkdirs()
    val generator = LevelGenerator()
    val validator = LevelValidator()
    val calibrator = LevelCalibrator()
    val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
    val started = System.currentTimeMillis()

    fun build(number: Int, variant: Int): LevelDefinition {
        var attempt = 0
        while (true) {
            val raw = generator.generate(number, attempt, variant)
            val structural = validator.validateStructure(raw) + validator.validateStart(raw)
            if (structural.isEmpty()) {
                var calibrated = calibrator.calibrate(raw)
                var tries = 0
                while (calibrated != null && tries++ < 3 &&
                    validator.simulate(calibrated, RELEASE_GATE_RUNS, RELEASE_GATE_SEED) == 0.0
                ) {
                    calibrated = calibrated.copy(moves = (calibrated.moves * 1.15).toInt() + 1)
                }
                if (calibrated != null && validator.validateStructure(calibrated).isEmpty() &&
                    validator.simulate(calibrated, RELEASE_GATE_RUNS, RELEASE_GATE_SEED) > 0.0
                ) {
                    return calibrated
                }
            }
            attempt++
            check(attempt < 40) { "could not produce a valid level $number: $structural" }
        }
    }

    val seen = HashSet<String>()
    for (chapter in 1..Chapters.COUNT) {
        val numbers = (1..Chapters.LEVELS_PER_CHAPTER).map { (chapter - 1) * Chapters.LEVELS_PER_CHAPTER + it }
        val built = numbers.map { n -> pool.submit<LevelDefinition> { build(n, 0) } }.map { it.get() }
        // Sequential de-duplication keeps the output deterministic.
        val levels = built.map { def ->
            var d = def
            var variant = 0
            while (!seen.add(fingerprint(d))) {
                variant++
                d = build(def.number, variant)
            }
            if (variant > 0) println("  level ${def.number} re-rolled $variant time(s) to avoid a duplicate")
            d
        }
        File(outDir, LevelCatalog.fileName(chapter)).writeText(LevelCatalog.encodeChapter(ChapterFile(chapter, levels)))
        val avgMoves = levels.map { it.moves }.average()
        val avgWin = levels.mapNotNull { it.metrics?.botWinRate }.average()
        println("chapter $chapter: avg moves %.1f, avg bot win rate %.2f (%ds)".format(avgMoves, avgWin, (System.currentTimeMillis() - started) / 1000))
    }
    pool.shutdown()
}
