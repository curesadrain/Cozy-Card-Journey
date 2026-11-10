package com.cozycards.app.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "wallet")
data class WalletEntity(@PrimaryKey val id: Int = 0, val coins: Long, val tokens: Long)

@Entity(tableName = "ledger")
data class LedgerEntity(@PrimaryKey val key: String, val claimedAt: Long)

@Entity(tableName = "level_progress")
data class LevelProgressEntity(
    @PrimaryKey val levelId: String,
    val bestStars: Int,
    val bestScore: Int,
    val plays: Int,
    val wins: Int,
    val firstClearedAt: Long?,
)

@Entity(tableName = "owned_cards")
data class OwnedCardEntity(
    @PrimaryKey val cardId: String,
    val count: Int,
    val firstObtainedAt: Long,
    val lastObtainedAt: Long,
    val favorite: Boolean,
)

@Entity(tableName = "card_history", indices = [Index("cardId")])
data class CardHistoryEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val cardId: String, val at: Long, val source: String)

@Entity(tableName = "lootboxes")
data class LootboxEntity(
    @PrimaryKey val id: Long,
    val tier: String,
    val seed: Long,
    val source: String,
    val grantedAt: Long,
    val collectionId: String?,
)

@Entity(tableName = "opened_lootboxes")
data class OpenedLootboxEntity(@PrimaryKey val boxId: Long, val tier: String, val source: String, val openedAt: Long, val drawsJson: String)

@Entity(tableName = "boosters")
data class BoosterEntity(@PrimaryKey val type: String, val count: Int)

@Entity(tableName = "meta")
data class MetaEntity(@PrimaryKey val key: String, val value: String)

@Entity(tableName = "reward_log")
data class RewardLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val key: String,
    val at: Long,
    val description: String,
    val coins: Long,
    val tokens: Long,
)

/** Blocking DAO: only called inside a transaction on a background thread. */
@Dao
interface GameDao {
    @Query("SELECT * FROM wallet WHERE id = 0") fun wallet(): WalletEntity?
    @Upsert fun putWallet(w: WalletEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE) fun insertLedger(e: LedgerEntity): Long
    @Query("SELECT COUNT(*) FROM ledger WHERE `key` = :key") fun ledgerCount(key: String): Int
    @Query("SELECT `key` FROM ledger WHERE `key` LIKE :prefix || '%'") fun ledgerKeys(prefix: String): List<String>
    @Query("SELECT * FROM ledger") fun allLedger(): List<LedgerEntity>

    @Query("SELECT * FROM level_progress WHERE levelId = :id") fun level(id: String): LevelProgressEntity?
    @Upsert fun putLevel(e: LevelProgressEntity)
    @Query("SELECT * FROM level_progress") fun allLevels(): List<LevelProgressEntity>

    @Query("SELECT * FROM owned_cards WHERE cardId = :id") fun card(id: String): OwnedCardEntity?
    @Upsert fun putCard(e: OwnedCardEntity)
    @Query("DELETE FROM owned_cards WHERE cardId = :id") fun deleteCard(id: String)
    @Query("SELECT * FROM owned_cards") fun allCards(): List<OwnedCardEntity>

    @Insert fun insertHistory(e: CardHistoryEntity)
    @Query("SELECT * FROM card_history ORDER BY id") fun allHistory(): List<CardHistoryEntity>
    @Query("SELECT * FROM card_history WHERE cardId = :id ORDER BY id DESC") fun historyFor(id: String): List<CardHistoryEntity>

    @Query("SELECT * FROM lootboxes ORDER BY id") fun lootboxes(): List<LootboxEntity>
    @Insert(onConflict = OnConflictStrategy.ABORT) fun insertLootbox(e: LootboxEntity)
    @Query("DELETE FROM lootboxes WHERE id = :id") fun deleteLootbox(id: Long): Int

    @Insert(onConflict = OnConflictStrategy.ABORT) fun insertOpened(e: OpenedLootboxEntity)
    @Query("SELECT * FROM opened_lootboxes ORDER BY openedAt DESC, boxId DESC") fun allOpened(): List<OpenedLootboxEntity>
    @Query("SELECT * FROM opened_lootboxes ORDER BY openedAt DESC, boxId DESC LIMIT :n") fun recentOpened(n: Int): List<OpenedLootboxEntity>

    @Query("SELECT * FROM boosters WHERE type = :type") fun booster(type: String): BoosterEntity?
    @Upsert fun putBooster(e: BoosterEntity)
    @Query("SELECT * FROM boosters") fun allBoosters(): List<BoosterEntity>

    @Query("SELECT value FROM meta WHERE `key` = :key") fun meta(key: String): String?
    @Upsert fun putMeta(e: MetaEntity)
    @Query("SELECT * FROM meta") fun allMeta(): List<MetaEntity>

    @Insert fun insertRewardLog(e: RewardLogEntity)
    @Query("SELECT * FROM reward_log ORDER BY id") fun allRewardLog(): List<RewardLogEntity>
    @Query("SELECT * FROM reward_log ORDER BY id DESC LIMIT :n") fun recentRewardLog(n: Int): List<RewardLogEntity>

    @Query("DELETE FROM wallet") fun clearWallet()
    @Query("DELETE FROM ledger") fun clearLedger()
    @Query("DELETE FROM level_progress") fun clearLevels()
    @Query("DELETE FROM owned_cards") fun clearCards()
    @Query("DELETE FROM card_history") fun clearHistory()
    @Query("DELETE FROM lootboxes") fun clearLootboxes()
    @Query("DELETE FROM opened_lootboxes") fun clearOpened()
    @Query("DELETE FROM boosters") fun clearBoosters()
    @Query("DELETE FROM meta") fun clearMeta()
    @Query("DELETE FROM reward_log") fun clearRewardLog()
}

@Database(
    entities = [
        WalletEntity::class, LedgerEntity::class, LevelProgressEntity::class, OwnedCardEntity::class,
        CardHistoryEntity::class, LootboxEntity::class, OpenedLootboxEntity::class, BoosterEntity::class,
        MetaEntity::class, RewardLogEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): GameDao

    companion object {
        const val NAME = "cozy_cards.db"

        /**
         * v1 → v2: cards gained a "favorite" flag and rewards got an audit log.
         * Existing progress is preserved.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE owned_cards ADD COLUMN favorite INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `reward_log` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`key` TEXT NOT NULL, `at` INTEGER NOT NULL, `description` TEXT NOT NULL, `coins` INTEGER NOT NULL, `tokens` INTEGER NOT NULL)"
                )
            }
        }

        val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2)

        /** Exact v1 schema, used by the migration test to build an old database. */
        val V1_SCHEMA = listOf(
            "CREATE TABLE IF NOT EXISTS `wallet` (`id` INTEGER NOT NULL, `coins` INTEGER NOT NULL, `tokens` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `ledger` (`key` TEXT NOT NULL, `claimedAt` INTEGER NOT NULL, PRIMARY KEY(`key`))",
            "CREATE TABLE IF NOT EXISTS `level_progress` (`levelId` TEXT NOT NULL, `bestStars` INTEGER NOT NULL, `bestScore` INTEGER NOT NULL, `plays` INTEGER NOT NULL, `wins` INTEGER NOT NULL, `firstClearedAt` INTEGER, PRIMARY KEY(`levelId`))",
            "CREATE TABLE IF NOT EXISTS `owned_cards` (`cardId` TEXT NOT NULL, `count` INTEGER NOT NULL, `firstObtainedAt` INTEGER NOT NULL, `lastObtainedAt` INTEGER NOT NULL, PRIMARY KEY(`cardId`))",
            "CREATE TABLE IF NOT EXISTS `card_history` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `cardId` TEXT NOT NULL, `at` INTEGER NOT NULL, `source` TEXT NOT NULL)",
            "CREATE INDEX IF NOT EXISTS `index_card_history_cardId` ON `card_history` (`cardId`)",
            "CREATE TABLE IF NOT EXISTS `lootboxes` (`id` INTEGER NOT NULL, `tier` TEXT NOT NULL, `seed` INTEGER NOT NULL, `source` TEXT NOT NULL, `grantedAt` INTEGER NOT NULL, `collectionId` TEXT, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `opened_lootboxes` (`boxId` INTEGER NOT NULL, `tier` TEXT NOT NULL, `source` TEXT NOT NULL, `openedAt` INTEGER NOT NULL, `drawsJson` TEXT NOT NULL, PRIMARY KEY(`boxId`))",
            "CREATE TABLE IF NOT EXISTS `boosters` (`type` TEXT NOT NULL, `count` INTEGER NOT NULL, PRIMARY KEY(`type`))",
            "CREATE TABLE IF NOT EXISTS `meta` (`key` TEXT NOT NULL, `value` TEXT NOT NULL, PRIMARY KEY(`key`))",
        )
    }
}
