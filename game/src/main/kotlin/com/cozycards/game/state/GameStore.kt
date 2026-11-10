package com.cozycards.game.state

import com.cozycards.game.economy.BoosterType

/**
 * Mutation view inside one atomic transaction. Either every change made through a [GameTx]
 * is committed, or none is (an exception rolls everything back).
 */
interface GameTx {
    fun wallet(): Wallet
    fun setWallet(wallet: Wallet)

    /** Idempotency ledger. Returns true only the first time a key is claimed. */
    fun claimOnce(key: String, at: Long): Boolean
    fun isClaimed(key: String): Boolean
    fun claimedKeys(prefix: String): List<String>

    fun levelProgress(levelId: String): LevelProgress?
    fun putLevelProgress(progress: LevelProgress)

    fun ownedCard(cardId: String): OwnedCard?
    fun putOwnedCard(card: OwnedCard)
    fun allOwnedCards(): List<OwnedCard>
    fun addCardHistory(entry: CardHistoryEntry)

    fun lootboxes(): List<PendingLootbox>
    fun addLootbox(box: PendingLootbox)
    fun removeLootbox(id: Long): Boolean
    fun addOpenedLootbox(opened: OpenedLootbox)

    fun boosterCount(type: BoosterType): Int
    fun setBoosterCount(type: BoosterType, count: Int)

    /** Small key/value store for counters, pity, stats, quest progress, cosmetics. */
    fun meta(key: String): String?
    fun setMeta(key: String, value: String)

    fun addRewardLog(entry: RewardLogEntry)
}

interface GameStore {
    suspend fun <T> transaction(block: (GameTx) -> T): T
}

// Typed helpers over the meta store -------------------------------------------------------

fun GameTx.metaLong(key: String, default: Long = 0): Long = meta(key)?.toLongOrNull() ?: default
fun GameTx.metaInt(key: String, default: Int = 0): Int = meta(key)?.toIntOrNull() ?: default
fun GameTx.setMetaLong(key: String, value: Long) = setMeta(key, value.toString())
fun GameTx.incrementMeta(key: String, by: Long = 1): Long {
    val v = metaLong(key) + by
    setMetaLong(key, v)
    return v
}

/** Monotonic id sequence stored in meta. */
fun GameTx.nextId(sequence: String): Long = incrementMeta("seq.$sequence")

fun GameTx.addCoins(amount: Long) {
    require(amount >= 0)
    val w = wallet()
    setWallet(w.copy(coins = w.coins + amount))
}

fun GameTx.addTokens(amount: Long) {
    require(amount >= 0)
    val w = wallet()
    setWallet(w.copy(tokens = w.tokens + amount))
}

class InsufficientFundsException(message: String) : IllegalStateException(message)

fun GameTx.spend(coins: Long = 0, tokens: Long = 0) {
    require(coins >= 0 && tokens >= 0)
    val w = wallet()
    if (w.coins < coins) throw InsufficientFundsException("Need $coins coins, have ${w.coins}")
    if (w.tokens < tokens) throw InsufficientFundsException("Need $tokens tokens, have ${w.tokens}")
    setWallet(Wallet(w.coins - coins, w.tokens - tokens))
}
