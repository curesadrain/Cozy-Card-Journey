package com.cozycards.game.loot

import com.cozycards.engine.Rng
import com.cozycards.game.cards.CardCatalog
import com.cozycards.game.cards.Rarity
import com.cozycards.game.state.CardDraw
import com.cozycards.game.state.PityState
import com.cozycards.levels.LootboxTier

/**
 * Transparent drop rules. Every number here is shown to the player in the odds screen.
 *
 * Per card: rarity is rolled from [baseOdds]. Hard pity: after [EPIC_PITY] cards in a row
 * without Epic-or-better the next card is at least Epic; after [LEGENDARY_PITY] cards without a
 * Legendary the next card is Legendary. Box guarantees (Epic box: ≥1 Epic+, Legendary box:
 * ≥1 Legendary) upgrade the last card if no earlier card met them. Within a rarity every
 * eligible card is equally likely.
 */
object LootboxOdds {
    const val EPIC_PITY = 25
    const val LEGENDARY_PITY = 80

    val baseOdds: Map<Rarity, Double> = linkedMapOf(
        Rarity.COMMON to 0.68,
        Rarity.RARE to 0.24,
        Rarity.EPIC to 0.065,
        Rarity.LEGENDARY to 0.015,
    )

    fun guarantee(tier: LootboxTier): Rarity? = when (tier) {
        LootboxTier.COMMON, LootboxTier.RARE -> null
        LootboxTier.EPIC -> Rarity.EPIC
        LootboxTier.LEGENDARY -> Rarity.LEGENDARY
    }

    /** Rare boxes shift odds slightly towards rarer cards. Documented in the UI. */
    fun oddsFor(tier: LootboxTier): Map<Rarity, Double> = when (tier) {
        LootboxTier.COMMON -> baseOdds
        LootboxTier.RARE -> linkedMapOf(Rarity.COMMON to 0.60, Rarity.RARE to 0.30, Rarity.EPIC to 0.08, Rarity.LEGENDARY to 0.02)
        LootboxTier.EPIC -> linkedMapOf(Rarity.COMMON to 0.55, Rarity.RARE to 0.32, Rarity.EPIC to 0.10, Rarity.LEGENDARY to 0.03)
        LootboxTier.LEGENDARY -> linkedMapOf(Rarity.COMMON to 0.50, Rarity.RARE to 0.32, Rarity.EPIC to 0.13, Rarity.LEGENDARY to 0.05)
    }

    fun describe(tier: LootboxTier): String = buildString {
        append("${tier.cardCount} cards. ")
        oddsFor(tier).forEach { (r, p) -> append("${r.label} %.1f%%  ".format(p * 100)) }
        guarantee(tier)?.let { append("\nGuaranteed: at least one ${it.label}${if (it == Rarity.EPIC) " or better" else ""}.") }
        append("\nPity: Epic+ at the latest every $EPIC_PITY cards, Legendary every $LEGENDARY_PITY cards.")
    }
}

data class DrawResult(val draws: List<CardDraw>, val pity: PityState)

class LootboxEngine(private val catalog: CardCatalog) {

    /**
     * Pure function: same inputs → same cards. [owned] decides the "new" flag only.
     */
    fun draw(tier: LootboxTier, seed: Long, pity: PityState, owned: (String) -> Boolean, collectionId: String? = null): DrawResult {
        val rng = Rng(seed)
        val odds = LootboxOdds.oddsFor(tier)
        val guarantee = LootboxOdds.guarantee(tier)
        var p = pity
        val newThisBox = HashSet<String>()
        val out = mutableListOf<CardDraw>()
        for (i in 0 until tier.cardCount) {
            var rarity = roll(odds, rng)
            var pityHit = false
            var guaranteed = false
            if (p.sinceLegendary + 1 >= LootboxOdds.LEGENDARY_PITY && rarity != Rarity.LEGENDARY) {
                rarity = Rarity.LEGENDARY; pityHit = true
            } else if (p.sinceEpicPlus + 1 >= LootboxOdds.EPIC_PITY && !rarity.atLeast(Rarity.EPIC)) {
                rarity = Rarity.EPIC; pityHit = true
            }
            val isLast = i == tier.cardCount - 1
            if (isLast && guarantee != null && out.none { it.rarity.atLeast(guarantee) } && !rarity.atLeast(guarantee)) {
                rarity = guarantee; guaranteed = true
            }
            val card = pickCard(rarity, rng, collectionId)
            val isNew = !owned(card.id) && card.id !in newThisBox
            if (isNew) newThisBox += card.id
            out += CardDraw(card.id, card.gameRarity, isNew, pityHit, guaranteed)
            p = PityState(
                sinceEpicPlus = if (card.gameRarity.atLeast(Rarity.EPIC)) 0 else p.sinceEpicPlus + 1,
                sinceLegendary = if (card.gameRarity == Rarity.LEGENDARY) 0 else p.sinceLegendary + 1,
            )
        }
        return DrawResult(out, p)
    }

    private fun roll(odds: Map<Rarity, Double>, rng: Rng): Rarity {
        val x = rng.nextDouble()
        var acc = 0.0
        for ((r, w) in odds) {
            acc += w
            if (x < acc) return r
        }
        return odds.keys.first()
    }

    /** Picks uniformly within the rarity; falls back to the nearest available rarity. */
    private fun pickCard(rarity: Rarity, rng: Rng, collectionId: String?) = run {
        val order = listOf(rarity) + Rarity.entries.filter { it != rarity }.sortedBy { Math.abs(it.ordinal - rarity.ordinal) * 2 + if (it.ordinal > rarity.ordinal) 1 else 0 }
        for (r in order) {
            val pool = catalog.pool(r, collectionId)
            if (pool.isNotEmpty()) return@run rng.pick(pool)
        }
        error("Card catalog is empty")
    }
}
