package com.cozycards.game

import com.cozycards.game.cards.CardCatalog
import com.cozycards.game.cards.CardPackValidator
import com.cozycards.game.cards.ImageRights
import com.cozycards.game.cards.Rarity
import com.cozycards.game.cards.VerificationStatus
import com.cozycards.game.loot.LootboxEngine
import com.cozycards.game.loot.LootboxOdds
import com.cozycards.game.state.PityState
import com.cozycards.levels.LootboxTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CardsAndLootTest {
    private val catalog = Fixtures.catalog
    private val engine = LootboxEngine(catalog)

    @Test
    fun bundledPacksAreValidAndHonest() {
        val seen = HashSet<String>()
        for (p in Fixtures.packs) {
            assertEquals(emptyList<String>(), CardPackValidator.validate(p, seen))
            seen += p.cards.map { it.id }
            for (c in p.cards) {
                // Starter content claims no physical-card data.
                assertTrue(c.verification == VerificationStatus.ORIGINAL_ART)
                assertEquals(null, c.manufacturer); assertEquals(null, c.year); assertEquals(null, c.cardNumber)
                assertEquals(ImageRights.ORIGINAL_ARTWORK, c.imageRights)
            }
        }
        assertEquals(setOf("bts", "spiderman", "tmnt"), catalog.collections.map { it.id }.toSet())
        for (r in Rarity.entries) assertTrue("pool for $r", catalog.pool(r).isNotEmpty())
    }

    @Test
    fun validatorRejectsFakeRealData() {
        val pack = Fixtures.packs.first()
        val bad = pack.copy(cards = pack.cards.mapIndexed { i, c ->
            if (i == 0) c.copy(manufacturer = "Some Maker", year = 2020, cardNumber = "#1") else c
        })
        assertTrue(CardPackValidator.validate(bad).any { "must not carry physical card data" in it })
        val verifiedNoSource = pack.copy(cards = pack.cards.mapIndexed { i, c ->
            if (i == 0) c.copy(verification = VerificationStatus.VERIFIED, manufacturer = "M", year = 2020, cardNumber = "1") else c
        })
        assertTrue(CardPackValidator.validate(verifiedNoSource).any { "cite a source" in it })
        val image = pack.copy(cards = pack.cards.mapIndexed { i, c ->
            if (i == 0) c.copy(frontImage = "photo.jpg", imageRights = ImageRights.UNKNOWN) else c
        })
        assertTrue(CardPackValidator.validate(image).any { "without cleared rights" in it })
    }

    @Test
    fun packRoundTrip() {
        val p = Fixtures.packs[1]
        assertEquals(p, CardCatalog.parsePack(CardCatalog.encodePack(p)))
    }

    @Test
    fun oddsSumToOne() {
        for (t in LootboxTier.entries) assertEquals(1.0, LootboxOdds.oddsFor(t).values.sum(), 1e-9)
    }

    @Test
    fun boxesHaveCorrectSizesAndGuarantees() {
        for (seed in 1L..2000L) {
            for (t in LootboxTier.entries) {
                val r = engine.draw(t, seed, PityState(), { false })
                assertEquals(t.cardCount, r.draws.size)
                LootboxOdds.guarantee(t)?.let { g -> assertTrue("$t seed $seed", r.draws.any { it.rarity.atLeast(g) }) }
            }
        }
    }

    @Test
    fun drawIsDeterministic() {
        val a = engine.draw(LootboxTier.EPIC, 42, PityState(3, 10), { false })
        val b = engine.draw(LootboxTier.EPIC, 42, PityState(3, 10), { false })
        assertEquals(a, b)
    }

    @Test
    fun pityCapsDroughts() {
        var pity = PityState()
        var sinceEpic = 0
        var sinceLegendary = 0
        var maxEpicGap = 0
        var maxLegGap = 0
        for (seed in 1L..4000L) {
            val r = engine.draw(LootboxTier.COMMON, seed, pity, { false })
            for (d in r.draws) {
                sinceEpic = if (d.rarity.atLeast(Rarity.EPIC)) 0 else sinceEpic + 1
                sinceLegendary = if (d.rarity == Rarity.LEGENDARY) 0 else sinceLegendary + 1
                maxEpicGap = maxOf(maxEpicGap, sinceEpic)
                maxLegGap = maxOf(maxLegGap, sinceLegendary)
            }
            pity = r.pity
        }
        assertTrue("epic gap $maxEpicGap", maxEpicGap < LootboxOdds.EPIC_PITY)
        assertTrue("legendary gap $maxLegGap", maxLegGap < LootboxOdds.LEGENDARY_PITY)
    }

    @Test
    fun observedRatesMatchPublishedOddsWithoutPity() {
        val counts = HashMap<Rarity, Int>()
        var total = 0
        for (seed in 1L..20_000L) {
            // Fresh pity each box so the measurement reflects base odds.
            engine.draw(LootboxTier.COMMON, seed, PityState(), { false }).draws.forEach {
                counts.merge(it.rarity, 1, Int::plus); total++
            }
        }
        for ((r, p) in LootboxOdds.oddsFor(LootboxTier.COMMON)) {
            val observed = (counts[r] ?: 0).toDouble() / total
            assertEquals("$r", p, observed, 0.01)
        }
    }

    @Test
    fun collectionBoxesStayInCollection() {
        for (seed in 1L..300L) {
            val r = engine.draw(LootboxTier.RARE, seed, PityState(), { false }, "tmnt")
            assertTrue(r.draws.all { catalog.require(it.cardId).collectionId == "tmnt" })
        }
    }

    @Test
    fun newFlagMarksOnlyFirstCopy() {
        val r = engine.draw(LootboxTier.LEGENDARY, 5, PityState(), { false })
        val seen = HashSet<String>()
        for (d in r.draws) {
            assertEquals(d.cardId !in seen, d.isNew)
            seen += d.cardId
        }
    }
}
