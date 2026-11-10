package com.cozycards.game.state

import com.cozycards.game.cards.Rarity
import com.cozycards.levels.LootboxTier
import kotlinx.serialization.Serializable

@Serializable
data class Wallet(val coins: Long = 0, val tokens: Long = 0) {
    init {
        require(coins >= 0 && tokens >= 0) { "Wallet balances can never be negative" }
    }
}

@Serializable
data class LevelProgress(
    val levelId: String,
    val bestStars: Int = 0,
    val bestScore: Int = 0,
    val plays: Int = 0,
    val wins: Int = 0,
    val firstClearedAt: Long? = null,
)

@Serializable
data class OwnedCard(
    val cardId: String,
    val count: Int,
    val firstObtainedAt: Long,
    val lastObtainedAt: Long,
    val favorite: Boolean = false,
) {
    val duplicates get() = (count - 1).coerceAtLeast(0)
}

@Serializable
data class PendingLootbox(
    val id: Long,
    val tier: LootboxTier,
    /** Fixed when the box is granted so reopening after a crash can't reroll it. */
    val seed: Long,
    val source: String,
    val grantedAt: Long,
    /** Optional: restrict drops to one collection (shop boxes). */
    val collectionId: String? = null,
)

@Serializable
data class CardDraw(val cardId: String, val rarity: Rarity, val isNew: Boolean, val pityTriggered: Boolean = false, val guaranteed: Boolean = false)

@Serializable
data class OpenedLootbox(val boxId: Long, val tier: LootboxTier, val source: String, val openedAt: Long, val draws: List<CardDraw>)

@Serializable
data class CardHistoryEntry(val cardId: String, val at: Long, val source: String)

@Serializable
data class RewardLogEntry(val key: String, val at: Long, val description: String, val coins: Long = 0, val tokens: Long = 0)

@Serializable
data class PityState(val sinceEpicPlus: Int = 0, val sinceLegendary: Int = 0)
