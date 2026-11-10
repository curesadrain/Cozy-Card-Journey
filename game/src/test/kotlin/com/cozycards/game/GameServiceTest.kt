package com.cozycards.game

import com.cozycards.game.cards.Rarity
import com.cozycards.game.economy.BoosterType
import com.cozycards.game.economy.Cosmetic
import com.cozycards.game.economy.CosmeticSlot
import com.cozycards.game.economy.Economy
import com.cozycards.game.economy.ShopCatalog
import com.cozycards.game.economy.ShopItem
import com.cozycards.game.progression.DailyRewards
import com.cozycards.game.progression.Stat
import com.cozycards.game.state.InMemoryGameStore
import com.cozycards.game.state.InsufficientFundsException
import com.cozycards.game.state.OwnedCard
import com.cozycards.game.state.SaveCodec
import com.cozycards.game.state.SaveFormatException
import com.cozycards.levels.Chapters
import com.cozycards.levels.LootboxTier
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GameServiceTest {

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit): T {
        try {
            block()
        } catch (t: Throwable) {
            if (t is T) return t
            throw AssertionError("expected ${T::class.simpleName} but got $t", t)
        }
        fail("expected ${T::class.simpleName}")
        error("unreachable")
    }

    @Test
    fun starterStateIsGrantedOnce() = runTest {
        val store = InMemoryGameStore()
        val s = Fixtures.service(store)
        s.initialize(); s.initialize()
        assertEquals(GameService.STARTER_COINS, store.data.wallet.coins)
        assertEquals(BoosterType.HAMMER.starterAmount, store.data.boosters["HAMMER"])
        assertEquals(Cosmetic.BACK_CLASSIC.name, store.data.meta[GameService.equippedKey(CosmeticSlot.CARD_BACK)])
    }

    @Test
    fun firstClearRewardsExactlyOnce() = runTest {
        val store = InMemoryGameStore()
        val s = Fixtures.service(store)
        s.initialize()
        val r1 = s.completeLevel(Fixtures.win("c01_l01"))
        assertTrue(r1.firstClear)
        assertEquals(1, r1.lootboxes.size)
        assertEquals(2, r1.unlockedLevel)
        val coinsAfterFirst = store.data.wallet.coins
        val r2 = s.completeLevel(Fixtures.win("c01_l01"))
        assertFalse(r2.firstClear)
        assertTrue(r2.lootboxes.isEmpty())
        assertEquals(1, store.data.lootboxes.size)
        assertTrue(store.data.wallet.coins - coinsAfterFirst <= Economy.REPLAY_COINS * 2)
        assertEquals(2, store.data.levels.single().wins)
    }

    @Test
    fun lossGivesNoRewardAndNoPenalty() = runTest {
        val store = InMemoryGameStore()
        val s = Fixtures.service(store)
        s.initialize()
        val before = store.data.wallet
        val r = s.completeLevel(Fixtures.loss("c01_l01"))
        assertFalse(r.firstClear)
        assertEquals(before, store.data.wallet)
        // The level can be retried immediately – there is no lives system.
        s.startLevel("c01_l01", emptySet())
        s.startLevel("c01_l01", emptySet())
    }

    @Test
    fun lockedLevelsCannotStart() = runTest {
        val s = Fixtures.service()
        s.initialize()
        assertThrows<GameRuleException> { s.startLevel("c01_l03", emptySet()) }
        s.completeLevel(Fixtures.win("c01_l01"))
        s.completeLevel(Fixtures.win("c01_l02"))
        s.startLevel("c01_l03", emptySet())
    }

    @Test
    fun milestoneLevelsGiveBetterBoxes() = runTest {
        val s = Fixtures.service()
        s.initialize()
        assertEquals(LootboxTier.RARE, s.completeLevel(Fixtures.win("c01_l05")).lootboxes.single().tier)
        assertEquals(LootboxTier.EPIC, s.completeLevel(Fixtures.win("c01_l10")).lootboxes.single().tier)
        assertEquals(LootboxTier.LEGENDARY, s.completeLevel(Fixtures.win("c01_l50")).lootboxes.first().tier)
    }

    @Test
    fun chapterCompletionBonusOnce() = runTest {
        val store = InMemoryGameStore()
        val s = Fixtures.service(store)
        s.initialize()
        var chapterDone: Int? = null
        for (n in 1..50) {
            val r = s.completeLevel(Fixtures.win(Chapters.levelId(n)))
            if (r.chapterCompleted != null) chapterDone = r.chapterCompleted
        }
        assertEquals(1, chapterDone)
        // 50 level boxes + chapter box
        assertEquals(51, store.data.lootboxes.size)
        assertNull(s.completeLevel(Fixtures.win("c01_l50")).chapterCompleted)
        assertEquals(51, store.data.lootboxes.size)
        assertEquals(1L, store.data.meta[GameService.statKey(Stat.CHAPTERS_COMPLETED)]!!.toLong())
    }

    @Test
    fun failedTransactionRollsBackEverything() = runTest {
        val store = InMemoryGameStore()
        val s = Fixtures.service(store)
        s.initialize()
        val before = store.data
        store.failBeforeCommit = true
        assertThrows<IllegalStateException> { s.completeLevel(Fixtures.win("c01_l01")) }
        assertEquals(before, store.data)
        store.failBeforeCommit = false
        // Retrying after the "crash" grants the reward once.
        assertTrue(s.completeLevel(Fixtures.win("c01_l01")).firstClear)
        assertEquals(1, store.data.lootboxes.size)
    }

    @Test
    fun openingLootboxIsAtomicAndNotRepeatable() = runTest {
        val store = InMemoryGameStore()
        val s = Fixtures.service(store)
        s.initialize()
        val box = s.completeLevel(Fixtures.win("c01_l01")).lootboxes.single()
        store.failBeforeCommit = true
        assertThrows<IllegalStateException> { s.openLootbox(box.id) }
        assertEquals(1, store.data.lootboxes.size)
        assertTrue(store.data.cards.isEmpty())
        store.failBeforeCommit = false
        val opened = s.openLootbox(box.id)
        assertEquals(3, opened.draws.size)
        assertEquals(3, store.data.cards.sumOf { it.count })
        assertEquals(1, store.data.opened.size)
        assertThrows<GameRuleException> { s.openLootbox(box.id) }
        assertEquals(3, store.data.cards.sumOf { it.count })
    }

    @Test
    fun boxContentsAreFixedAtGrantTime() = runTest {
        // Same save, opened twice from a copy: same cards (no reroll by force-closing the app).
        val store = InMemoryGameStore()
        val s = Fixtures.service(store)
        s.initialize()
        val box = s.completeLevel(Fixtures.win("c01_l01")).lootboxes.single()
        val copy = InMemoryGameStore(store.data)
        val a = s.openLootbox(box.id).draws
        val b = Fixtures.service(copy).openLootbox(box.id).draws
        assertEquals(a, b)
    }

    @Test
    fun openAllOpensEverything() = runTest {
        val store = InMemoryGameStore()
        val s = Fixtures.service(store)
        s.initialize()
        for (n in 1..12) s.completeLevel(Fixtures.win(Chapters.levelId(n)))
        val results = s.openAll()
        assertEquals(12, results.size)
        assertTrue(store.data.lootboxes.isEmpty())
        val total = results.sumOf { it.draws.size }
        assertEquals(total, store.data.cards.sumOf { it.count })
        assertEquals(total, store.data.history.size)
    }

    @Test
    fun recycleProtectsLastCopyAndRareCards() = runTest {
        val legendary = Fixtures.catalog.pool(Rarity.LEGENDARY).first().id
        val common = Fixtures.catalog.pool(Rarity.COMMON).first().id
        val store = InMemoryGameStore()
        store.transaction { tx ->
            tx.putOwnedCard(OwnedCard(legendary, 2, 0, 0))
            tx.putOwnedCard(OwnedCard(common, 4, 0, 0))
        }
        val s = Fixtures.service(store)
        assertThrows<GameRuleException> { s.recycle(common, 4) }
        assertThrows<GameRuleException> { s.recycle(legendary, 1) }
        assertEquals(Economy.recycleValue(Rarity.LEGENDARY), s.recycle(legendary, 1, confirmRare = true))
        assertThrows<GameRuleException> { s.recycle(legendary, 1, confirmRare = true) }
        assertEquals(1, store.data.cards.first { it.cardId == legendary }.count)
        assertEquals(3 * Economy.recycleValue(Rarity.COMMON), s.recycleSafeDuplicates())
        assertEquals(1, store.data.cards.first { it.cardId == common }.count)
    }

    @Test
    fun safeRecycleSkipsEpicAndFavourites() = runTest {
        val epic = Fixtures.catalog.pool(Rarity.EPIC).first().id
        val rare = Fixtures.catalog.pool(Rarity.RARE).first().id
        val store = InMemoryGameStore()
        store.transaction { tx ->
            tx.putOwnedCard(OwnedCard(epic, 3, 0, 0))
            tx.putOwnedCard(OwnedCard(rare, 3, 0, 0, favorite = true))
        }
        assertEquals(0L, Fixtures.service(store).recycleSafeDuplicates())
        assertEquals(3, store.data.cards.first { it.cardId == epic }.count)
    }

    @Test
    fun exchangeBuysMissingCardsOnly() = runTest {
        val store = InMemoryGameStore()
        val s = Fixtures.service(store)
        val card = Fixtures.catalog.pool(Rarity.RARE).first()
        assertThrows<InsufficientFundsException> { s.exchangeForCard(card.id) }
        store.transaction { it.setWallet(it.wallet().copy(tokens = 100)) }
        s.exchangeForCard(card.id)
        assertEquals(100 - Economy.exchangePrice(Rarity.RARE), store.data.wallet.tokens)
        assertThrows<GameRuleException> { s.exchangeForCard(card.id) }
    }

    @Test
    fun completingAlbumPaysOnce() = runTest {
        val store = InMemoryGameStore()
        val s = Fixtures.service(store)
        val album = Fixtures.catalog.collections.first().albums.first()
        store.transaction { tx -> album.cardIds.drop(1).forEach { tx.putOwnedCard(OwnedCard(it, 1, 0, 0)) } }
        store.transaction { it.setWallet(it.wallet().copy(tokens = 1000)) }
        val res = s.exchangeForCard(album.cardIds.first())
        assertEquals(listOf(album.id), res.completedAlbums)
        assertTrue(store.data.ledger.containsKey("album.${album.id}"))
    }

    @Test
    fun walletNeverNegative() = runTest {
        val store = InMemoryGameStore()
        val s = Fixtures.service(store)
        val expensive = ShopCatalog.items(listOf("bts")).first { it is ShopItem.Box && it.coins > 0 }
        assertThrows<InsufficientFundsException> { s.buy(expensive) }
        assertEquals(0L, store.data.wallet.coins)
        assertThrows<IllegalArgumentException> { com.cozycards.game.state.Wallet(-1, 0) }
    }

    @Test
    fun shopPurchasesWork() = runTest {
        val store = InMemoryGameStore()
        val s = Fixtures.service(store)
        s.initialize()
        store.transaction { it.setWallet(it.wallet().copy(coins = 10_000, tokens = 1_000)) }
        val items = ShopCatalog.items(Fixtures.catalog.collections.map { it.id })
        val hammer5 = items.filterIsInstance<ShopItem.Booster>().first { it.type == BoosterType.HAMMER && it.amount == 5 }
        s.buy(hammer5)
        assertEquals(BoosterType.HAMMER.starterAmount + 5, store.data.boosters["HAMMER"])
        val focused = items.filterIsInstance<ShopItem.Box>().first { it.collectionId == "bts" }
        val box = s.buy(focused)!!
        assertEquals("bts", box.collectionId)
        assertTrue(s.openLootbox(box.id).draws.all { Fixtures.catalog.require(it.cardId).collectionId == "bts" })
        val style = items.filterIsInstance<ShopItem.Style>().first { it.cosmetic == Cosmetic.BACK_SAKURA }
        assertThrows<GameRuleException> { s.equip(Cosmetic.BACK_SAKURA) }
        s.buy(style)
        assertThrows<GameRuleException> { s.buy(style) }
        s.equip(Cosmetic.BACK_SAKURA)
        assertEquals("BACK_SAKURA", store.data.meta[GameService.equippedKey(CosmeticSlot.CARD_BACK)])
    }

    @Test
    fun boostersAreConsumedAndCannotGoNegative() = runTest {
        val store = InMemoryGameStore()
        val s = Fixtures.service(store)
        s.initialize()
        repeat(BoosterType.RAINBOW.starterAmount) { s.startLevel("c01_l01", setOf(BoosterType.RAINBOW)) }
        assertThrows<GameRuleException> { s.startLevel("c01_l01", setOf(BoosterType.RAINBOW)) }
        assertEquals(0, store.data.boosters["RAINBOW"])
        assertThrows<GameRuleException> { s.useInLevelBooster(BoosterType.RAINBOW) }
        s.useInLevelBooster(BoosterType.HAMMER)
        assertEquals(BoosterType.HAMMER.starterAmount - 1, store.data.boosters["HAMMER"])
    }

    @Test
    fun dailyRewardOncePerDayWithStreak() = runTest {
        val clock = FakeClock()
        val store = InMemoryGameStore()
        val s = Fixtures.service(store, clock)
        assertEquals(DailyRewards.cycle[0], s.claimDaily())
        assertThrows<GameRuleException> { s.claimDaily() }
        clock.advanceDays(1)
        assertEquals(DailyRewards.cycle[1], s.claimDaily())
        clock.advanceDays(2) // missed a day → streak resets
        assertEquals(DailyRewards.cycle[0], s.claimDaily())
    }

    @Test
    fun movingClockBackwardsDoesNotGrantRewards() = runTest {
        val clock = FakeClock()
        val store = InMemoryGameStore()
        val s = Fixtures.service(store, clock)
        clock.advanceDays(5)
        s.claimDaily()
        clock.advanceDays(-3)
        val status = s.dailyStatus()
        assertTrue(status.clockTampered)
        assertFalse(status.canClaim)
        assertThrows<GameRuleException> { s.claimDaily() }
        clock.advanceDays(3) // back to the real day: already claimed
        assertThrows<GameRuleException> { s.claimDaily() }
        clock.advanceDays(1)
        s.claimDaily()
        assertEquals(2, store.data.ledger.keys.count { it.startsWith("daily.") })
    }

    @Test
    fun questsTrackAndPayOnce() = runTest {
        val clock = FakeClock()
        val store = InMemoryGameStore()
        val s = Fixtures.service(store, clock)
        s.initialize()
        for (n in 1..25) s.completeLevel(Fixtures.win(Chapters.levelId(n)))
        val quests = s.quests()
        assertTrue(quests.size >= 6)
        val done = quests.filter { it.complete }
        assertTrue("some quests should be complete", done.isNotEmpty())
        val q = done.first()
        s.claimQuest(q.def.id)
        assertThrows<GameRuleException> { s.claimQuest(q.def.id) }
        val incomplete = s.quests().firstOrNull { !it.complete }
        if (incomplete != null) assertThrows<GameRuleException> { s.claimQuest(incomplete.def.id) }
        // A new day brings fresh daily quests with zero progress.
        clock.advanceDays(1)
        assertTrue(s.quests().filter { it.periodKey.startsWith("d") }.all { it.progress == 0L })
    }

    @Test
    fun achievementsUnlockAndClaimOnce() = runTest {
        val store = InMemoryGameStore()
        val s = Fixtures.service(store)
        s.initialize()
        s.completeLevel(Fixtures.win("c01_l01"))
        val first = s.achievements().first { it.def.id == "win_1" }
        assertTrue(first.unlocked)
        s.claimAchievement("win_1")
        assertThrows<GameRuleException> { s.claimAchievement("win_1") }
        assertThrows<GameRuleException> { s.claimAchievement("win_10") }
    }

    @Test
    fun saveExportImportRoundTrip() = runTest {
        val store = InMemoryGameStore()
        val s = Fixtures.service(store)
        s.initialize()
        for (n in 1..7) s.completeLevel(Fixtures.win(Chapters.levelId(n)))
        s.openAll()
        val text = SaveCodec.export(store.data, 123)
        val restored = SaveCodec.import(text, Fixtures.catalog.cards.map { it.id }.toSet())
        assertEquals(store.data, restored)
    }

    @Test
    fun corruptedOrTamperedSavesAreRejected() {
        val text = SaveCodec.export(com.cozycards.game.state.SaveData(wallet = com.cozycards.game.state.Wallet(10, 0)), 1)
        assertThrows<SaveFormatException> { SaveCodec.import(text.replace("\\\"coins\\\":10", "\\\"coins\\\":999999")) }
        assertThrows<SaveFormatException> { SaveCodec.import(text.take(text.length / 2)) }
        assertThrows<SaveFormatException> { SaveCodec.import("hello") }
    }

    @Test
    fun migratesVersionOneSaves() {
        val v1 = """{"version":1,"coins":420,"tokens":7,"ledger":{"level.first.c01_l01":5},"levels":[],"cards":[],"history":[],"lootboxes":[],"opened":[],"boosters":{},"meta":{},"rewardLog":[]}"""
        val env = """{"format":"${SaveCodec.FORMAT}","version":1,"createdAt":0,"checksum":"${SaveCodec.sha256(v1)}","payload":${kotlinx.serialization.json.JsonPrimitive(v1)}}"""
        val data = SaveCodec.import(env)
        assertEquals(420L, data.wallet.coins)
        assertEquals(7L, data.wallet.tokens)
        assertEquals(2, data.version)
        assertNotNull(data.ledger["level.first.c01_l01"])
    }

    @Test
    fun importDropsUnknownCards() {
        val data = com.cozycards.game.state.SaveData(cards = listOf(OwnedCard("bts_001", 2, 0, 0), OwnedCard("ghost_999", 1, 0, 0)))
        val back = SaveCodec.import(SaveCodec.export(data, 0), Fixtures.catalog.cards.map { it.id }.toSet())
        assertEquals(listOf("bts_001"), back.cards.map { it.cardId })
    }

    @Test
    fun longPlaythroughKeepsInvariants() = runTest {
        // Simulated player: completes all 1000 levels, opens every box, recycles and exchanges.
        val store = InMemoryGameStore()
        val clock = FakeClock()
        val s = Fixtures.service(store, clock)
        s.initialize()
        var boxesGranted = 0
        for (n in 1..Chapters.TOTAL_LEVELS) {
            val r = s.completeLevel(Fixtures.win(Chapters.levelId(n), stars = 1 + n % 3))
            boxesGranted += r.lootboxes.size
            if (n % 25 == 0) {
                s.openAll()
                s.recycleSafeDuplicates()
                clock.advanceDays(1)
                runCatching { s.claimDaily() }
            }
        }
        s.openAll()
        val d = store.data
        assertTrue(d.wallet.coins >= 0 && d.wallet.tokens >= 0)
        assertEquals(1000, d.levels.size)
        assertEquals(20, d.ledger.keys.count { it.startsWith("chapter.complete.") })
        val received = d.opened.sumOf { it.draws.size }
        assertEquals(received, d.history.count { it.source != "Token exchange" })
        // Missing cards can always be completed with tokens earned.
        println("After 1000 levels: coins=${d.wallet.coins} tokens=${d.wallet.tokens} unique=${d.cards.size}/${Fixtures.catalog.cards.size} boxes=$boxesGranted")
        assertTrue(d.cards.size >= Fixtures.catalog.cards.size * 0.8)
    }
}
