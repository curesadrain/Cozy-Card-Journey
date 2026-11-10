package com.cozycards.game

import com.cozycards.game.cards.CardCatalog
import com.cozycards.game.cards.CardPack
import com.cozycards.game.progression.GameClock
import com.cozycards.game.state.InMemoryGameStore
import com.cozycards.levels.LevelCatalog
import com.cozycards.levels.LevelDefinition
import java.io.File

class FakeClock(var time: Long = 1_762_800_000_000L /* 2025-11-10 */, var offset: Long = 0) : GameClock {
    override fun now() = time
    override fun zoneOffsetMillis() = offset
    fun advanceDays(d: Int) { time += d * 86_400_000L }
}

object Fixtures {
    val packs: List<CardPack> by lazy {
        File("../app/src/main/assets/cards").listFiles { f -> f.extension == "json" }!!
            .sortedBy { it.name }.map { CardCatalog.parsePack(it.readText()) }
    }
    val catalog by lazy { CardCatalog(packs) }
    val levels: List<LevelDefinition> by lazy {
        LevelCatalog.loadAll { File("../app/src/main/assets/levels", it).readText() }
    }

    fun service(store: InMemoryGameStore = InMemoryGameStore(), clock: FakeClock = FakeClock()) =
        GameService(store, catalog, levels, clock)

    fun win(levelId: String, stars: Int = 3, movesLeft: Int = 5) =
        LevelResult(levelId, true, stars, 5000, movesLeft, 120, 4, 1, 10)

    fun loss(levelId: String) = LevelResult(levelId, false, 0, 1000, 0, 80, 2, 0, 3)
}
