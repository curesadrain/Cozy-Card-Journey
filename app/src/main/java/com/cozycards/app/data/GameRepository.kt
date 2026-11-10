package com.cozycards.app.data

import android.content.Context
import android.net.Uri
import android.util.Log
import com.cozycards.app.data.db.RoomGameStore
import com.cozycards.app.data.db.RoomGameStore.Companion.toModel
import com.cozycards.game.GameService
import com.cozycards.game.economy.BoosterType
import com.cozycards.game.economy.Cosmetic
import com.cozycards.game.economy.CosmeticSlot
import com.cozycards.game.progression.GameClock
import com.cozycards.game.progression.Stat
import com.cozycards.game.state.CardHistoryEntry
import com.cozycards.game.state.LevelProgress
import com.cozycards.game.state.OpenedLootbox
import com.cozycards.game.state.OwnedCard
import com.cozycards.game.state.PendingLootbox
import com.cozycards.game.state.PityState
import com.cozycards.game.state.RewardLogEntry
import com.cozycards.game.state.SaveCodec
import com.cozycards.game.state.Wallet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.TimeZone

/** Immutable snapshot of everything the UI shows about the player. */
data class PlayerState(
    val loaded: Boolean = false,
    val wallet: Wallet = Wallet(),
    val progress: Map<String, LevelProgress> = emptyMap(),
    val highestCleared: Int = 0,
    val cards: Map<String, OwnedCard> = emptyMap(),
    val lootboxes: List<PendingLootbox> = emptyList(),
    val boosters: Map<BoosterType, Int> = emptyMap(),
    val pity: PityState = PityState(),
    val equipped: Map<CosmeticSlot, Cosmetic> = emptyMap(),
    val ownedCosmetics: Set<Cosmetic> = emptySet(),
    val stats: Map<Stat, Long> = emptyMap(),
    val recentOpened: List<OpenedLootbox> = emptyList(),
    val recentRewards: List<RewardLogEntry> = emptyList(),
) {
    val currentLevel get() = (highestCleared + 1).coerceAtMost(1000)
    fun isUnlocked(number: Int) = number <= highestCleared + 1
    fun equippedOrDefault(slot: CosmeticSlot) = equipped[slot] ?: Cosmetic.defaultFor(slot)
}

object SystemClock : GameClock {
    override fun now() = System.currentTimeMillis()
    override fun zoneOffsetMillis() = TimeZone.getDefault().getOffset(System.currentTimeMillis()).toLong()
}

/**
 * Bridges the pure [GameService] and the UI: runs operations, then republishes [state].
 * Also maintains rolling JSON backups used for crash/corruption recovery.
 */
class GameRepository(
    private val context: Context,
    private val store: RoomGameStore,
    val content: ContentRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    val service = GameService(store, content.catalog, content.levels, SystemClock)
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state
    private val backupDir = File(context.filesDir, "backups").apply { mkdirs() }
    private var backupJob: Job? = null

    init {
        store.afterCommit = {
            scope.launch { refresh() }
            scheduleBackup()
        }
    }

    suspend fun start() {
        restoreIfDatabaseLost()
        service.initialize()
        refresh()
        writeBackup()
    }

    private val refreshLock = kotlinx.coroutines.sync.Mutex()

    /** Serialised so an older snapshot can never overwrite a newer one. */
    suspend fun refresh() = refreshLock.withLock { refreshInternal() }

    private suspend fun refreshInternal() {
        val s = store.read { dao ->
            val meta = dao.allMeta().associate { it.key to it.value }
            val owned = Cosmetic.entries.filter { dao.ledgerCount("cosmetic.${it.name}") > 0 }.toSet()
            PlayerState(
                loaded = true,
                wallet = dao.wallet()?.let { Wallet(it.coins, it.tokens) } ?: Wallet(),
                progress = dao.allLevels().associate { it.levelId to it.toModel() },
                highestCleared = meta[GameService.KEY_HIGHEST_CLEARED]?.toIntOrNull() ?: 0,
                cards = dao.allCards().associate { it.cardId to it.toModel() },
                lootboxes = dao.lootboxes().map { it.toModel() },
                boosters = BoosterType.entries.associateWith { t -> dao.booster(t.name)?.count ?: 0 },
                pity = PityState(meta[GameService.KEY_PITY_EPIC]?.toIntOrNull() ?: 0, meta[GameService.KEY_PITY_LEGENDARY]?.toIntOrNull() ?: 0),
                equipped = CosmeticSlot.entries.associateWith { slot ->
                    meta[GameService.equippedKey(slot)]?.let { runCatching { Cosmetic.valueOf(it) }.getOrNull() } ?: Cosmetic.defaultFor(slot)
                },
                ownedCosmetics = owned,
                stats = Stat.entries.associateWith { meta[GameService.statKey(it)]?.toLongOrNull() ?: 0 },
                recentOpened = dao.recentOpened(30).map { it.toModel() },
                recentRewards = dao.recentRewardLog(30).map { RewardLogEntry(it.key, it.at, it.description, it.coins, it.tokens) },
            )
        }
        _state.value = s
    }

    suspend fun cardHistory(cardId: String): List<CardHistoryEntry> =
        store.read { dao -> dao.historyFor(cardId).map { CardHistoryEntry(it.cardId, it.at, it.source) } }

    // ------------------------------------------------------------------ backups

    private val autoBackup get() = File(backupDir, "autosave.json")
    private val previousBackup get() = File(backupDir, "autosave.prev.json")

    private fun scheduleBackup() {
        backupJob?.cancel()
        backupJob = scope.launch {
            delay(1500)
            writeBackup()
        }
    }

    /** Atomic write: temp file + rename, keeping the previous generation. */
    suspend fun writeBackup() = withContext(Dispatchers.IO) {
        runCatching {
            val text = SaveCodec.export(store.exportAll(), System.currentTimeMillis())
            val tmp = File(backupDir, "autosave.tmp")
            tmp.writeText(text)
            if (autoBackup.exists()) autoBackup.copyTo(previousBackup, overwrite = true)
            if (!tmp.renameTo(autoBackup)) { autoBackup.delete(); tmp.renameTo(autoBackup) }
        }.onFailure { Log.w("GameRepository", "backup failed", it) }
    }

    /**
     * If the database is empty (deleted after corruption, or wiped) but a valid backup exists,
     * restore it. Falls back to the previous backup generation if the newest is damaged.
     */
    private suspend fun restoreIfDatabaseLost() {
        val initialized = store.read { it.ledgerCount("init.starter") > 0 }
        if (initialized) return
        val ids = content.catalog.cards.map { it.id }.toSet()
        for (f in listOf(autoBackup, previousBackup)) {
            if (!f.exists()) continue
            val data = runCatching { SaveCodec.import(f.readText(), ids) }.getOrNull() ?: continue
            store.replaceAll(data)
            Log.i("GameRepository", "Progress restored from ${f.name}")
            restoredFromBackup = true
            return
        }
    }

    var restoredFromBackup = false
        private set

    suspend fun exportTo(uri: Uri) = withContext(Dispatchers.IO) {
        val text = SaveCodec.export(store.exportAll(), System.currentTimeMillis())
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) } ?: error("Can't write file")
    }

    /** Validates fully before replacing anything; a bad file leaves progress untouched. */
    suspend fun importFrom(uri: Uri) {
        val text = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: error("Can't read file")
        }
        val data = SaveCodec.import(text, content.catalog.cards.map { it.id }.toSet())
        writeBackup() // keep the current state recoverable
        store.replaceAll(data)
        refresh()
    }
}
