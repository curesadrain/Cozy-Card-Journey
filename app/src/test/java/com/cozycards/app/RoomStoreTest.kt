package com.cozycards.app

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.cozycards.app.data.db.AppDatabase
import com.cozycards.app.data.db.RoomGameStore
import com.cozycards.game.GameService
import com.cozycards.game.LevelResult
import com.cozycards.game.cards.CardCatalog
import com.cozycards.game.progression.GameClock
import com.cozycards.game.state.InMemoryGameStore
import com.cozycards.game.state.OwnedCard
import com.cozycards.levels.LevelCatalog
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

object TestClock : GameClock {
    var t = 1_762_800_000_000L
    override fun now() = t
    override fun zoneOffsetMillis() = 0L
}

/** Runs the shared game rules against the real Room store. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class RoomStoreTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var store: RoomGameStore
    private lateinit var service: GameService

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        store = RoomGameStore(db)
        val catalog = CardCatalog(context.assets.list("cards")!!.map { CardCatalog.parsePack(context.assets.open("cards/$it").bufferedReader().readText()) })
        val levels = LevelCatalog.loadAll { context.assets.open("levels/$it").bufferedReader().readText() }
        service = GameService(store, catalog, levels, TestClock)
    }

    @After
    fun tearDown() = db.close()

    private fun win(id: String) = LevelResult(id, true, 3, 1000, 4, 100, 3, 0, 5)

    @Test
    fun firstClearIsIdempotentInRoom() = runBlocking {
        service.initialize()
        service.initialize()
        val r1 = service.completeLevel(win("c01_l01"))
        val r2 = service.completeLevel(win("c01_l01"))
        assertTrue(r1.firstClear)
        assertFalse(r2.firstClear)
        val data = store.exportAll()
        assertEquals(1, data.lootboxes.size)
        assertEquals(1, data.ledger.keys.count { it == "level.first.c01_l01" })
        assertEquals(1, data.ledger.keys.count { it == "init.starter" })
    }

    @Test
    fun exceptionRollsBackRoomTransaction() = runBlocking {
        service.initialize()
        val before = store.exportAll()
        val result = runCatching {
            store.transaction { tx ->
                tx.setWallet(tx.wallet().copy(coins = 999_999))
                tx.claimOnce("level.first.c01_l01", 1)
                tx.putOwnedCard(OwnedCard("bts_001", 5, 0, 0))
                error("crash in the middle of granting rewards")
            }
        }
        assertTrue(result.isFailure)
        assertEquals(before, store.exportAll())
    }

    @Test
    fun lootboxOpeningIsAtomicAndSingleUse() = runBlocking {
        service.initialize()
        val box = service.completeLevel(win("c01_l01")).lootboxes.single()
        val opened = service.openLootbox(box.id)
        assertEquals(3, opened.draws.size)
        assertTrue(runCatching { service.openLootbox(box.id) }.isFailure)
        val data = store.exportAll()
        assertEquals(3, data.cards.sumOf { it.count })
        assertEquals(1, data.opened.size)
        assertEquals(opened.draws, data.opened.single().draws)
    }

    @Test
    fun exportReplaceRoundTripMatchesInMemoryStore() = runBlocking {
        service.initialize()
        for (n in 1..12) service.completeLevel(win(com.cozycards.levels.Chapters.levelId(n)))
        service.openAll()
        service.recycleSafeDuplicates()
        val snapshot = store.exportAll()
        // A second Room database restored from the snapshot holds identical data.
        val db2 = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val store2 = RoomGameStore(db2)
        store2.replaceAll(snapshot)
        assertEquals(snapshot.copy(history = snapshot.history), store2.exportAll())
        // And behaves the same as the reference in-memory store for further play.
        val mem = InMemoryGameStore(snapshot)
        val s2 = GameService(store2, service.catalog, (1..20).mapNotNull { service.level(it) }, TestClock)
        val sm = GameService(mem, service.catalog, (1..20).mapNotNull { service.level(it) }, TestClock)
        s2.completeLevel(win("c01_l13")); sm.completeLevel(win("c01_l13"))
        assertEquals(mem.data.wallet, store2.exportAll().wallet)
        assertEquals(mem.data.lootboxes, store2.exportAll().lootboxes)
        db2.close()
    }

    @Test
    fun migrationFromVersionOneKeepsProgress() {
        val name = "migration-test.db"
        context.deleteDatabase(name)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) { AppDatabase.V1_SCHEMA.forEach { db.execSQL(it) } }
                override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build()
        )
        helper.writableDatabase.apply {
            execSQL("INSERT INTO wallet (id, coins, tokens) VALUES (0, 1234, 56)")
            execSQL("INSERT INTO ledger (`key`, claimedAt) VALUES ('level.first.c01_l01', 1)")
            execSQL("INSERT INTO owned_cards (cardId, count, firstObtainedAt, lastObtainedAt) VALUES ('bts_001', 3, 1, 2)")
            execSQL("INSERT INTO meta (`key`, value) VALUES ('progress.highestCleared', '1')")
        }
        helper.close()
        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(*AppDatabase.ALL_MIGRATIONS).build()
        val data = runBlocking { RoomGameStore(migrated).exportAll() }
        assertEquals(1234L, data.wallet.coins)
        assertEquals(56L, data.wallet.tokens)
        assertEquals(3, data.cards.single().count)
        assertFalse(data.cards.single().favorite)
        assertTrue(data.ledger.containsKey("level.first.c01_l01"))
        // New v2 table is usable.
        runBlocking { RoomGameStore(migrated).transaction { it.addRewardLog(com.cozycards.game.state.RewardLogEntry("k", 1, "test")) } }
        assertEquals(1, runBlocking { RoomGameStore(migrated).exportAll() }.rewardLog.size)
        migrated.close()
    }
}
