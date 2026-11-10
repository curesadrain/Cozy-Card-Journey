package com.cozycards.game.economy

import com.cozycards.game.cards.Rarity
import com.cozycards.levels.LootboxTier

enum class BoosterKind { PRE_LEVEL, IN_LEVEL }

/** All boosters. Each maps to a concrete Match3Game operation (see the app's level controller). */
enum class BoosterType(val title: String, val description: String, val price: Long, val kind: BoosterKind, val starterAmount: Int) {
    ROCKET("Rocket", "Start the level with a rocket on the board.", 120, BoosterKind.PRE_LEVEL, 3),
    BOMB("Bomb", "Start the level with a bomb on the board.", 150, BoosterKind.PRE_LEVEL, 3),
    RAINBOW("Rainbow Sphere", "Start the level with a rainbow sphere.", 260, BoosterKind.PRE_LEVEL, 1),
    ROCKET_BOMB("Rocket + Bomb", "Start with both a rocket and a bomb.", 240, BoosterKind.PRE_LEVEL, 1),
    EXTRA_MOVES("Extra Moves", "+5 moves for this level.", 200, BoosterKind.PRE_LEVEL, 2),
    HAMMER("Hammer", "Smash any single tile or obstacle layer. No move used.", 140, BoosterKind.IN_LEVEL, 3),
    SWAP_GLOVE("Swap Glove", "Swap any two neighbours, even without a match. No move used.", 160, BoosterKind.IN_LEVEL, 2),
    SHUFFLE("Shuffle", "Rearrange the tiles. No move used.", 90, BoosterKind.IN_LEVEL, 2),
    SUPER_HAMMER("Super Hammer", "Three heavy hits on one cell and a hit on its neighbours.", 260, BoosterKind.IN_LEVEL, 1),
    AREA_CLEAR("Area Clear", "Blast a 3×3 area.", 300, BoosterKind.IN_LEVEL, 1);
}

object Economy {
    /** Tokens gained per recycled duplicate. */
    fun recycleValue(r: Rarity): Long = when (r) {
        Rarity.COMMON -> 2
        Rarity.RARE -> 6
        Rarity.EPIC -> 20
        Rarity.LEGENDARY -> 60
    }

    /** Tokens needed to exchange for a specific missing card. ~10x recycle value. */
    fun exchangePrice(r: Rarity): Long = when (r) {
        Rarity.COMMON -> 20
        Rarity.RARE -> 60
        Rarity.EPIC -> 200
        Rarity.LEGENDARY -> 600
    }

    const val REPLAY_COINS = 15L
    const val COINS_PER_STAR = 5L
    const val COINS_PER_MOVE_LEFT = 2L
    const val ALBUM_COMPLETE_TOKENS = 40L
    const val ALBUM_COMPLETE_COINS = 300L

    fun chapterCompleteCoins(chapter: Int) = 250L + chapter * 25L
    fun chapterCompleteBox(chapter: Int) = if (chapter % 5 == 0) LootboxTier.LEGENDARY else LootboxTier.EPIC
}

enum class CosmeticSlot { CARD_BACK, BOARD_THEME, CARD_FRAME }

enum class Cosmetic(val slot: CosmeticSlot, val title: String, val description: String, val coins: Long, val tokens: Long) {
    BACK_CLASSIC(CosmeticSlot.CARD_BACK, "Classic Back", "Cream paper with a gold emblem.", 0, 0),
    BACK_SAKURA(CosmeticSlot.CARD_BACK, "Sakura Back", "Pink blossoms drifting across the back.", 900, 0),
    BACK_LANTERN(CosmeticSlot.CARD_BACK, "Night Lantern Back", "Deep navy with glowing lanterns.", 1200, 0),
    BACK_WAVE(CosmeticSlot.CARD_BACK, "Ocean Wave Back", "Indigo waves in a woodblock style.", 0, 120),
    BACK_GOLD(CosmeticSlot.CARD_BACK, "Gold Leaf Back", "Black lacquer with gold leaf.", 0, 300),
    BOARD_DEFAULT(CosmeticSlot.BOARD_THEME, "Chapter Theme", "Board follows the chapter's look.", 0, 0),
    BOARD_PAPER(CosmeticSlot.BOARD_THEME, "Washi Paper", "Warm handmade paper board.", 700, 0),
    BOARD_NIGHT(CosmeticSlot.BOARD_THEME, "Night Market", "Dark board with lantern glow.", 1000, 0),
    BOARD_PASTEL(CosmeticSlot.BOARD_THEME, "Pastel Cafe", "Soft milk-tea pastels.", 800, 0),
    FRAME_STANDARD(CosmeticSlot.CARD_FRAME, "Standard Frames", "Rarity-coloured frames.", 0, 0),
    FRAME_LACQUER(CosmeticSlot.CARD_FRAME, "Lacquer Frames", "Glossy lacquer rims on every card.", 0, 200),
    ;

    val isFree get() = coins == 0L && tokens == 0L

    companion object {
        fun defaultFor(slot: CosmeticSlot) = entries.first { it.slot == slot && it.isFree }
    }
}

/** Items sold in the shop. Lootbox items show their odds in the UI. */
sealed class ShopItem(val id: String, val title: String, val description: String, val coins: Long, val tokens: Long) {
    class Booster(val type: BoosterType, val amount: Int) :
        ShopItem("booster_${type.name.lowercase()}_$amount", "${type.title} ×$amount", type.description, type.price * amount * (if (amount >= 5) 85 else 100) / 100, 0)

    class Box(val tier: LootboxTier, val collectionId: String?, coins: Long, tokens: Long, title: String) :
        ShopItem("box_${tier.name.lowercase()}_${collectionId ?: "any"}_${if (coins > 0) "c" else "t"}", title, "${tier.cardCount} cards" + if (collectionId != null) " from one collection" else "", coins, tokens)

    class Style(val cosmetic: Cosmetic) : ShopItem("cosmetic_${cosmetic.name.lowercase()}", cosmetic.title, cosmetic.description, cosmetic.coins, cosmetic.tokens)

    class CoinPack(val coinsOut: Long, tokensIn: Long) : ShopItem("tokens_to_coins_$tokensIn", "$coinsOut coins", "Trade collector tokens for coins.", 0, tokensIn)
}

object ShopCatalog {
    fun items(collectionIds: List<String>): List<ShopItem> = buildList {
        BoosterType.entries.forEach { add(ShopItem.Booster(it, 1)); add(ShopItem.Booster(it, 5)) }
        add(ShopItem.Box(LootboxTier.RARE, null, 700, 0, "Rare Box"))
        add(ShopItem.Box(LootboxTier.EPIC, null, 1800, 0, "Epic Box"))
        add(ShopItem.Box(LootboxTier.EPIC, null, 0, 160, "Collector's Epic Box"))
        add(ShopItem.Box(LootboxTier.LEGENDARY, null, 0, 450, "Collector's Legendary Box"))
        collectionIds.forEach { add(ShopItem.Box(LootboxTier.RARE, it, 0, 70, "Focused Box")) }
        Cosmetic.entries.filter { !it.isFree }.forEach { add(ShopItem.Style(it)) }
        add(ShopItem.CoinPack(300, 30))
    }
}
