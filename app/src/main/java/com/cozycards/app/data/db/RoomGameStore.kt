package com.cozycards.app.data.db

import com.cozycards.game.economy.BoosterType
import com.cozycards.game.state.CardDraw
import com.cozycards.game.state.CardHistoryEntry
import com.cozycards.game.state.GameStore
import com.cozycards.game.state.GameTx
import com.cozycards.game.state.LevelProgress
import com.cozycards.game.state.OpenedLootbox
import com.cozycards.game.state.OwnedCard
import com.cozycards.game.state.PendingLootbox
import com.cozycards.game.state.RewardLogEntry
import com.cozycards.game.state.SaveData
import com.cozycards.game.state.Wallet
import com.cozycards.levels.LootboxTier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Room-backed [GameStore]. Every [transaction] is one SQLite transaction: if the block throws or
 * the process dies before commit, nothing is written.
 */
class RoomGameStore(private val db: AppDatabase) : GameStore {
    private val dao = db.dao()
    private val mutex = Mutex()
    var afterCommit: (() -> Unit)? = null

    override suspend fun <T> transaction(block: (GameTx) -> T): T = mutex.withLock {
        val result = withContext(Dispatchers.IO) {
            db.runInTransaction<T> { block(RoomTx(dao)) }
        }
        afterCommit?.invoke()
        result
    }

    suspend fun <T> read(block: (GameDao) -> T): T = withContext(Dispatchers.IO) { block(dao) }

    suspend fun exportAll(): SaveData = transaction { _ -> snapshot(dao) }

    suspend fun replaceAll(data: SaveData) = transaction { _ ->
        dao.clearWallet(); dao.clearLedger(); dao.clearLevels(); dao.clearCards(); dao.clearHistory()
        dao.clearLootboxes(); dao.clearOpened(); dao.clearBoosters(); dao.clearMeta(); dao.clearRewardLog()
        val tx = RoomTx(dao)
        tx.setWallet(data.wallet)
        data.ledger.forEach { (k, v) -> dao.insertLedger(LedgerEntity(k, v)) }
        data.levels.forEach { tx.putLevelProgress(it) }
        data.cards.forEach { tx.putOwnedCard(it) }
        data.history.forEach { tx.addCardHistory(it) }
        data.lootboxes.forEach { tx.addLootbox(it) }
        data.opened.forEach { tx.addOpenedLootbox(it) }
        data.boosters.forEach { (k, v) -> dao.putBooster(BoosterEntity(k, v.coerceAtLeast(0))) }
        data.meta.forEach { (k, v) -> dao.putMeta(MetaEntity(k, v)) }
        data.rewardLog.forEach { tx.addRewardLog(it) }
    }

    companion object {
        private val drawsSerializer = ListSerializer(CardDraw.serializer())
        val json = Json { ignoreUnknownKeys = true }

        fun decodeDraws(text: String): List<CardDraw> = runCatching { json.decodeFromString(drawsSerializer, text) }.getOrDefault(emptyList())

        fun snapshot(dao: GameDao): SaveData = SaveData(
            version = SaveData.CURRENT_VERSION,
            wallet = dao.wallet()?.let { Wallet(it.coins, it.tokens) } ?: Wallet(),
            ledger = dao.allLedger().associate { it.key to it.claimedAt },
            levels = dao.allLevels().map { it.toModel() },
            cards = dao.allCards().map { it.toModel() },
            history = dao.allHistory().map { CardHistoryEntry(it.cardId, it.at, it.source) },
            lootboxes = dao.lootboxes().map { it.toModel() },
            opened = dao.allOpened().map { it.toModel() },
            boosters = dao.allBoosters().associate { it.type to it.count },
            meta = dao.allMeta().associate { it.key to it.value },
            rewardLog = dao.allRewardLog().map { RewardLogEntry(it.key, it.at, it.description, it.coins, it.tokens) },
        )

        fun LevelProgressEntity.toModel() = LevelProgress(levelId, bestStars, bestScore, plays, wins, firstClearedAt)
        fun OwnedCardEntity.toModel() = OwnedCard(cardId, count, firstObtainedAt, lastObtainedAt, favorite)
        fun LootboxEntity.toModel() = PendingLootbox(id, LootboxTier.valueOf(tier), seed, source, grantedAt, collectionId)
        fun OpenedLootboxEntity.toModel() = OpenedLootbox(boxId, LootboxTier.valueOf(tier), source, openedAt, decodeDraws(drawsJson))

        fun encodeDraws(d: List<CardDraw>) = json.encodeToString(drawsSerializer, d)
    }

    private class RoomTx(private val dao: GameDao) : GameTx {
        override fun wallet() = dao.wallet()?.let { Wallet(it.coins, it.tokens) } ?: Wallet()
        override fun setWallet(wallet: Wallet) = dao.putWallet(WalletEntity(0, wallet.coins, wallet.tokens))
        override fun claimOnce(key: String, at: Long) = dao.insertLedger(LedgerEntity(key, at)) != -1L
        override fun isClaimed(key: String) = dao.ledgerCount(key) > 0
        override fun claimedKeys(prefix: String) = dao.ledgerKeys(prefix)
        override fun levelProgress(levelId: String) = dao.level(levelId)?.toModel()
        override fun putLevelProgress(progress: LevelProgress) = with(progress) {
            dao.putLevel(LevelProgressEntity(levelId, bestStars, bestScore, plays, wins, firstClearedAt))
        }
        override fun ownedCard(cardId: String) = dao.card(cardId)?.toModel()
        override fun putOwnedCard(card: OwnedCard) {
            require(card.count >= 0)
            if (card.count == 0) dao.deleteCard(card.cardId)
            else dao.putCard(OwnedCardEntity(card.cardId, card.count, card.firstObtainedAt, card.lastObtainedAt, card.favorite))
        }
        override fun allOwnedCards() = dao.allCards().map { it.toModel() }
        override fun addCardHistory(entry: CardHistoryEntry) = dao.insertHistory(CardHistoryEntity(0, entry.cardId, entry.at, entry.source))
        override fun lootboxes() = dao.lootboxes().map { it.toModel() }
        override fun addLootbox(box: PendingLootbox) =
            dao.insertLootbox(LootboxEntity(box.id, box.tier.name, box.seed, box.source, box.grantedAt, box.collectionId))
        override fun removeLootbox(id: Long) = dao.deleteLootbox(id) > 0
        override fun addOpenedLootbox(opened: OpenedLootbox) =
            dao.insertOpened(OpenedLootboxEntity(opened.boxId, opened.tier.name, opened.source, opened.openedAt, encodeDraws(opened.draws)))
        override fun boosterCount(type: BoosterType) = dao.booster(type.name)?.count ?: 0
        override fun setBoosterCount(type: BoosterType, count: Int) {
            require(count >= 0) { "booster count can't be negative" }
            dao.putBooster(BoosterEntity(type.name, count))
        }
        override fun meta(key: String) = dao.meta(key)
        override fun setMeta(key: String, value: String) = dao.putMeta(MetaEntity(key, value))
        override fun addRewardLog(entry: RewardLogEntry) =
            dao.insertRewardLog(RewardLogEntity(0, entry.key, entry.at, entry.description, entry.coins, entry.tokens))
    }
}
