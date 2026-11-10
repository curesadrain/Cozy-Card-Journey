package com.cozycards.game.state

import com.cozycards.game.economy.BoosterType
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Reference [GameStore] implementation. Each transaction works on a copy of the data and the
 * copy replaces the committed state only when the block completes without throwing.
 * Used by unit tests and as the behavioural specification of the Room store.
 */
class InMemoryGameStore(initial: SaveData = SaveData()) : GameStore {
    private val mutex = Mutex()
    var data: SaveData = initial
        private set

    /** Test hook: throw after the block to simulate a crash before commit. */
    var failBeforeCommit = false

    override suspend fun <T> transaction(block: (GameTx) -> T): T = mutex.withLock {
        val tx = Tx(data)
        val result = block(tx)
        if (failBeforeCommit) throw IllegalStateException("simulated crash before commit")
        data = tx.build()
        result
    }

    private class Tx(src: SaveData) : GameTx {
        var currentWallet = src.wallet
        val ledger = LinkedHashMap(src.ledger)
        val levels = LinkedHashMap(src.levels.associateBy { it.levelId })
        val cards = LinkedHashMap(src.cards.associateBy { it.cardId })
        val history = ArrayList(src.history)
        val boxes = ArrayList(src.lootboxes)
        val opened = ArrayList(src.opened)
        val boosters = LinkedHashMap(src.boosters)
        val meta = LinkedHashMap(src.meta)
        val rewardLog = ArrayList(src.rewardLog)

        fun build() = SaveData(
            version = SaveData.CURRENT_VERSION,
            wallet = currentWallet,
            ledger = ledger,
            levels = levels.values.toList(),
            cards = cards.values.toList(),
            history = history,
            lootboxes = boxes,
            opened = opened,
            boosters = boosters,
            meta = meta,
            rewardLog = rewardLog,
        )

        override fun wallet() = currentWallet
        override fun setWallet(wallet: Wallet) { currentWallet = wallet }
        override fun claimOnce(key: String, at: Long): Boolean {
            if (key in ledger) return false
            ledger[key] = at
            return true
        }
        override fun isClaimed(key: String) = key in ledger
        override fun claimedKeys(prefix: String) = ledger.keys.filter { it.startsWith(prefix) }
        override fun levelProgress(levelId: String) = levels[levelId]
        override fun putLevelProgress(progress: LevelProgress) { levels[progress.levelId] = progress }
        override fun ownedCard(cardId: String) = cards[cardId]
        override fun putOwnedCard(card: OwnedCard) {
            require(card.count >= 0)
            if (card.count == 0) cards.remove(card.cardId) else cards[card.cardId] = card
        }
        override fun allOwnedCards() = cards.values.toList()
        override fun addCardHistory(entry: CardHistoryEntry) { history += entry }
        override fun lootboxes() = boxes.toList()
        override fun addLootbox(box: PendingLootbox) {
            require(boxes.none { it.id == box.id }) { "duplicate lootbox id ${box.id}" }
            boxes += box
        }
        override fun removeLootbox(id: Long) = boxes.removeIf { it.id == id }
        override fun addOpenedLootbox(opened: OpenedLootbox) { this.opened += opened }
        override fun boosterCount(type: BoosterType) = boosters[type.name] ?: 0
        override fun setBoosterCount(type: BoosterType, count: Int) {
            require(count >= 0) { "booster count can't be negative" }
            boosters[type.name] = count
        }
        override fun meta(key: String) = meta[key]
        override fun setMeta(key: String, value: String) { meta[key] = value }
        override fun addRewardLog(entry: RewardLogEntry) { rewardLog += entry }
    }
}
